# Production verification — 2.2.3

The local engineering evidence below was collected before publication authorization. The published `v2.2.2` tag and assets remain immutable; the current release coordinates and Marketplace catalog advance to `2.2.3`. Publication requires successful hosted CI and the tag-controlled release gates. This report is not an unconditional certification of every production ETL.

## Closed engineering gaps

- Definition reads have an independent byte budget, including malformed XML and failed readers. Cached definitions are not charged for reads that did not occur. External-change detection, immutable snapshots, single-flight refresh and generation invalidation remain intact.
- Filesystem traversal failures preserve partial results and signal an incomplete scan rather than claiming complete pagination.
- Hop Web's deadline includes bounded body reception and cancels stalled requests without allocating a blocking reader worker.
- Real full-Hop timeout acceptance uncovered an output-schema mismatch: the native execution wrapper returns `error_count = -1` when the error count is unavailable at timeout. The shared synchronous/asynchronous execution schema now accepts that existing sentinel, rejects values below it, and does not fabricate a zero count. No new tool or authorization was added.
- A distribution-only differential gate compares optimized extraction against complete native `PipelineMeta` deserialization. It checks exact reference records (type, name, component, source), variables, truncation at 200 references, and missing-plugin fallback. All seven required plugins must be present: TableInput, TableOutput, DBLookup, DBJoin, InsertUpdate, Delete and DBProc. Sixteen cases passed on full Hop 2.19.0; this is not exhaustive coverage of third-party plugins or every metadata type.
- A disposable-project MCP acceptance gate covers semantic preview/apply and native reload, five generated rows verified using persisted native metrics, pipeline success/failure, workflow execution, logs/history/diagnosis, active asynchronous cancellation, synchronous/asynchronous timeout, stale SHA-256 rejection, protected backups, exact-byte rollback, path confinement and protocol-only STDIO/clean EOF.
- Linux CI now fails if the two symlink confinement tests are missing, skipped or unsuccessful. The gate was also checked against Windows reports and correctly rejected their skipped cases.
- The official conformance runner returned success despite two unlisted raw failures. A second, independently tested raw-report guard now rejects unlisted failures, missing/unexecuted core scenarios, stale baselines and wire-schema violations. The missing `json_schema_2020_12_tool` is explicitly declared as an everything-server fixture gap, not supplied as a fake product capability. The other failure was fixed, not baselined: idle GET event-stream headers in the test-only HTTP adapter were not flushed until an event appeared. A bounded diagnostic timed out after 5 seconds; the original runner's resumed GET eventually failed after about 300 seconds on Linux/Node 22. The adapter now flushes only asynchronous GET `text/event-stream` responses after native handling. The corrected runner received resumed-GET headers in about 1 ms and finished normally. Production transport, authorization and native handlers are unchanged.

## Verification and evidence

Local evidence is retained outside `target/`, under `outputs/production-readiness/`; Maven clean does not erase it. Intermediate failed acceptance reports were preserved as well as the final results. The early harness failures involved incorrect native property names and incomplete test run-configuration metadata; those were corrected in the harness. The timeout schema failure required the product correction described above.

| Check | Result |
| --- | --- |
| Java 21 / Hop 2.19.0 `clean verify` | 124 tests, zero failures/errors; two Windows symlink-privilege skips |
| Java 21 / Hop 2.20.0-SNAPSHOT `clean verify` | Same counts; compatibility build, not a stable-runtime production certification |
| Linux Java 21 / Hop 2.19.0 `clean verify` | 124 tests, zero failures/errors/skips; actual symlink confinement tests passed |
| Full Hop 2.19 native reference matrix | 16 cases / 7 required plugins passed on Windows and Linux |
| Full Hop 2.19 operational MCP acceptance | 20 recorded checks passed on Windows and Linux, including real active cancellation and both timeout result shapes |
| Installed default-mode smoke | 24 default tools; privileged tools absent; validation and clean EOF passed on Windows and Linux |
| Marketplace packaging | ZIP integrity, expected layout/version, unchanged LICENSE/NOTICE, no bundled Hop runtime jars |
| Jandex | Index read successfully: 107 classes, including HopMcpCommand with the native HopCommand annotation |
| CycloneDX | JSON/XML generated and inspected for both profiles; expected Hop dependency versions present |
| Report-guard regressions | Seven standard-library tests passed; actual Windows skipped reports and original unlisted conformance failures were rejected |
| Official MCP 2025-11-25 runner / Linux Node 22 | 33 scenarios: 42 successful checks, 25 explicitly expected fixture failures, three warnings; strict raw-result guard passed with no unexpected failures or stale baseline |

The Linux build runs in an isolated, offline Maven/Java 21 container with read-only source and dependency-cache mounts. CycloneDX intentionally skips its goal in offline mode: Linux evidence does **not** claim a newly generated Linux SBOM. The Windows online verification generated and inspected both SBOM formats. The Hop 2.20 build emitted upstream snapshot-repository metadata warnings during SBOM generation; expected dependencies are present, but complete upstream snapshot provenance is not certified.

