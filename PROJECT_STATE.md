# Project state

Last updated: 2026-11-25. This is an early implementation, not a release. The current working tree includes in-progress, unpublished transformation-engine work. `MISSION.md` preserves the full V3 continuation requirements; `docs/research/v3-initial-audit.md` records the active audit.

## Implemented

- Java 21 Maven project with ASM 9.11.1, WALA 1.8.0, and SootUp 3.0.1 dependencies; ASM class-header inspection of physical JAR entries; results are sorted by entry name.
- CLI `inspect` (physical or explicit release-selected logical classpath view), `analyze-release`, and experimental `rename-private-static` commands. `analyze-release` feeds both libraries the same class-only selected view, only for the running JDK release; it rejects selected module descriptors and has no application dependencies. Renaming requires selected method identities, a mapping destination and an explicit closed-world/name-use acknowledgment; it refuses existing destinations.
- WALA hierarchy adapter for a single non-versioned JAR, running JDK libraries and three-valued assignability; SootUp adapter reports Jimple/CFG metrics for a single non-versioned JAR. Neither takes application dependencies; the analysis-aware workflow consumes their facts as conservative rename vetoes.
- Bounded physical JAR ingestion used by inspection and renaming, and deterministic ZIP32 output, plus signature/digest mutation guards. Archive entry payloads are preserved; ZIP metadata is normalized. Entry payload copies were reduced and output verification streams, but the whole pipeline working set and available heap remain unbounded by the payload limit.
- ASM class checks, explicit-byte hierarchy lookup, an opt-in private static method renamer, deterministic mapping serialization, and stack-frame translation. The pass rejects versioned inputs, custom attributes, incomplete nests and unresolved symbolic-owner matches. Mapping translation requires complete inventories and rejects known ambiguous names.
- Exact class and method keep rules are consumed by the renamer and CLI. Keep takes precedence over explicit selection, with applied/kept decisions and reasons. `AnalysisAwareRenamer` and the CLI derive conservative keeps from WALA hierarchy, SootUp owner/comparison facts and recognized reflection sinks for a fixed non-MR, no-dependency scope. Unknown lookup names keep all selected methods. Scope identity and reflection coverage remain incomplete.

## Validation actually performed

- JDK 21 `mvn -o -B clean verify` passed 94 tests after adding release-specific analysis and built the project JAR. The packaged `META-INF/LICENSE` and `META-INF/THIRD_PARTY_NOTICES` match the repository files byte for byte. This was a clean build in the current worktree, not a clean-checkout reproducibility test.

- JDK 21: `mvn -B -Dmaven.repo.local=/tmp/zartema-m2 package`: 10 tests passed and the JAR was built on 2026-11-25.
- An initial test compilation failed as expected because the inspector had not yet been implemented. A separate initial Maven attempt failed because the default local repository lock directory was not writable; a temporary repository was used. A subsequent sandbox DNS failure was resolved by running the authorized build with network access.
- CLI smoke test: JDK 21 with `-Xverify:all` inspected the ASM 9.11.1 JAR, reporting 41 class entries and one resource. This checks the inspector process, not transformed code.
- JDK 21 full-worktree `mvn -o -B -Dmaven.repo.local=/tmp/zartema-m2 test`: 54 tests passed after fixing the interrupted missing CLI publication method. Fixtures include deterministic archive emission across time zones, ASM hierarchy array and interface merge cases executed with `-Xverify:all`, direct-call and method-handle rename comparisons, and CLI process execution. These are bounded fixture results, not universal claims.
- Focused renamer and CLI run after keep-rule integration: 15 tests passed on JDK 21, including a mixed kept/renamed fixture and class/method CLI rules. A subsequent full offline Maven run passed 58 tests on JDK 21; compatibility and release gates remain incomplete.
- The private-method fixture compared original and transformed stdout and exit status on JDK 21 with `-Xverify:all`, and checked repeated output bytes. No broad compatibility matrix, real-world transformation corpus, independent full audit, performance measurement, clean-checkout reproducibility study, or release validation has been completed.

