# Phase 04 test reports

## Work order C: test-engineer, attempt 1 (BLOCKED-ON)

```text
STATUS: BLOCKED-ON
OWNER: test-engineer
WORK ORDER: Phase 04, Work order C (adversarial tests for the Hadoop ingestion module), from docs/agent-runs/phase-04/prompt.md

I wrote no tests and ran no tests. sbt does not compile or run anything under `tests/**` yet, and my role says to stop and return BLOCKED-ON build-engineer in that case.

FILES CREATED: none
FILES MODIFIED: none

COMMANDS RUN (all read-only):
- `cat docs/agent-runs/phase-04/prompt.md; ls docs/agent-runs/phase-04/; cat build.sbt; ls -la tests` -> `ls: cannot access 'tests': No such file or directory`
- `grep -n -i "tests\|unmanagedSourceDirectories\|Test /" build.sbt` -> the only matches are comments, `Test / testOptions`, `Test / fork`, `Test / javaOptions`, `Test / parallelExecution` and the `markTestRun` task. No project points at `tests/`, and no source directory includes it.
- `grep -rn "tests" project/*.scala project/*.sbt build.sbt` -> nothing that wires `tests/` in.
- `grep -n "hadoopJobs|common" -A8 project/Dependencies.scala` -> `hadoopJobs = Seq(hadoopClient)`, which is Provided. `common = Seq(scalatest)`.

TEST RESULTS: passed=0 failed=0 skipped=0 errored=0. No test suite was run.

VALIDATION RESULTS:
- I did not run `sbt check`. I changed nothing, so the tree is the same one hadoop-engineer last reported at 511/511. I did not observe that result myself.
- The build defines these projects: `core`, `hadoop`, `entityResolution`, `sparkAnalytics`, `storage`, `rag`, `services` and `root`. `root` lives at `.`, so its Test sources would be `./src/test/scala`, not `tests/`.
- Because nothing is wired to `tests/`, any Scala file I put there would never be compiled or run. It also would not count toward `sbt check` or `markTestRun`.

DEFECTS FOUND: none (no tests were run).

COVERAGE BY REQUIREMENT: none yet. These nine categories are planned and wait on the build wiring:
- malformed and truncated XML
- unknown entities
- Unicode and confusable characters
- missing fields
- duplicate and conflicting DOIs
- common names (block overflow)
- reordered author lists
- a team larger than `maxPairExpansionTeamSize`
- a record straddling a split boundary

ASSUMPTIONS:
- The tests will use hadoop's public API and LocalJobRunner, so the test project needs `hadoop` and `core` on its classpath. It also needs hadoop-client at test time.
- In sbt, Provided dependencies of `hadoop` do not pass to projects that depend on it. The test project must declare hadoop-client itself.

RISKS:
- The "Module dependency boundaries" table in CLAUDE.md does not list a test project. It may need a constitutional amendment by factory-engineer, ratified by a human, to record a `tests` project that depends on `core` and `hadoop`. In docs/agents.md, `tests/**` is already test-engineer's territory.
- Phase 4 cannot meet the gate item "Adversarial tests are present. sbt check passes" until the wiring exists and I rerun.

MANUAL ACTION REQUIRED: The orchestrator routes the wiring request to build-engineer and records the event as `repair-NN.md`.

BLOCKED-ON OWNER: build-engineer. A factory-engineer note may also be needed for the CLAUDE.md module table.

BLOCKED-ON EVIDENCE:
- `tests/` does not exist, and neither `build.sbt` nor `project/*` refers to it.
- The `check` alias only runs projects aggregated under `root`: `core`, `hadoop`, `entityResolution`, `sparkAnalytics`, `storage`, `rag` and `services`.
- The minimal wiring I need, which build-engineer writes and I do not:
  1. A new sbt project, for example `lazy val adversarialTests = (project in file("tests"))`, using the default layout: `tests/src/test/scala` and `tests/src/test/resources`.
  2. `.dependsOn(core, hadoop)` for compile access only. I do not need hadoop's test classes.
  3. `.settings(commonSettings, clusterJobSettings)` to get ScalaTest, the AWS-integration exclusion, `Test / fork := true`, the Java 17 openings and `Test / parallelExecution := false`.
  4. `libraryDependencies ++= Dependencies.hadoopJobs`. Provided scope is fine because Provided is on the declaring project's own Test classpath. This puts hadoop-client on the test classpath, since it does not come through `hadoop`.
  5. `publish / skip := true`. No assembly is needed.
  6. Add the project to `root`'s `.aggregate(...)`, so that `test`, `scalafmtCheckAll` and `markTestRun` in `sbt check` cover it.
  7. Optionally, set `Test / baseDirectory` (or the forked working directory) to the repo root. Tests can then resolve `datasets/samples/dblp/dblp-2023-06-28.dtd` by relative path, the same way `hadoop/runMain` does. Otherwise I will build absolute paths from a system property.

NEXT GATE: build-engineer adds the `tests` project, runs `sbt check` and reports. Then the orchestrator reruns test-engineer for Work order C in a fresh context, and I write the adversarial suites and report exact pass and fail counts.
```

Repair between attempt 1 and attempt 2: repair-03 (build-engineer, sbt project `adversarialTests` at tests/).

## Work order C: test-engineer, attempt 2 (FAILED: 10 production defects found)

```text
STATUS: FAILED
OWNER: test-engineer
WORK ORDER: Phase 04, Work order C (attempt 2): adversarial tests for the Hadoop ingestion layer under tests/** only

`sbt check` fails only because of the 10 adversarial tests that expose production defects. Per the work order I left them failing: none is ignored, pending or weakened. Formatting, compilation, the core suite (466/466) and the hadoop suite (45/45) all pass. `markTestRun` did not run, so `.claude/.last-test-run` was not updated, and the Stop hook will report the tests as stale until the defects are fixed.

FILES CREATED:
- /home/olmos/scholarchain/tests/src/test/scala/scholarchain/adversarial/Adv.scala (my own support code: reader driver, LocalJobRunner pipeline runner, table readers; scratch output goes only to tests/target/adversarial-scratch)
- /home/olmos/scholarchain/tests/src/test/scala/scholarchain/adversarial/MalformedXmlSpec.scala
- /home/olmos/scholarchain/tests/src/test/scala/scholarchain/adversarial/EntitySpec.scala
- /home/olmos/scholarchain/tests/src/test/scala/scholarchain/adversarial/UnicodeSpec.scala
- /home/olmos/scholarchain/tests/src/test/scala/scholarchain/adversarial/MissingFieldsSpec.scala
- /home/olmos/scholarchain/tests/src/test/scala/scholarchain/adversarial/DoiSpec.scala
- /home/olmos/scholarchain/tests/src/test/scala/scholarchain/adversarial/BlockOverflowSpec.scala
- /home/olmos/scholarchain/tests/src/test/scala/scholarchain/adversarial/TeamSpec.scala
- /home/olmos/scholarchain/tests/src/test/scala/scholarchain/adversarial/SplitBoundarySpec.scala

FILES MODIFIED: none outside tests/. `git status` before and after is identical except `?? tests/`. target/phase04/run-a, run-b and probe were not touched.

COMMANDS RUN:
- `sbt -batch "adversarialTests/Test/compile"`: success
- `sbt -batch "adversarialTests/test"` (first run): 77 tests, 66 passed, 11 failed. One failure was a fault in my own test: the "two root elements" case is split into two values by the reader, so it cannot be a single pipeline reject. I fixed the test by keeping that case parser-only.
- `sbt -batch "adversarialTests/Test/scalafmt" "adversarialTests/Test/compile"`: reformatted my 9 files only; success
- `sbt -batch check` (final, current tree): exit 1, caused only by `adversarialTests / Test / test`

TEST RESULTS (final `sbt check`, exact as observed):
- core: passed=466 failed=0 skipped=0 errored=0
- hadoop: passed=45 failed=0 skipped=0 errored=0
- adversarialTests: passed=67 failed=10 skipped=0 errored=0 (8 suites completed, 0 aborted)
- Total: passed=578 failed=10 skipped=0 errored=0

VALIDATION RESULTS: `scalafmtCheckAll`, `scalafmtSbtCheck` and `Test/compile` passed. `markTestRun` did not run because tests failed.

DEFECTS FOUND (test | failing input | expected | observed | owner):
1. MalformedXmlSpec "a truncated inproceedings does not swallow the following complete records (reader)" | Good1, then `<inproceedings key="conf/adv/Trunc1">` with no end tag, then Good2, Good3 and a complete inproceedings Good4 | each complete record is its own reader value | one value spans Trunc1+Good2+Good3+Good4, because `DblpRecordReader.readToEnd` scans to the next `</inproceedings>` | hadoop-engineer
2. MalformedXmlSpec "a truncated article does not swallow the next article (reader)" | truncated article Trunc2, then Good1, Good2 | Good1 read on its own | Trunc2+Good1 come out as one value | hadoop-engineer
3. MalformedXmlSpec "with a truncated record mid-file, every split size yields the single-split records" | same document, split sizes 16, 32, 50, 64, 100 and 300 | identical to one split | with one split, Good2 exists only inside the Trunc2 value; with small splits it is also read on its own. Output depends on split size, which breaks the reader's own boundary invariant | hadoop-engineer
4. MalformedXmlSpec "pipeline: a truncated record mid-file costs one reject, not its neighbours" | the input from defect 1 through the full driver | 4 works accepted, 1 reject | only journals/adv/Good1 is accepted (J01 INPUT_RECORDS=2, REJECTED=1). Three valid records are lost inside one MALFORMED_XML reject, and the reject excerpt is cut at 1024 code points | hadoop-engineer
5. EntitySpec "an undeclared entity with a non-ASCII name is UnknownEntity" | `<title>x &café; y</title>` | `UnknownEntity("café")`, which gives reject UNKNOWN_ENTITY and the UNKNOWN_ENTITY counter | `Malformed("[com.ctc.wstx.exc.WstxLazyException] Undeclared general entity \"café\"")`. The ASCII-only `EntityRef` regex in `DtdEntities.substitute` misses legal non-ASCII XML names | hadoop-engineer
6. DoiSpec "a percent-encoded DOI URL and its decoded form normalize to the same DOI" | `https://doi.org/10.1002/%28SICI%291097-4571%28199806%2949%3A8` vs `https://doi.org/10.1002/(SICI)1097-4571(199806)49:8` | both give `10.1002/(sici)1097-4571(199806)49:8` | the encoded form gives `10.1002/%28sici%291097-4571%28199806%2949%3a8`, so a duplicate DOI is missed in J03/J04 | domain-engineer (`ExternalIds.normalizeDoi`, modules/core)
7. UnicodeSpec "a soft hyphen (U+00AD) inside a surname does not split it" | `Normalize.surnameAndInitial("John Smi­th")` | `("smith","j")` | `("th","j")`. Invisible format characters split tokens, which corrupts blocking keys. U+00AD is legal in the ISO-8859-1 DBLP file | hadoop-engineer (`text/Normalize.fold`)
8. UnicodeSpec "a zero-width space (U+200B) inside a surname does not split it" | `"John Smi​th"` | `("smith","j")` | `("th","j")` | hadoop-engineer
9. UnicodeSpec "the same author spelled in NFC and NFD on one paper is one team member" | authors `José Pérez` (NFC), `Other Person`, `José Pérez` (NFD) | team size 2, removedDuplicates=1 | team size 3, which produces a self-coauthor pair. `TeamExpansion.mentionKey` compares raw `sourceAuthorKey` without Unicode canonical normalization | hadoop-engineer
10. UnicodeSpec "pipeline: bytes that are illegal in the declared UTF-8 are rejected, not replaced by U+FFFD" | a UTF-8-declared file whose title contains bytes C3 28 and FF | MALFORMED_XML reject (XML 1.0 §4.3.3 makes illegal bytes in the declared encoding a fatal error) | accepted with title `"Invalid �( � bytes."`. `DblpExtractMapper` decodes with `new String(..., charset)`, which silently replaces bad bytes | hadoop-engineer

COVERAGE BY REQUIREMENT (passing tests confirm the behaviour; failing ones are the defects above):
- Malformed and truncated XML: reader swallowing and split-size dependence fail (defects 1–4). Ten malformed-record shapes all give Malformed and never throw (mismatched or unclosed tags, unquoted or duplicate attribute, bare `&`, stray `<`, `&#0;`, lone surrogate, two roots, entity without `;`). In the pipeline, 9 malformed records give exactly 9 MALFORMED_XML rejects, neighbours are accepted, and every reject is a valid RejectRecord of at most 1024 code points. A file cut at every 3rd byte never yields a partial record that parses as valid.
- Unknown entities: entity in an attribute; case-sensitive names (`&UUML;` unknown); `&amp;bogus;` and `&#38;bogus;` stay text; an external general entity (XXE) is never resolved; an external file or network parameter entity makes the DTD load fail; a billion-laughs DTD stays bounded; entity text never creates elements. The non-ASCII entity name fails (defect 5).
- Unicode and confusables: fullwidth and mathematical-bold letters fold to ASCII; folding does not depend on the default locale (tr-TR); NFC and NFD give the same fold; supplementary-plane initials stay whole; Cyrillic look-alikes are never merged, either as team members or as blocking keys; UTF-8 CJK, emoji and Greek text survive J01/J01C exactly; multibyte characters straddling split sizes 1–120 read intact. Defects 7–10 fail.
- Missing fields: whitespace-only or markup-only titles give MISSING_TITLE; blank, empty or absent keys give MISSING_KEY; blank or markup-only authors give MISSING_AUTHOR_NAME; a key containing a newline is not accepted; a padded year still parses; a non-integer or empty year is out of subset; empty records are not accepted; degenerate ee links give no DOI or arXiv ID; punctuation-only names and titles give no key rather than an empty one; a bare "0001" name is not emptied.
- Duplicate and conflicting DOIs: DOI case, resolver and whitespace variants collapse to one value. The outcome rule holds (DUPLICATE, CONFLICT, MATCH, ORCID conflict on one work) and does not depend on the order of the 24 permutations tested. In the pipeline, one DOI in 3 spellings on 3 keys gives one DUPLICATE outcome with the correct recordRefs and derivedFrom; a byte-identical repeat counts as DUPLICATE; a conflicting key keeps one version and writes one CONFLICTING_RECORD reject, and the kept version does not depend on input order. The percent-encoding case fails (defect 6).
- Common names (block overflow), maxBlockSize=3: no block exceeds 3; a group of exactly 3 stays a primary block; all 7 "Wei Wang" mentions are each placed exactly once (fallback block or oversize); REKEYED_MEMBERS=17, OVERSIZE_BLOCK=3, OVERSIZE_BLOCK_MEMBERS=15, matching 15 oversize rows with the expected refs; no candidate comes from an oversize group; the largest group is reported as 7 under r|wang|w.
- Reordered author lists: the same unordered name pairs, with positions following the names; a duplicate at different positions gives the same team with first-occurrence positions kept; homonym suffixes are not de-duplicated; work features are the same under reordering and symmetric.
- Giant team above maxPairExpansionTeamSize: 2001 authors at the default thresholds 50/2000, plus a 51-author and a 3-author record. The giant record is larger than 64 KB and is read whole. OVERSIZE_AUTHOR_TEAM=1, one oversize_team row of size 2001, no pairs for it, EMITTED_PAIRS=1278; the team row and all 2001 author_paper rows are emitted with teamSize 2001; the stats report maxTeamSize=2001 with its workId; the 51-author team is large and expanded. Routing boundaries hold and the routing size is the de-duplicated size.
- Records straddling split boundaries: a quoted `>` and `/>` in attributes, CRLF and tab after the element name, adjacent records, decoy element names, a self-closing record, a record ending at EOF, and a 90 KB record. Records and offsets match for split sizes 1–80 and a set of large sizes, with a boundary at every byte of a start tag and an end tag. The giant record runs through the full pipeline with 4096-byte splits (more than 10 map tasks) and gives the same logical output as one split.

ASSUMPTIONS:
- Defects 7–9 assume names that differ only by invisible format characters or Unicode canonical equivalence are the same name for keys and for de-duplication within one paper. This follows the spec's "Unicode normalization" requirement.
- Defect 6 assumes percent-encoded DOI URLs identify the same DOI, as the DOI Handbook states.
- Confusables (Cyrillic and Latin look-alikes) are deliberately not treated as defects. The tests assert that they are never merged and do not require skeleton matching.

RISKS:
- Confusable names get different blocking keys, so cross-script look-alikes never become match candidates. This is a recall risk for Phase 5 entity resolution, not asserted as a defect.
- A UTF-16 DBLP file would yield 0 records with no reject, because the reader synchronizes on ASCII bytes. Not tested.
- When a DBLP record has several DOI ee links, only the first is indexed for exact matching. Not tested as a defect.
- An entity reference inside a comment or CDATA is still resolved (a documented known limit). Not tested.
- The adversarial suite adds about 5.5 minutes to `sbt check`.

MANUAL ACTION REQUIRED: none from the human, beyond routing the defects.
BLOCKED-ON OWNER: none. Defects 1–5 and 7–10 go to hadoop-engineer; defect 6 goes to domain-engineer.
BLOCKED-ON EVIDENCE: n/a. The failing output is above; the full log is /tmp/claude-1000/-home-olmos-scholarchain/6b01634c-cc8a-448e-b030-ceacd9f03210/scratchpad/check.log
NEXT GATE: owner repair, then test rerun (`sbt adversarialTests/test`, then `sbt check`)
```

_Orchestrator note: repair-04 (domain-engineer, defect 6) and repair-05 (hadoop-engineer, defects 1–5 and 7–10) came between attempts 2 and 3._

## Work order C: test-engineer, attempt 3

```text
STATUS: FAILED
OWNER: test-engineer
WORK ORDER: Phase 04, Work order C (attempt 3, fresh context). Rerun the adversarial suites after Repair 04 (domain-engineer) and Repair 05 (hadoop-engineer), then probe those repairs adversarially. Tests go only under tests/**.

Summary: all 9 suites from attempt 2 now pass (77/77), so the 10 earlier defects are confirmed fixed. Of the 23 new probe tests, 19 pass and 4 fail. The 4 failures come from 2 new defects, one in core and one in hadoop. `sbt check` fails because of these 4 tests, which are left failing on purpose.

FILES CREATED:
- /home/olmos/scholarchain/tests/src/test/scala/scholarchain/adversarial/TruncationSplitSpec.scala (9 tests)
- /home/olmos/scholarchain/tests/src/test/scala/scholarchain/adversarial/RepairProbeSpec.scala (14 tests)

FILES MODIFIED: none. The 9 attempt-2 suites are unchanged. No production, build or constitutional file was touched. Scratch output went only to tests/target/adversarial-scratch and my session scratchpad; target/phase04/* was not touched.

COMMANDS RUN:
1. `timeout 1500 sbt check` (baseline). Log: /tmp/claude-1000/-home-olmos-scholarchain/6b01634c-cc8a-448e-b030-ceacd9f03210/scratchpad/check1.log
   - Format check, compile and the `test` step ran on the tree before my files existed. All passed: core 475/475, hadoop 52/52, adversarialTests 77/77.
   - The `markTestRun` step then compiled my new files mid-run. My own 1500 s timeout killed it, so it had no final result (EXIT=124). Its partial output is where I first saw the failures.
2. `sbt "adversarialTests/Test/scalafmt"` (formats only my own test sources), run twice.
3. `sbt check` (interim run). Log: check2.log. EXIT=1, adversarialTests 99 run, 96 passed, 3 failed.
4. `sbt "adversarialTests/testOnly scholarchain.adversarial.RepairProbeSpec -- -z \"encoded control\""` (diagnostic run of one test). 1 run, 1 failed.
5. `sbt check` (final run, current tree). Log: /tmp/claude-1000/-home-olmos-scholarchain/6b01634c-cc8a-448e-b030-ceacd9f03210/scratchpad/check3.log. EXIT=1.

TEST RESULTS (final `sbt check`, exact as observed):
- core: passed=475 failed=0 skipped=0 errored=0 (11 suites, 0 aborted)
- hadoop: passed=52 failed=0 skipped=0 errored=0 (5 suites, 0 aborted)
- adversarialTests: passed=96 failed=4 skipped=0 errored=0 (100 run, 10 suites, 0 aborted, 0 canceled, 0 ignored, 0 pending)
  - The 77 attempt-2 tests: 77 passed.
  - The 23 new tests: 19 passed, 4 failed.
- Total: passed=623 failed=4 skipped=0 errored=0.
- No other project reported any test results in the log.
- `markTestRun` did not run, because `test` failed. `.claude/.last-test-run` is unchanged (still 10:16).

VALIDATION RESULTS:
- `scalafmtCheckAll` and `scalafmtSbtCheck`: passed. `Test/compile`: passed.
- `test`: failed (adversarialTests / Test / test, TestsFailedException), so `sbt check` fails.

DEFECTS FOUND: <test> | <failing input> | <expected> | <observed> | <owning agent>

1. RepairProbeSpec "an author name made only of Cf characters is MISSING_AUTHOR_NAME, like a blank one"
   - Failing input: `<author>&#8203;</author>` (ZWSP), placed between two real authors. The same happens with `&#173;` (soft hyphen), `&#8203;&#8204;&#8205;&#8288;&#65279;`, and `&#173; &#8203;`.
   - Expected: `DblpOutcome.Rejected("MISSING_AUTHOR_NAME")`, the same as a blank author.
   - Observed: the record is Accepted. Mention 2 has rawName = normalizedName = U+200B (or U+00AD, and so on).
   - Cause: `DblpNormalizer.build` checks blankness with `String.trim`, which does not remove Cf characters. When `Normalize.fold` returns "", the code falls back to `f.text.trim`. The result is a team member, author-paper row and coauthor pairs for an author with an invisible name.
   - Owner: hadoop-engineer (modules/hadoop/.../dblp/DblpNormalizer.scala).

2. RepairProbeSpec "DOI: decoded control characters are never part of a normalized DOI"
   - Failing input: `https://doi.org/10.1000/abc%00`, `%01def`, `%1B`, `%7F`, `%C2%85`. Also the bare forms `10.1000/ab\u0000c`, `10.1000/abc\u0001def`, `10.1000/ab\u007Fc`.
   - Expected: Left, or a value with no ISO control characters. C0 controls, DEL and C1 controls are not graphic characters, so they cannot be part of a DOI.
   - Observed: Right("10.1000/abc\u0000"), Right("10.1000/abc\u001B"), Right("10.1000/abc\u0085"), and so on. `DoiPattern` uses `\S+`, which excludes only the 6 ASCII whitespace characters.
   - Owner: domain-engineer (modules/core/.../ids/ExternalIds.scala, normalizeDoi).

3. RepairProbeSpec "DOI: normalizeDoi is idempotent when a URL decodes to a trailing control character"
   - Failing input: `https://doi.org/10.1000/abc%00` (also `%01`, `%08`, `%1F`).
   - Expected: normalizeDoi(v) == Right(v) for every accepted v.
   - Observed: `10.1000/abc\u0000` -> Right(`10.1000/abc`). The second call's `trim` removes the decoded trailing control, so the value changes.
   - Same root cause as defect 2. Owner: domain-engineer.

4. RepairProbeSpec "pipeline: a DOI URL with an encoded control character does not reach WorkRecord.doi"
   - Failing input: two otherwise valid DBLP articles, with `<ee>https://doi.org/10.1000/abc%00</ee>` and `<ee>https://doi.org/10.1000/abc%01</ee>`.
   - Expected: 2 works, with the DOI dropped or clean. A malformed escape such as `%zz` already behaves this way, and that test passes.
   - Observed: 0 works. j01 shows REJECTED=2 and SCHEMA_FAILURE=2. The reject reads `InvalidValue(doi,'10.1000/abc\u0000' is not normalized ('10.1000/abc'))`.
   - Impact: one bad ee link costs the whole publication.
   - Root cause is defects 2 and 3 in core. Owner: domain-engineer. Optional hardening by hadoop-engineer (dropping a DOI that fails the contract instead of the work) is a routing decision for the orchestrator.

COVERAGE BY REQUIREMENT (new tests in this attempt; the attempt-2 suites still cover all 9 Work order C categories and all pass):
- Truncation at split boundaries (TruncationSplitSpec, 9 tests, all pass):
  - Exact truncated values. A cut point exactly at, before and after every split boundary: all split sizes 1..len are checked.
  - Truncation inside a start tag, and truncation at EOF.
  - A chain of adjacent truncated records, which reuses the pending start across splits.
  - An escaped `&lt;article` in text is not a cut point. Non-configured tags (`proceedings`, `articles`, `www`) are not cut points. A raw `<article` inside an attribute value gives the same values at every split size and the next record survives.
  - A multi-block bzip2 file with 714 truncated records gives the same values at 5 split sizes.
  - Pipeline: one MALFORMED_XML reject per truncated record, with excerpts that hold only that record. 53-byte splits give the same work, author_mention and rejects_j01 tables as one split.
- Cf characters: edge, interior and supplementary-plane Cf, and Cf between a letter and its combining mark (pass). Cf-only names: FAIL (defect 1).
- NFC dedup: combining sequences with no precomposed form in both mark orders, Hangul jamo vs syllable, and OHM SIGN vs OMEGA are each one member. Different marks, or mark vs no mark, stay separate. All pass.
- Illegal bytes at split boundaries: in a UTF-8 file, 0xFF, a lone lead byte before `</title>`, and a truncated record ending in a partial sequence give the same values at every split size, a strict Malformed result that names the byte offset, and an intact neighbour. A pipeline run with 37-byte splits matches one split. All pass.
- Percent-encoded DOIs:
  - Pass: double encoding is decoded once; `%2F` and `%2E` in the prefix decode; bare and `doi:` forms are not decoded; accepted values are fixed points; malformed escapes, overlong sequences, encoded surrogates and truncated UTF-8 give Left and never throw; in the pipeline a malformed escape gives a missing DOI, and the double-encoded URL and the bare form give one exact-identifier outcome.
  - FAIL: control characters (defects 2, 3, 4).

ASSUMPTIONS:
- A name made only of invisible Cf characters counts as a blank author, so MISSING_AUTHOR_NAME applies.
- A normalized DOI must not contain C0, DEL or C1 control characters. Per the DOI Handbook, DOI names use graphic Unicode characters.
- A bare DOI with a trailing NUL that `trim` removes is acceptable. The test only flags results that still contain a control character.

RISKS:
- Each full `sbt check` takes about 15 minutes.
- The attempt-2 test-report.md in docs/agent-runs/phase-04 is now superseded by these counts.
- Whether hadoop-engineer should also stop dropping whole works when a DOI fails the contract (defect 4) is still open for the orchestrator to route.

MANUAL ACTION REQUIRED: none. The orchestrator should route defects 2-4 to domain-engineer and defect 1 to hadoop-engineer.
BLOCKED-ON OWNER: domain-engineer (defects 2, 3, 4), hadoop-engineer (defect 1)
BLOCKED-ON EVIDENCE: /tmp/claude-1000/-home-olmos-scholarchain/6b01634c-cc8a-448e-b030-ceacd9f03210/scratchpad/check3.log, lines 650-708 (4 FAILED tests, "Tests: succeeded 96, failed 4", EXIT=1).
NEXT GATE: owner repair, then test rerun
```

_Orchestrator note: repair-06 (domain-engineer, defects 2–4) and repair-07 (hadoop-engineer, defect 1) came between attempts 3 and 4._

## Work order C: test-engineer, attempt 4

```text
STATUS: FAILED
OWNER: test-engineer
WORK ORDER: Phase 04, Work order C, attempt 4 (fresh context). Run `sbt check` independently, then add a small set of adversarial probes for repair 06 (domain-engineer, `normalizeDoi` non-graphic rule) and repair 07 (hadoop-engineer, shared `Normalize.isBlank` rule). Tests go only under tests/**.

Summary: the 100 tests from attempts 2 and 3 still pass, so all earlier defects stay fixed. I added 14 probes in one new suite: 10 pass and 4 fail. The 4 failures expose 4 new defects, all owned by hadoop-engineer. None comes from repair 06, whose DOI rule held under every probe. `sbt check` exits 1 only because of these 4 tests, which I left failing on purpose.

FILES CREATED:
- /home/olmos/scholarchain/tests/src/test/scala/scholarchain/adversarial/InvisibleRepairProbeSpec.scala (14 tests; pure-ASCII source, every invisible code point built from its hex value)

FILES MODIFIED: none. The 11 earlier test files are unchanged. No production, build or constitutional file was touched, and nothing was committed. Scratch output went only to tests/target/adversarial-scratch and my scratchpad. target/phase04/* was not touched (probe, run-a, run-a2, run-a3, run-b, run-b2, run-b3 are all present).

COMMANDS RUN:
1. `sbt -batch "adversarialTests/Test/scalafmtOnly <spec>" "adversarialTests/testOnly ...InvisibleRepairProbeSpec"`, run 3 times.
   - Runs 1 and 2: scalafmt failed to parse my file because I had used `given` as a parameter name. I renamed it.
   - Run 3: 10 passed, 4 failed.
2. `sbt -batch check` (log: /tmp/claude-1000/-home-olmos-scholarchain/6b01634c-cc8a-448e-b030-ceacd9f03210/scratchpad/a4-check.log). EXIT=1, core 483/483, hadoop 62/62, adversarialTests 110 passed and 4 failed.
   - This run is superseded: after it I found that the Write tool had turned most `\uXXXX` escapes into literal invisible characters in the source, so I rewrote the file with a `u(0x..)` helper. The rewritten file is pure ASCII, which I checked with a python scan: 0 non-ASCII or control characters.
   - I tried to stop this run early with `kill`, but the permission system denied it, so I let it finish.
3. Same scalafmt and testOnly command on the rewritten file: 10 passed, 4 failed, the same 4 tests. Log: a4-probe4.log.
4. FINAL `sbt -batch check` on the final tree. Log: /tmp/claude-1000/-home-olmos-scholarchain/6b01634c-cc8a-448e-b030-ceacd9f03210/scratchpad/a4-check2.log. EXIT=1, total time 964 s.

TEST RESULTS (final `sbt check`, exact as observed): passed=655 failed=4 skipped=0 errored=0
- core: 483 run, 483 passed, 0 failed (12 suites, 0 aborted)
- hadoop: 62 run, 62 passed, 0 failed (6 suites, 0 aborted)
- adversarialTests: 114 run, 110 passed, 4 failed, 0 canceled, 0 ignored, 0 pending (11 suites, 0 aborted)
  - The 100 tests from attempts 2 and 3: 100 passed.
  - The 14 new tests: 10 passed, 4 failed.
- markTestRun did not run because `test` failed. `.claude/.last-test-run` is unchanged (12:29:11), so the Stop hook still reports the tests as stale.

VALIDATION RESULTS:
- `scalafmtCheckAll` and `scalafmtSbtCheck` passed. Compile and Test/compile passed.
- `test` failed: `[error] (adversarialTests / Test / test) sbt.TestsFailedException`.

DEFECTS FOUND: <test> | <failing input> | <expected> | <observed> | <owning agent>
1. InvisibleRepairProbeSpec "Cf-only spelling differences of one DBLP name on one paper are one team member"
   - Failing input: one article with authors `Wei Wang`, `Wei Wa&#173;ng`, `Other Person`. The same happens with `Wei Wang` plus `Wei Wang&#8203;`, and with `Wei Wang 0001` plus `&#65279;Wei Wang 0001`.
   - Expected: team size 2, removedDuplicates 1. Fold and the blocking key already treat these as one name.
   - Observed: team size 3, removed 0. `TeamExpansion.mentionKey` is "k:" + NFC(raw `sourceAuthorKey`), which keeps the U+00AD, U+200B or U+FEFF, so the paper gets a self-coauthor pair.
   - Owner: hadoop-engineer (modules/hadoop/.../pairs/TeamExpansion.scala, or the DBLP `sourceAuthorKey` built in DblpNormalizer).
2. InvisibleRepairProbeSpec "an invisible character next to a DBLP homonym suffix does not make the suffix the surname"
   - Failing input: `Normalize.surnameAndInitial` on `"Wei Wang 0001" + U+200B`, on `"Wei Wang 0001" + U+00AD`, and on `"Wei Wang" + U+00A0 + "0001"`.
   - Expected: `Some(("wang","w"))`.
   - Observed: `Some(("0001","w"))` for all three. `DblpHomonymSuffix` (`\s+[0-9]{4}$`) runs on `trim`-ed raw text, before Cf characters are removed, and `\s` does not match NBSP. The homonym number becomes the blocking surname (`r|0001|w`).
   - Owner: hadoop-engineer (modules/hadoop/.../text/Normalize.scala, `stripHomonymSuffix`).
3. InvisibleRepairProbeSpec "OpenAlex: a non-graphic doi drops only the DOI; a valid ids.doi is still used"
   - Failing input: an OpenAlex work whose `"doi"` holds one of these DOIs: `https://doi.org/10.1000/a%C2%A0b`, `...a%E2%80%A8b`, `10.1000/a`+U+00A0+`b`, `doi:10.1000/a`+U+2029+`b`, or `10.1000/a`+U+D800+`b`. Its `"ids":{"doi":"https://doi.org/10.1000/AB"}` is valid.
   - Expected: `WorkRecord.doi == Some("10.1000/ab")`.
   - Observed: `None`. `Json.str(w,"doi").orElse(ids.doi).flatMap(normalizeDoi)` picks the first present string and then normalizes it, so an invalid non-blank `doi` hides a valid `ids.doi`. Repair 06 exposed this: those values were accepted before it. Without `ids.doi`, the DOI is dropped and the work is kept, which is correct (that part passes).
   - Owner: hadoop-engineer (modules/hadoop/.../sources/OpenAlexNormalizer.scala).
4. InvisibleRepairProbeSpec "an invisible-only type or abstract is absent everywhere, as Json.str now treats it"
   - Failing input: an OpenAlex work with `"type"` and `"language"` set to an invisible-only value, and a Crossref item with `"type"`, `"publisher"` and `"abstract"` set to one. The values are NBSP, U+2028, U+2029, U+3000, U+0001, U+0085, or the mix NBSP ZWSP TAB NNBSP BOM.
   - Expected, per repair 07's own statement that any invisible-only JSON string field is now absent: no work_node property holds an invisible-only value, and an invisible-only Crossref abstract gives no `abstractTextRef`.
   - Observed, for every variant:
     - The OpenAlex work_node keeps `language` and `type`. The Crossref work_node keeps `publisher` and `type`. `Json.scalar` and the `Json.obj` paths bypass `isBlank`.
     - Crossref sets `abstractTextRef = crossref/adv.jsonl#10.1000/typ/abstract`, because `Json.nonEmpty` uses `s.nonEmpty`.
     - `WorkRecord.recordType` is correctly "unknown", so the work node and the record disagree.
   - Severity: low.
   - Owner: hadoop-engineer (modules/hadoop/.../sources/Emitted.scala `Json.scalar`/`Json.nonEmpty`, OpenAlexNormalizer, CrossrefNormalizer).

COVERAGE BY REQUIREMENT:
Probes of repairs 06 and 07 (new suite):
- Invisible-only names and titles in each source (all pass): NBSP, LS, PS, ideographic space, NNBSP, C0 SOH (JSON only), C1 NEL and mixed values were tested.
  - DBLP author gives MISSING_AUTHOR_NAME and DBLP title gives MISSING_TITLE.
  - OpenAlex: an invisible `raw_author_name` falls back to `author.display_name`, and an invisible title falls back to `display_name`. Both names invisible gives MISSING_AUTHOR_NAME.
  - Crossref: an invisible `given` next to a real family name gives rawName exactly "Lee" and normalizedName "lee", and the reverse gives "Ann". The mention node keeps no invisible property. An invisible title element is skipped.
  - ORCID: an invisible credit name gives a label from given + family names. Invisible given names are not kept. An invisible iD path is a MISSING_KEY reject.
- Combining-mark-only names (pass): `&#769;`, ZWSP+U+0301+SHY and two stacked marks are accepted, with normalizedName equal to the visible mark alone and no invisible characters. They get no researcher blocking key. In the pipeline this is counted as j05 MISSING_KEY=1, so it is not dropped silently.
- normalizeDoi properties (pass): 225 adversarial inputs, built from 3 base forms, 25 inserts and 3 positions. No input throws. Every accepted value is a fixed point, is graphic, and gives the same result in the `doi:`, `DOI:`, whitespace-padded, fully percent-encoded `https://doi.org/` and `HTTP://DX.DOI.ORG/` forms. No input holding NBSP, LS, PS, U+3000, NNBSP or a lone high or low surrogate is ever accepted.
- DOI fate through the pipeline:
  - DBLP (pass, full LocalJobRunner run): NBSP, LS and PS DOIs in the URL, `doi:` and bare forms are dropped and the works survive. A later valid ee wins (`10.1000/good1`). SCHEMA_FAILURE=0.
  - Crossref (pass): a non-graphic DOI key gives a visible MISSING_KEY reject and no work row.
  - ORCID (pass): the node survives, the raw value is kept, and no normalizedDoi property is derived.
  - OpenAlex with no `ids.doi` (pass): the DOI is dropped and the work is kept. OpenAlex with a valid `ids.doi`: FAIL (defect 3).
- Invisible-only non-name fields:
  - Pass: the OpenAlex work `id`, OpenAlex author `id` and Crossref `DOI` each give exactly one MISSING_KEY reject, never a silent drop.
  - FAIL: `type`, `language`, `publisher` and `abstract` (defect 4).
- Team de-duplication and homonym suffix next to invisible characters: FAIL (defects 1 and 2).

Phase 4 gate:
- At least two real MapReduce jobs: all 9 jobs (j01 through j09) run in the LocalJobRunner pipeline tests, and they pass.
- DBLP reader record and split boundaries: SplitBoundarySpec, TruncationSplitSpec and RepairProbeSpec split tests, all pass.
- Pair counts for 1, 2, 3 and larger teams, and large teams counted rather than dropped: TeamSpec, all pass. Defect 1 is a new de-duplication gap that inflates team size.
- Counters and reject outputs visible: pass in every suite. Defect 4 is a consistency gap, not a hidden reject.
- Repeated runs equivalent: the split-size and order-independence tests pass.
- Provenance on every output: covered by the earlier suites, which pass.
- Adversarial tests present: yes, 114 tests. `sbt check` passes: NO (exit 1, the 4 defects above).

ASSUMPTIONS:
- Defect 1: names that differ only by Cf characters are one name for in-paper de-duplication. This is the same assumption as my attempt-2 defects 7 and 8, which the owner accepted. NBSP versus space (NFKC) was left out on purpose, because the owner documented that choice.
- Defect 2: the homonym suffix must survive invisible characters. Real DBLP uses an ASCII space; these inputs come from character references.
- Defect 4 holds hadoop-engineer to its own repair-07 statement. Severity is low.
- Cf characters inside DOIs are accepted by design (repair 06). I did not report this as a defect.

RISKS:
- Repair 06 accepts a DOI with a leading or trailing Cf character (for example `10.1000/abc`+U+200B). It is a distinct DOI from `10.1000/abc`, so an exact-ID match can be missed when a copy-pasted DOI carries a ZWSP. I did not assert this, because it is a deliberate domain decision. It is flagged here for code-reviewer.
- Crossref drops a whole record (as a visible MISSING_KEY) when its DOI has a trailing NBSP. `trim` does not strip NBSP. This is consistent with repair 06.
- A full `sbt check` takes about 16 minutes. The adversarial suite is about 8 of them.

MANUAL ACTION REQUIRED: none from the human. The orchestrator routes defects 1 to 4 to hadoop-engineer.
BLOCKED-ON OWNER: hadoop-engineer (defects 1, 2, 3, 4)
BLOCKED-ON EVIDENCE: /tmp/claude-1000/-home-olmos-scholarchain/6b01634c-cc8a-448e-b030-ceacd9f03210/scratchpad/a4-check2.log, lines 548-565 (4 FAILED), line 747 ("*** 4 TESTS FAILED ***") and line 756 (exit after 964 s). The failure detail with code points is in a4-probe4.log and a4-probe3.log in the same directory.
NEXT GATE: owner repair, then test rerun
```

_Orchestrator note: repair-08 (hadoop-engineer, attempt-4 defects 1–4) came between attempts 4 and 5. Per human escalation 01, attempt 5 was scoped: no new exploratory tests, and any new failure is a known risk, not a repair request._

## Work order C: test-engineer, attempt 5 (scoped, final)

```text
STATUS: COMPLETE
OWNER: test-engineer
WORK ORDER: Phase 04, Work order C, attempt 5 (scoped and final, per the human decision after repair 08). Confirm that the 4 attempt-4 failures in InvisibleRepairProbeSpec are fixed, then rerun the full suite and `sbt check`. No new tests were written and no existing test was changed.
FILES CREATED: none
FILES MODIFIED: none. I wrote no test, production, build or constitutional file. Nothing under target/phase04 was touched. Logs went to the scratchpad only.
COMMANDS RUN:
1. `sbt -batch "adversarialTests/testOnly scholarchain.adversarial.InvisibleRepairProbeSpec"`: exit 0, 50 s. Log: /tmp/claude-1000/-home-olmos-scholarchain/6b01634c-cc8a-448e-b030-ceacd9f03210/scratchpad/a5-probe.log
2. `sbt -batch check` (no timeout, run in the background): exit 0, real 32m04.5s. Log: /tmp/claude-1000/-home-olmos-scholarchain/6b01634c-cc8a-448e-b030-ceacd9f03210/scratchpad/a5-check.log
   - I first tried a version of this command that also listed `.claude/.last-test-run` with `ls`. The guard hook blocked it ("shell write to the test marker"), so it never ran. I reran it without the `ls`. I did not touch the marker in any other way.
TEST RESULTS: passed=672 failed=0 skipped=0 errored=0 (exact, observed, per test pass)
- InvisibleRepairProbeSpec (scoped run): 14 run, 14 succeeded, 0 failed, 0 canceled, 0 ignored, 0 pending. The 4 tests that failed in attempt 4 now pass, with the exact `[info] -` lines observed:
  1. "Cf-only spelling differences of one DBLP name on one paper are one team member": PASS
  2. "an invisible character next to a DBLP homonym suffix does not make the suffix the surname": PASS
  3. "OpenAlex: a non-graphic doi drops only the DOI; a valid ids.doi is still used": PASS
  4. "an invisible-only type or abstract is absent everywhere, as Json.str now treats it": PASS
- Full `sbt check`. The log shows two complete test passes: the `test` step, then the test run that `markTestRun` depends on. Both gave identical counts.
  - core: 483 run, 483 succeeded, 0 failed, 0 canceled, 0 ignored, 0 pending (12 suites, 0 aborted)
  - hadoop: 75 run, 75 succeeded, 0 failed, 0 canceled, 0 ignored, 0 pending (7 suites, 0 aborted)
  - adversarialTests: 114 run, 114 succeeded, 0 failed, 0 canceled, 0 ignored, 0 pending (11 suites, 0 aborted)
  - These counts match the ones repair 08 reported (core 483, hadoop 75, adversarialTests 114/114).
VALIDATION RESULTS:
- The scalafmt checks passed, at log lines 6-9.
- Compile and Test/compile passed.
- The first test pass ended `[success] Total time: 968 s` (line 734). The second ended `[success] Total time: 946 s` (line 1459).
- Line 1458: `markTestRun: tests passed; touched /home/olmos/scholarchain/.claude/.last-test-run`.
- Final line: `exit=0`.
DEFECTS FOUND: none in this run. Attempt-4 defects 1 to 4 are confirmed fixed.
COVERAGE BY REQUIREMENT (Phase 4 gate, from the existing suites):
- At least two real MapReduce jobs: the LocalJobRunner pipeline tests run all of j01 to j09 (hadoop suites, plus the RepairProbeSpec and InvisibleRepairProbeSpec pipeline tests). Pass.
- DBLP reader record and split boundaries: SplitBoundarySpec, TruncationSplitSpec, the RepairProbeSpec split tests and MalformedXmlSpec. Pass.
- Pair counts for 1-, 2-, 3- and larger-author teams, and large teams counted rather than dropped: TeamSpec (including a giant team above maxPairExpansionTeamSize and reordered author lists) and InvisibleRepairProbeSpec team de-duplication. Pass.
- Counters and reject outputs visible: MissingFieldsSpec, EntitySpec, the MISSING_KEY probes and the pipeline counter assertions. Pass.
- Repeated runs equivalent: the split-size and order-independence tests. Pass.
- Provenance on every output: covered by the earlier suites and the core contract tests. Pass.
- Unicode and confusables, duplicate and conflicting DOIs, block overflow: UnicodeSpec, DoiSpec, InvisibleRepairProbeSpec and BlockOverflowSpec. Pass.
- Adversarial tests present, and `sbt check` passes: yes. 114 adversarial tests, and `sbt check` exited 0.
ASSUMPTIONS:
- `sbt check` runs the test task twice: once for `test`, and once more inside `markTestRun`. TEST RESULTS counts one pass (483+75+114=672). Both passes gave identical results.
- No existing test was judged wrong in this attempt.
RISKS (known risks, not repair requests):
- These were carried forward from attempt 4 and are not asserted by any test, by design:
  - Repair 06 accepts a DOI with a leading or trailing Cf character, such as `10.1000/abc`+U+200B. Such a DOI is distinct from `10.1000/abc`, so an exact-ID match can be missed. This is a domain decision, flagged for code-reviewer. Owner: domain-engineer/hadoop-engineer.
  - Crossref drops a whole record, as a visible MISSING_KEY reject, when its DOI has a trailing NBSP, because `trim` does not strip NBSP. This is consistent with repair 06. Owner: hadoop-engineer.
- The adversarial loop has ended by human decision, so edge cases not probed in attempts 1 to 5 remain untested.
- A full `sbt check` takes about 32 minutes, because of the double test pass. Each pass takes about 16 minutes.
- No new failures were observed.
MANUAL ACTION REQUIRED: none
BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: Work order D (code-reviewer) read-only review against the Phase 4 gate, then the human gate
```
