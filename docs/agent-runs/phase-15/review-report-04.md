STATUS: COMPLETE
OWNER: code-reviewer
WORK ORDER: Read-only review of the uncommitted fix for INC-20261125-debug-credential-logging (HEAD 87e1e48)
VERDICT: APPROVE
FILES CREATED: none
FILES MODIFIED: none
COMMANDS RUN: none (read-only tools only)

Paths below are under /home/olmos/scholarchain. S = modules/services/src.

FINDINGS:
No critical or major findings. The three minor ones do not need to hold the redeploy, since exposure is still ongoing.

| # | Severity | File:line | Concrete failure input | Expected impact | Owning agent |
|---|---|---|---|---|---|
| 1 | minor | S/main/resources/logback.xml:9; S/test/scala/scholarchain/services/LoggingCanarySpec.scala:187; docker/launch.sh:5-6 | The deployment sets `JAVA_TOOL_OPTIONS=-Dlogback.configurationFile=<other file or URL>`. launch.sh documents that variable as the route for extra flags and does not pin the property. | The whole production file is replaced, so every WARN pin is lost. The comment "no environment variable or property may lower a level" and the test name "no setting can lower a level" overclaim: the test only checks that the file has no `${`. The incident's action 2 asks for no environment override of the AWS and HTTP loggers. No current Terraform environment key sets this (I searched infra/terraform), so it takes a deliberate change. Either narrow the claim or pin the property on the java command line, which takes precedence over JAVA_TOOL_OPTIONS. | service-engineer (wording); build-engineer (launch.sh) |
| 2 | minor | LoggingCanarySpec.scala:191-195 (filter at 194); also 60 | A dependency or upstream module jar ships a root `logback-test.xml` with root DEBUG. Its URL is `jar:file:...!/logback-test.xml`, which has no `/classes/`, so the filter drops it. | In production Logback prefers that file over logback.xml, and all 8 tests still pass: every child JVM forces `-Dlogback.configurationFile`, so automatic discovery is never exercised. A stronger check excludes only this module's own test-classes entry, or runs one child without the property and without test-classes on the classpath. Not a live defect in the jars I checked. | service-engineer |
| 3 | minor | LoggingCanaryProbe.scala:70-81; LoggingCanarySpec.scala:130-145 | The Netty path stops being observable, or never logs headers or bodies even at DEBUG. The control's assertions can all be satisfied by the synchronous Apache call alone (wire, headers, request logger). | The "no leak" result for the async transport has no control showing that this capture could see a leak on it. I found no async client in the services, rag or storage main sources, so production impact today is nil. | service-engineer / test-engineer |

AUTHOR CLAIMS AND REQUESTED CHECKS:
- Apache HttpClient 5: confirmed. The staged lib has apache5-client and httpclient5 5.6.4, and the control asserts that the `org.apache.hc.client5.http.wire` and `.headers` loggers appear. I saw no HttpClient 4 SDK client jar in a targeted glob.
- Levels: root INFO; all the required loggers, plus org.apache.hc, io.undertow and org.xnio, are at WARN. Explicit child levels are not lowered by a root change.
- Can the test fail: yes.
  - Deleting the production file fails `production` (it requires exactly one logback.xml).
  - Removing the org.apache.hc pins leaks under the root-DEBUG test, and the level assertions fail.
  - A child that fails, hangs or does not make both signed calls is caught by the timeout, exit code and `Success` marker. The fake server checks the signature and token headers.
  - Empty logging output would pass the canary test alone, but the shape test (access and stage lines, exactly once each) and the DEBUG control would fail.
  - I did not run any mutation; this is from reading the code.
- Hermeticity: confirmed by reading. The environment is cleared (so no JAVA_TOOL_OPTIONS, proxy or real credential variables), credentials are static fakes, user.home and the AWS config files point at nothing, IMDS is disabled, region is explicit, and the endpoint is loopback only.
- Format equals TTLLLayout default: the test compares encoded bytes against BasicConfigurator, and it passed in the report.
- collect.py: unaffected. `STAGE_LINE`, `BATCH_LINE` and `REJECT_LINE` (experiments/bin/collect.py:61-63) match message substrings. Those lines are INFO or WARN, so they survive root INFO.
- Packaging: the services jar staged for each of the three services contains a logback entry. In the query-api copy the only config entry name is `logback.xml`, with no logback-test or scmo. I also checked the query-api core, storage, rag and hadoop-client-runtime jars and found no competing config. I did not check every third-party jar, did not compare the packaged XML bytes with the source, and did not check a built image.
- No build change needed: confirmed for packaging (standard main resources). build.sbt and Dependencies.scala are unchanged as far as the work order and git status show, so there is no ledger split issue.
- Tests: modules/services/target/test-reports/TEST-scholarchain.services.LoggingCanarySpec.xml shows tests=8, failures=0, errors=0, skipped=0. I did not verify the repository totals (2391 passed, 24 ignored).
- Application log paths: I found no log statement in services that writes headers, bodies, tokens or secret values.
  - AccessRecord carries none of them.
  - The secret load failure message is fixed text (SecretsManagerApiKeySource.scala:22), and the parse failure does not echo content (Auth.scala:82).
  - ApiService.scala:111-112 does not log causes.
  - The reject sink logs store, key and code only.
- Ownership: all three files are in modules/services/**, which belongs to service-engineer.

TEST RESULTS: not run by reviewer
VALIDATION RESULTS: findings verified against files: 1 yes; 2 yes (by reading the filter; no jar was constructed); 3 yes (by reading the assertions; Netty's logging behavior was not reproduced)

ASSUMPTIONS:
- Logback's file precedence and level inheritance behave as documented for 1.5.38.
- The human-gate.md change is out of scope and was not reviewed.

RISKS:
- Cask console output bypasses logback. CaskServer (S/main/scala/scholarchain/services/server/CaskServer.scala:12-15) does not override `debugMode`, and HttpEndpoint uses `cask.defaultHandler`. From library knowledge, not verified in the jar: the default is true, so an exception outside `api.handle` (for example an IOException from `readNBytes` at line 28) would print a stack trace to the console and could return it to the caller. This predates the diff, and I see no way for it to carry headers or secrets. Worth a separate service-engineer follow-up.
- The fix has no effect until all three services are rebuilt and redeployed. The Neptune audit log keeps capturing signed headers regardless (infra-engineer, human).
- Still open from the incident's "Preventive test or control" list: an explicit jar packaging check in `sbt check` (build-engineer), the post-deploy count gate and alarm (infra-engineer), and, if wanted, a canary under tests/** (test-engineer).
- Material already logged, rotation and session revocation are untouched by this change and remain human actions.

MANUAL ACTION REQUIRED:
- The human commits the three untracked files before any image build, so the SHA-tagged images contain them.
- After redeploy, repeat the incident's count queries on the new log streams (expect 0) before running the embedding worker or experiment C again.

BLOCKED-ON OWNER: none
BLOCKED-ON EVIDENCE: none
NEXT GATE: orchestrator archives this verdict; human commits; owners may repair findings 1-3 as follow-ups (then re-review); `/deploy` approval for the three services.