- Focused JDK 21 analysis/CLI rerun passed 17 tests, including a recognized reflective lookup that keeps a selected method and matching original/output executions. An earlier concurrent Maven run failed from a shared build-output collision; see the evidence record.
- The CLI runs the forked JVM class-load check on staged input and output before publication. It uses the running JDK with no application dependencies, rejects manifest `Class-Path` and versioned archives, and does not initialize classes. Java 21 `mvn -o -B -Dtest=JvmJarLoadVerifierTest,CliTest test` passed 18 tests, including actual `VerifyError`, missing-type refusal without publication, manifest class-path rejection, and verified execution fixtures. This does not prove complete linkage or behavioral equivalence.

- JDK 21 `mvn -o -B -Dtest=JarInspectorTest test`: 3 tests passed after routing inspection through the bounded archive reader, including rejection of an unsafe entry name. Total process memory remains unbounded by the payload limits.

- JDK 21 targeted runs passed 31 verifier/CLI/release-view tests and 13 inspector/release-view tests, including version-before-base order, JDK selection cross-checks and an executed Java 21 fixture. These runs do not validate multi-release transformation.

- JDK 21 `mvn -o -B -Dtest=AnalysisSnapshotCoordinatorTest,CliTest#analyzeReleaseCommandReportsActualLibraryFacts test` passed 5 tests, including both libraries observing a versioned class with the expected superclass. The path was subsequently included in a 94-test clean build; a focused CLI rerun passed after the diagnostic text change. This new work is not yet published or on CI.

## Outstanding

- Independent verifier review identified stage-file verification/publication races; bounded verifier inputs and child heap/metaspace, plus a distinct older-target failure, are implemented and tested. Input/output staged digests are now rechecked after JVM loading, and unsafe mapping rollback was removed; these edits need integrated tests and still do not eliminate all same-directory races. The CLI currently supports only self-contained application JARs and its loader check does not establish complete linkage.
- Extend the shared analysis snapshot and CLI with dependency inputs, complete identity binding, deeper analysis, cross-validation, and compatibility tests.
- Complete multi-release transformation and runtime selection, dependency-aware analysis, transformation lifecycle/contracts, richer keep-rule configuration and machine-readable diagnostic reports, robust trace translation, independent structural and runtime verification, resource limits and security review. The existing rename pass relies on user assertions rather than detecting all name-sensitive uses.
- Analysis coordinator inventory now uses the archive reader's decompressed-payload limits; a focused three-test run passed. Its staging disk usage, whole-analysis memory and runtime limits remain unbounded.
- The runtime dependency graph and upstream license metadata have been inventoried in `THIRD_PARTY_NOTICES`; the specialist inspected packaged runtime artifact notices and found the thin JAR embeds no dependency code. Complete notices and applicable source/relinking obligations for any future bundled distribution remain open. The thin JAR includes verified copies of the project license and inventory. Clean-checkout reproducibility, comprehensive compatibility testing, benchmarking, independent final audit, and CI inspection remain open.
- GitHub publication and CI results must be verified separately for each milestone.

## Publication

- Commit `afc5141` was pushed to the authorized `main` branch after the 92-test JDK 21 clean build and staged-content hygiene checks. GitHub Actions run 45737535724 completed successfully for commit `afc5141d9039e17947497ab2ae120250f0419afb` (observed with `gh run view`). An unrelated untracked archive is not part of the commit.

## Next steps

Investigate remaining stage/publication races and extend the release view into analysis and eventually multi-variant transformation with explicit scope, and rerun the full suite. Continue correcting V3 audit findings and strengthen the transformation consumer and analysis snapshot. Expand name-sensitive-use detection, runtime/differential fixtures, supported archive variants, and validation evidence before widening claims. Keep uncertainty explicit. See `docs/research/oba-usage-audit.md` for actual OBA usage and gaps.
