# Project state

Last updated: 2026-11-25. This is an early implementation, not a release.

## Implemented

- Java 21 Maven project with ASM 9.11.1, WALA 1.8.0, and SootUp 3.0.1 dependencies; ASM class-header inspection of physical JAR entries; results are sorted by entry name.
- CLI `inspect` command; it does not write output artifacts.
- WALA hierarchy adapter for a single non-versioned JAR, running JDK libraries and three-valued assignability; SootUp adapter reports Jimple/CFG metrics for a single non-versioned JAR. Neither takes application dependencies or drives transformations.
- Ten JUnit tests across inspection and the two adapters.

## Validation actually performed

- JDK 21: `mvn -B -Dmaven.repo.local=/tmp/zartema-m2 package`: 10 tests passed and the JAR was built on 2026-11-25.
- An initial test compilation failed as expected because the inspector had not yet been implemented. A separate initial Maven attempt failed because the default local repository lock directory was not writable; a temporary repository was used. A subsequent sandbox DNS failure was resolved by running the authorized build with network access.
- CLI smoke test: JDK 21 with `-Xverify:all` inspected the ASM 9.11.1 JAR, reporting 41 class entries and one resource. This checks the inspector process, not transformed code.
- No clean-checkout, runtime compatibility, differential, performance, reproducibility, broad real-world corpus, or transformation validation has been performed.

## Outstanding

- Integrate WALA and SootUp into a shared, runtime-specific snapshot and CLI; add dependency inputs, deeper analysis, cross-validation, and compatibility tests.
- Artifact rewriting, multi-release selection and compatibility, hierarchy resolution, transformation contracts, renaming and keep rules, mapping, stack-trace translation, structural and runtime verification, reporting, resource limits and security review.
- Full license and transitive dependency review, reproducible build verification, comprehensive test suite, benchmarking, independent audit, and CI result inspection.
- GitHub publication and CI results must be verified separately for each milestone.

## Next steps

Establish bounded JAR ingestion and validation, probe pinned WALA and SootUp APIs against the same explicitly selected runtime view, and record empirical results before using their findings for transformations. Build rename contracts and a differential fixture corpus before implementing any rename. Keep all uncertainty explicit.
