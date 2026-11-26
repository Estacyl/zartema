# Zartema

Zartema is an early-stage Java 21 JVM bytecode engineering project maintained by **estacyl**. It includes read-only JAR inspection, experimental WALA hierarchy and SootUp Jimple/control-flow analysis APIs, and an opt-in private static method renamer. It is not a stable release; see [project state](PROJECT_STATE.md) for validation and outstanding work.

## Build and use

Requirements: JDK 21 and Maven 3.9 or newer. Build and test with `mvn verify`. The project JAR does not bundle its dependencies. For example, on a Unix-like shell:

```sh
mvn -B package dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile=target/classpath.txt
java -cp "target/zartema-0.1.0-SNAPSHOT.jar:$(cat target/classpath.txt)" org.zartema.Main inspect input.jar
```

Use `;` instead of `:` as the classpath separator on Windows. Add `--release 21` to `inspect` to select a logical classpath view for a Java feature release and report the physical source of each selected class. This selection does not establish multi-release JAR conformance or runtime compatibility.

The experimental renamer requires explicit method identities, a mapping destination, and an acknowledgment of its closed-world assumptions:

```sh
java -cp "target/zartema-0.1.0-SNAPSHOT.jar:$(cat target/classpath.txt)" org.zartema.Main rename-private-static input.jar output.jar \
  --mapping output.map --method 'example/App#helper(I)I' \
  --acknowledge-complete-direct-callers-and-no-name-sensitive-uses
```

Repeat `--method` for additional methods. Optional `--keep-class <internal-owner>` and `--keep-method <owner#name(descriptor)return>` rules take precedence over selected methods; the CLI reports kept methods and reasons. The acknowledgment asserts that **all direct bytecode callers are in the input JAR** and that no internal or external reflection, method-name lookup, JNI/native code, agent, instrumentation, configuration, or other name-sensitive consumer relies on the selected names. Zartema cannot prove these conditions. A false assertion can produce an application that fails at runtime even if bytecode checks pass. Existing destination files are refused. Publication requires filesystem hard-link support; unsupported filesystems fail. The JAR and mapping are staged separately; an output-publication failure or crash after mapping publication can leave a mapping without its JAR.

The pass accepts private static non-native methods and rejects unsupported custom attributes, unresolved symbolic method owners, incomplete declared nests, signed or digest-bearing inputs, and versioned archives. It uses ASM structural and basic data-flow checks, then loads input and output classes on the running JVM in a separate `-Xverify:all` process before publication. This non-initializing classpath check rejects JAR manifest `Class-Path` entries and uses no application dependencies in the CLI. It does not exercise module layers, initialization, all later symbolic linkage, or application behavior; callers must execute representative workloads on their target JVM and compare behavior. Its keep rules currently support exact class and method identities only; it does not yet provide a general keep-rule language, complete reflection analysis, multi-release transformation, or universal equivalence guarantees. The CLI runs WALA and SootUp over the same staged, dependency-free non-versioned archive: unresolved hierarchy facts and recognized reflective lookups with matching or unknown names conservatively keep selected methods. This inventory does not prove that other name-sensitive uses are absent. The Java mapping API can translate individual stack frames if its producer supplies complete method inventories; it rejects known ambiguous names.

## Scope and limitations

By default the inspector reads physical `.class` entries, including versioned entries, and reports internal names and class-file versions. Its optional release view selects logical classpath entries and records their physical sources; explicitly addressed physical version paths are outside that view. It does not verify JAR signatures or multi-release conformance. Inspection and renaming use the archive API, which enforces decompressed payload and entry-count limits. These limits do not bound total JVM memory or the analysis libraries. The archive API preserves physical entry payloads and produces deterministic ZIP32 output while normalizing ZIP metadata. The WALA and SootUp adapters reject versioned archives; the CLI recomputes their facts on a staged snapshot with the running JDK and no application dependencies. It records a normalized archive digest and JDK image digest, but the snapshot identity and dependency support remain incomplete. See [architecture](docs/architecture.md) and the [renaming contract](docs/adr/0002-renaming-safety.md).

Licensed under Apache-2.0; see [LICENSE](LICENSE). A complete transitive dependency and distribution license review remains outstanding before release.
