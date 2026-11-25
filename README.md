# Zartema

Zartema is an early-stage Java 21 JVM engineering project maintained by **estacyl**. Its current implementation inspects physical class entries in a JAR with ASM and reports their internal names and class-file major versions. The Java API also exposes experimental WALA hierarchy facts and SootUp Jimple/control-flow summaries. It does not yet transform artifacts.

## Build and use

Requirements: JDK 21 and Maven 3.9 or newer. Run `mvn test` to compile and test. After `mvn package`, run the inspector with ASM on the classpath:

```sh
java -cp target/zartema-0.1.0-SNAPSHOT.jar:$HOME/.m2/repository/org/ow2/asm/asm/9.11.1/asm-9.11.1.jar org.zartema.Main inspect input.jar
```

Use `;` instead of `:` as the classpath separator on Windows. The packaged JAR does not bundle dependencies.

## Current limitations

Inspection reads every physical `.class` entry, including versioned entries, rather than choosing an effective multi-release view. It parses class headers; it does not verify bytecode, JAR signatures, multi-release conformance, dependencies, or behavior. It is not suitable for untrusted or arbitrarily large archives: there are no resource limits. The WALA and SootUp adapters reject versioned archives, do not accept an application dependency path, and are not yet connected to the CLI or a shared analysis snapshot. Their results do not establish semantic correctness. See [project state](PROJECT_STATE.md) and [architecture](docs/architecture.md).

Licensed under Apache-2.0; see [LICENSE](LICENSE). ASM is a separate BSD-3-Clause dependency. A complete dependency and distribution license review remains outstanding before release.
