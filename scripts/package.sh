#!/usr/bin/env bash
# package.sh - build the Hadoop and Spark job JARs and the service images, and record checksums.
#
#   scripts/package.sh [--skip-images] [--skip-jars] [--help]
#
# Steps: refuse a dirty tree; sbt packageJobs (hadoop and Spark assembly JARs); sbt dockerLocal
# (the three service images, built into the local Docker daemon, never pushed); retag each image
# as scholarchain-<service>:<short git sha> and drop the sbt version tag, so the SHA is the only
# tag (no "latest", ever); write build/package-manifest.json.
#
# build/package-manifest.json records: commit SHA (short and full), UTC build time, every JAR
# with its SHA-256 and size, every image with its tag and local image ID. Image digests exist
# only after a push; scripts/deploy.sh records the registry digests.
# --skip-jars / --skip-images leave that part out and the manifest says so explicitly
# ("jarsSkipped": true, "imagesSkipped": true, "complete": false); a partial manifest is not a
# release package manifest and the evidence archiver lists it as partial in MISSING.txt.
#
# Required environment variables: none. No AWS access and no credentials are used.
# Optional: SC_SBT_TIMEOUT (seconds per sbt invocation, default 3600), SC_BUILD_DIR (output
#   directory, default <repo>/build, untracked).
# Exit codes: 0 packaged, 1 build or check failure, 2 usage error.

set -euo pipefail
IFS=$'\n\t'

# shellcheck source=lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

SKIP_IMAGES=false
SKIP_JARS=false
SBT_TIMEOUT="${SC_SBT_TIMEOUT:-3600}"
SERVICES=(query-api graph-evidence-api embedding-worker)

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-images) SKIP_IMAGES=true; shift ;;
    --skip-jars) SKIP_JARS=true; shift ;;
    -h | --help) print_help ;;
    *) usage_error "unknown argument: $1" ;;
  esac
done

require_cmd git sbt python3 sha256sum timeout
[[ "$SKIP_IMAGES" == true ]] || require_cmd docker

step "refuse a dirty tree"
require_clean_tree
SHA="$(head_sha)"
FULL_SHA="$(git -C "$SC_REPO_ROOT" rev-parse HEAD)"
info "commit $SHA ($FULL_SHA)"

mkdir -p "$SC_BUILD_DIR"
MANIFEST="$SC_BUILD_DIR/package-manifest.json"
declare -a JAR_ENTRIES=()
declare -a IMAGE_ENTRIES=()

if [[ "$SKIP_JARS" == false ]]; then
  step "sbt packageJobs (Hadoop and Spark assembly JARs)"
  (cd "$SC_REPO_ROOT" && timeout --foreground "$SBT_TIMEOUT" sbt -batch packageJobs)

  step "checksum the JARs"
  for spec in "modules/hadoop/target|scholarchain-hadoop-assembly-*.jar" \
    "modules/spark/target|scholarchain-spark-analytics-assembly-*.jar"; do
    jar="$(newest_file "$SC_REPO_ROOT/${spec%%|*}" "${spec##*|}")"
    [[ -n "$jar" && -f "$jar" ]] || die "no JAR matching ${spec##*|} under ${spec%%|*}"
    digest="$(sha256_of "$jar")"
    JAR_ENTRIES+=("${jar#"$SC_REPO_ROOT"/}|$digest|$(stat -c %s "$jar")")
    info "JAR ${jar#"$SC_REPO_ROOT"/} sha256=$digest"
  done
fi

if [[ "$SKIP_IMAGES" == false ]]; then
  step "sbt dockerLocal (service images into the local Docker daemon, no push)"
  (cd "$SC_REPO_ROOT" && timeout --foreground "$SBT_TIMEOUT" sbt -batch dockerLocal)
  VERSION="$(cd "$SC_REPO_ROOT" && timeout --foreground 300 sbt -batch -error "print services/version" | tail -n 1)"
  [[ -n "$VERSION" ]] || die "could not read the sbt project version"

  step "tag the images with the git SHA only"
  for svc in "${SERVICES[@]}"; do
    src="scholarchain-$svc:$VERSION"
    dst="scholarchain-$svc:$SHA"
    timeout --foreground 120 docker image inspect "$src" >/dev/null 2>&1 || die "image $src was not built"
    docker tag "$src" "$dst"
    if [[ "$src" != "$dst" ]]; then docker rmi "$src" >/dev/null; fi
    image_id="$(timeout --foreground 120 docker image inspect --format '{{.Id}}' "$dst")"
    IMAGE_ENTRIES+=("scholarchain-$svc|$SHA|$image_id")
    info "image $dst id=$image_id"
  done
fi

step "write $MANIFEST"
python3 -I - "$MANIFEST" "$SHA" "$FULL_SHA" "$(utc_now)" "$SKIP_JARS" "$SKIP_IMAGES" "${#JAR_ENTRIES[@]}" \
  "${JAR_ENTRIES[@]}" "${IMAGE_ENTRIES[@]}" <<'PY'
import json
import sys

out, sha, full, built, skip_jars, skip_images, njars, *rest = sys.argv[1:]
njars = int(njars)
jars = []
for entry in rest[:njars]:
    path, digest, size = entry.split("|")
    jars.append({"path": path, "sha256": digest, "bytes": int(size)})
images = []
for entry in rest[njars:]:
    name, tag, image_id = entry.split("|")
    images.append({"name": name, "tag": tag, "localImageId": image_id})
jars_skipped = skip_jars == "true"
images_skipped = skip_images == "true"
# "complete" is true only when neither part was skipped; the evidence archiver refuses a partial
# manifest as a release package manifest.
doc = {"commitSha": sha, "commitShaFull": full, "builtAtUtc": built,
       "jarsSkipped": jars_skipped, "imagesSkipped": images_skipped,
       "complete": not jars_skipped and not images_skipped,
       "jars": jars, "images": images}
with open(out, "w") as fh:
    json.dump(doc, fh, indent=2)
    fh.write("\n")
PY
cat "$MANIFEST"
if [[ "$SKIP_JARS" == true || "$SKIP_IMAGES" == true ]]; then
  warn "PARTIAL manifest (jars skipped: $SKIP_JARS, images skipped: $SKIP_IMAGES); it is not a release package manifest"
fi
step "done: PACKAGE_MANIFEST=$MANIFEST"