Final reviewed Windows packages, SBOMs and Surefire reports are under `outputs/production-readiness/review-final/build-hop219` and `build-hop220`. Windows native-reference and operational evidence is under `outputs/production-readiness/final/`. Linux operational reports and native-reference evidence are under `outputs/production-readiness/linux-final/`, with the final reviewed-tree rerun under `linux-review-final/`. Original conformance failures are preserved under `conformance-linux/`; corrected raw reports are under `conformance-linux-fixed-v2/`. The three remaining conformance warnings concern priming/retry/resume in the test-only adapter, not certification of a production HTTP transport. This is an application-profile regression pass, **not** full everything-server conformance.

## Performance evidence already measured

The retained full-Hop benchmark predates the final timeout-schema-only correction. It is not a new isolated benchmark of that correction. Java 21 / full Hop 2.19.0, same SHA-256-verified 5,000-definition fixture, five fresh JVMs per version, alternating order, five hot queries per process:

| Phase | Published 2.2.2 median | Development median | Interpretation |
| --- | ---: | ---: | --- |
| Hop startup | 6,443.4 ms | 6,291.6 ms | No strong startup improvement claimed; baseline outlier |
| First impact query | 4,236.7 ms | 3,754.0 ms | 11.4% lower on this fixture |
| Hot impact query | 125.5 ms | 19.5 ms | 84.5% lower on this fixture |

Cold ranges were 4,226.0–4,320.1 ms / 3,696.1–3,790.8 ms; hot ranges were 114.1–139.9 ms / 17.2–38.1 ms. Raw data, ranges and median absolute deviations remain in `outputs/full-hop-improvements/results.json` and `report.md`. All queries returned exactly 64 nodes and 63 edges with identical canonical hashes and no truncation. Cold reads consumed 1,284,756 bytes; hot refreshes read zero bytes with 5,000 cache hits and a fresh filesystem scan. No TTL, lazy extraction or additional result cache was introduced.

Cold profiling identified full native `PipelineMeta` construction as substantial work; absent Maven-fixture plugins were not treated as the sole cause. The candidate already contained direct native transform extraction and hot-index work reduction before this closing pass. This comparison includes those changes and does not isolate the performance effect of the byte-budget, scan-failure, HTTP-deadline or schema fixes. The performance fixture has no typed metadata references; the separate differential gate closes that specific evidence gap for its seven-plugin scope, not by changing the timing fixture.

## Reproduce acceptance

Use an isolated, verified full Hop 2.19.0 installation with the verified connector ZIP installed, Java 21, compiled `target/classes` and `target/test-classes`, and a disposable Hop configuration folder initialized from the distribution. Run Java acceptance helpers from the Hop installation directory so native plugin discovery uses that distribution. Include its `lib/core/*` and platform SWT JAR directory in the classpath.

```powershell
rtk proxy java -cp '<classes>;<test-classes>;<hop>/lib/core/*;<hop>/lib/swt/win64/*' io.github.michaaels.hop.mcp.HopNativeReferenceAcceptance '<new-reference-fixtures>'
rtk proxy java -cp '<classes>;<test-classes>;<hop>/lib/core/*;<hop>/lib/swt/win64/*' io.github.michaaels.hop.mcp.HopOperationalFixture '<new-project>'
rtk proxy node scripts/ci/hop-operational-acceptance.mjs '<hop>' '<new-project>' '<config>' '<new-report.json>'
rtk proxy python scripts/ci/check-required-tests.py '<linux-surefire-reports>'
```

The Java helpers refuse an existing fixture/project directory. The operational client requires the bootstrap's disposable-fixture marker and refuses to overwrite existing definition fixtures. Native bootstrap creates only local run configurations and file-backed execution metadata, without database connections. Definition authoring and subsequent operations use MCP semantic tools, not XML replacement. These helpers/clients are development-only and are not bundled into the Marketplace runtime.

## Remaining production conditions

1. Review the release tree and execute hosted CI on the reviewed commit before tagging. Updated workflow configuration and local equivalent checks are not evidence of a successful GitHub Actions run.
2. Accept representative business pipelines/workflows on the intended host, with their exact plugins, drivers, parameters, realistic volumes, expected rows/checksums, restart/recovery behavior and concurrent workload. Synthetic/local fixtures cannot certify unspecified ETLs or every database. No remote database was contacted in this closing pass.
3. Provision native project metadata/run configurations, OS account isolation, least-privilege credentials, backups, retained execution information and operational monitoring before rollout. The test-only bootstrap is not a new product metadata-write tool. Current MCP capabilities do not mean every Hop GUI capability or every NiFi operational feature is implemented.
4. Keep STDIO under a controlled supervisor/client, with only required flags enabled. Connector deadlines and cancellation do not guarantee that every third-party JDBC driver or plugin promptly honors interruption; exercise relevant failures on the actual stack.
5. Use the stable release only after the review/CI and deployment-specific acceptance gates. Verify published checksums and provenance; never substitute a development snapshot for an immutable release artifact.

Engineering recommendation: controlled staging/pilot with the reviewed package, followed by production only after these deployment-specific gates. No speculative cache, scheduling layer, new execution engine or broader permissions were added to close these verification gaps.
