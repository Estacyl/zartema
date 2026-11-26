# Architecture and research notes

The CLI path is `Main` -> `JarInspector` -> ASM `ClassReader`. It reads bounded physical entries through `JarArchive`, parses class headers, and returns immutable records. It does not establish class validity or runtime behavior. By default versioned entries are reported separately. `inspect --release` instead uses `MultiReleaseJarView` to select logical classpath entries and report their physical sources; it is not a conformance or linkage validator and does not enable multi-release transformation.

`analyze-release` selects the effective logical classes, writes a class-only analysis snapshot and runs both adapters on that same snapshot. It records physical normalized archive, selected snapshot, and running JDK module-image digests plus JDK vendor and version; it requires the target release to equal the running JDK, excludes resources and rejects selected module descriptors. The Java API exposes WALA hierarchy and SootUp Jimple/CFG adapters. The rename CLI uses `AnalysisAwareRenamer`, which invokes `AnalysisSnapshotCoordinator` over a staged non-versioned JAR and consumes unresolved WALA hierarchy, missing or conflicting SootUp class facts, and recognized SootUp reflective lookup evidence as conservative keep decisions. WALA uses running JDK libraries; SootUp has no configured dependency or JDK input. The workflow records the staged archive and JDK module image digests plus a fixed scope policy; its identity does not yet bind all configuration or actual library artifacts. Both adapters reject versioned entries. SootUp uses an ASM frontend; agreement is not proof of transformation safety.

The experimental rename CLI reads a `JarArchive`, applies exact keep rules and a private-static method pass through ASM, checks classes with `AsmClassValidator`, then stages the archive and mapping. It reports applied and kept methods. The pass rejects several unsupported domains and requires a caller assertion about external and name-sensitive uses. Before publishing, the CLI runs a forked `-Xverify:all` class-loading check on staged input and output with the running JVM and no application dependencies. The check does not initialize classes, exercise module layers or prove all future linkage and behavior. Differential execution remains fixture-level evidence. Archive emission normalizes ZIP metadata, retains physical payloads and refuses signed/digest-bearing modifications; it does not implement target-release MRJAR analysis.

## Intended boundary, not yet complete

Original class-file bytes are the authoritative representation for artifact edits. ASM owns reading and writing class-file structures. WALA and SootUp should consume an explicitly defined immutable snapshot and return engine-owned facts, never mutable IR objects used as the output representation. Associate facts with a snapshot identity, runtime view, dependencies, and analysis configuration; invalidate them on mutation. WALA is a candidate for independent hierarchy and SSA analysis; SootUp is a candidate for Jimple CFG and data flow. Its bytecode frontend itself uses ASM, so agreement is not wholly independent. Exact APIs and feature coverage remain to be tested.

Before renaming, define the closed-world assumptions, member and override closure, observable naming surfaces, reflection, serialization, native access, signatures, resources, and unknown attributes. Unknown inputs must lead to conservative preservation or explicit rejection. Preserve all physical multi-release variants and derive analysis views for each supported runtime. A modified signed JAR needs an explicit signature policy. ASM checks do not replace actual JVM verification or differential execution.

## References

- [JVMS 21: class-file format and verification](https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-4.html)
- [JVMS 21: loading, linking, and initialization](https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-5.html)
- [JAR specification](https://docs.oracle.com/en/java/javase/21/docs/specs/jar/jar.html)
- [ASM release history](https://asm.ow2.io/versions.html)
- [WALA](https://github.com/wala/WALA)
- [SootUp](https://github.com/soot-oss/SootUp)

OBA V2 and V3 installed workflows and indexed JVM, ASM, WALA, SootUp, and program-semantics references informed these constraints; historical or illustrative claims require independent verification. For example, hierarchy resolution and frame computation require experiments against the selected ASM version, rather than trusting inconsistent guide claims.
