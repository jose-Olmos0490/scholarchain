# Phase 03 — repair-01

- Type: constitutional repair (agent control plane)
- Requested by: human architect
- Prepared by: factory-engineer (proposal; not self-ratified)
- Defect source: dataset-engineer, Phase 03 Work Order A report (STATUS: BLOCKED-ON)

## Defect
`.claude/hooks/guard-dangerous.sh`, in `check_scripts_and_agent_fences`, used the regex
`(^|[[:space:]/])scripts/([A-Za-z0-9_-]+)\.sh`. Because `/` was allowed before `scripts/`,
it matched `datasets/scripts/download.sh` and applied the repository-root infra-script
authority to it ("Blocked: dataset-engineer executing scripts/download.sh"). The same rule
blocked `chmod +x datasets/scripts/download.sh` and a `bash -n` for-loop over the dataset
scripts. docs/agents.md gives dataset-engineer `datasets/scripts/**` (bounded download and
sampling) and gates it on dataset validation, so the guard contradicted the ratified matrix.

## Fix
- The root `scripts/*.sh` authority rule (regex, per-script allowlist, approved-env check)
  is unchanged for every agent.
- New narrow exemption: for `dataset-engineer` only, a command is exempt when every
  `scripts/<name>.sh` word resolves (via `realpath -m`, the hook `cwd`, ".", "..", "//",
  and symlinks) to `<repo>/datasets/scripts/<name>.sh`, and the command has one of these shapes:
  direct execution, `bash|sh [-flags] <script>` (no -c/-o), `chmod [ugoa]+x <scripts>`,
  or `for v in <scripts>`. Anything else falls through to the existing rule (fail closed).
- Other agents, other paths, and `chmod` modes other than `+x` keep their prior behavior.
- 14 permanent guard probes were added to `.claude/hooks/validate-agent-files.sh`.

## Files changed
- `.claude/hooks/guard-dangerous.sh`
- `.claude/hooks/validate-agent-files.sh`

## Validation
- `.claude/hooks/validate-agent-files.sh --all`: exit 0, PASS=396 FAIL=0 SKIP=0
- `bash -n` on all four hooks: exit 0
- `shellcheck -S warning .claude/hooks/*.sh`: exit 0 (default severity: info-only notes, same count as HEAD)
- JSON parse of `.claude/settings.json` and `.mcp.json`: OK; all hooks executable

## Probes (synthetic hook JSON; nothing executed)
- Still blocked (exit 2) for dataset-engineer: `scripts/deploy.sh`, `./scripts/deploy.sh`,
  `bash scripts/deploy.sh`, `sh scripts/deploy.sh`, `<repo>/scripts/deploy.sh`,
  `datasets/scripts/../../scripts/deploy.sh`, `foo/../scripts/deploy.sh`,
  `datasets//scripts/../../scripts/deploy.sh`, `chmod +x scripts/deploy.sh`,
  `datasets/scripts/download.sh scripts/deploy.sh`, and a symlink
  `datasets/scripts/evil.sh -> ../../scripts/deploy.sh` (tested in an isolated scratch tree).
- Now allowed (exit 0) for dataset-engineer: `datasets/scripts/download.sh --dry-run`
  (relative, `./`, `bash`, `sh`, absolute), `chmod +x datasets/scripts/download.sh`.
- Unchanged (exit 2): hadoop-engineer, infra-engineer, and the orchestrator running
  or chmod'ing `datasets/scripts/download.sh`.

## Remaining risk
- Existing guard gaps, not changed by this repair: `cd scripts && ./deploy.sh` and
  `bash -c "scripts/deploy.sh"` are not caught by the script-authority regex.
- The exemption does not require `--dry-run`; dataset-engineer may run its scripts in
  full mode (bounded by its role file and the spec's "must not download a full corpus by default").

## Decision
(human) accepted / rejected — manual changes — accepted commit hash

## Human ratification
- Ratified: guard exception letting dataset-engineer run and chmod +x datasets/scripts/*.sh, including full mode. Full mode is still gated inside the script by --full plus SCHOLARCHAIN_ALLOW_FULL_DOWNLOAD=1, which only a human sets.
- Accepted: nested script folders (for example modules/*/scripts/*.sh) stay blocked for every agent.
- Known residual risk, not fixed here: "cd scripts && ./deploy.sh" and "bash -c 'scripts/deploy.sh'" bypass the guard both before and after this change. A separate factory-engineer repair is scheduled before Phase 13 (deployment scripts).
