# Architecture and research notes

The CLI path is `Main` -> `JarInspector` -> ASM `ClassReader`. It reads the physical ZIP directory through `JarFile`, parses class headers, and returns immutable records. It does not establish class validity or runtime behavior. Versioned entries are reported separately, not interpreted as an effective multi-release view.

The Java API also exposes independent WALA hierarchy and SootUp Jimple/CFG summary adapters. Both reject versioned entries; WALA uses the running JDK libraries and reports unresolved types, while SootUp currently has no configured dependency or JDK input. They are not connected to the CLI or a shared snapshot and must not be used as proof of transformation safety.

## Proposed boundary, not yet implemented

Original class-file bytes are the authoritative representation for artifact edits. ASM owns reading and writing class-file structures. WALA and SootUp should consume an explicitly defined immutable snapshot and return engine-owned facts, never mutable IR objects used as the output representation. Associate facts with a snapshot identity, runtime view, dependencies, and analysis configuration; invalidate them on mutation. WALA is a candidate for independent hierarchy and SSA analysis; SootUp is a candidate for Jimple CFG and data flow. Its bytecode frontend itself uses ASM, so agreement is not wholly independent. Exact APIs and feature coverage remain to be tested.

Before renaming, define the closed-world assumptions, member and override closure, observable naming surfaces, reflection, serialization, native access, signatures, resources, and unknown attributes. Unknown inputs must lead to conservative preservation or explicit rejection. Preserve all physical multi-release variants and derive analysis views for each supported runtime. A modified signed JAR needs an explicit signature policy. ASM checks do not replace actual JVM verification or differential execution.

## References

- [JVMS 21: class-file format and verification](https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-4.html)
- [JVMS 21: loading, linking, and initialization](https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-5.html)
- [JAR specification](https://docs.oracle.com/en/java/javase/21/docs/specs/jar/jar.html)
- [ASM release history](https://asm.ow2.io/versions.html)
- [WALA](https://github.com/wala/WALA)
- [SootUp](https://github.com/soot-oss/SootUp)

OBA V2's installed workflow and indexed JVM, ASM, WALA, SootUp, and program-semantics references informed these constraints; historical or illustrative claims require independent verification. For example, hierarchy resolution and frame computation require experiments against the selected ASM version, rather than trusting inconsistent guide claims.
