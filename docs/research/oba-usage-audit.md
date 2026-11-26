# OBA usage audit — 2026-11-25

This is an audit of the tool output and specialist reports visible in this session, not a claim that every historical agent operation can be reconstructed. A catalog entry, an index listing, and a delegated agent are not evidence that their contents were read. The installed OBA guides include historical and illustrative material; normative claims require primary-source verification and/or experiments. Historical `~/.qoder` links and missing legacy originals are not reliable citations.

## Available skills

The canonical installed OBA directory contained 50 skill manifests at audit time. Discovery is not activation:

- `android-dex-obfuscation`
- `anti-debug-engineering`
- `anti-tamper-engineering`
- `asm-engineering`
- `assembly-engineering`
- `binary-validation`
- `c-engineering`
- `code-virtualization`
- `compiler-engineering`
- `constant-obfuscation`
- `control-flow-engineering`
- `control-flow-obfuscation`
- `cpp-engineering`
- `cryptographic-engineering`
- `data-flow-engineering`
- `data-obfuscation`
- `decompiler-analysis`
- `deobfuscation-resilience-engineering`
- `differential-testing`
- `dotnet-obfuscation`
- `engineering-pragmatism`
- `go-obfuscation`
- `ir-engineering`
- `java-obfuscation-specialist`
- `javascript-obfuscation`
- `jvm-anti-debug-engineering`
- `jvm-bytecode-expert`
- `jvm-runtime-engineering`
- `jvm-verifier-expert`
- `llvm-obfuscation`
- `native-anti-debug-engineering`
- `native-binary-engineering`
- `native-virtualization`
- `obfuscation-architect`
- `obfuscation-benchmark`
- `obfuscation-code-reviewer`
- `obfuscation-quality-assurance`
- `obfuscation-researcher`
- `obfuscation-strategist`
- `program-semantics`
- `python-obfuscation`
- `reverse-engineering-specialist`
- `runtime-protection`
- `rust-obfuscation`
- `smt-z3-engineering`
- `sootup-engineering`
- `string-obfuscation`
- `virtual-machine-engineering`
- `wala-engineering`
- `wasm-obfuscation`

The separately installed `oba-workflow` orchestrator is at `~/.agents/skills/oba-workflow/SKILL.md`. The `~/.agents/skills/` tree also contains OBA-oriented wrapper skills and reference indexes; these are separate filesystem paths from the canonical catalog. The above list records the actual filesystem discovery, not a claim that all skills were applied.

## Main-agent reads visible in tool results

The main agent directly read `~/.agents/skills/oba-workflow/SKILL.md` in the original phase and again for this audit. It also read the wrapper `SKILL.md` files for `engineering-pragmatism`, `program-semantics`, `jvm-bytecode-expert`, `jvm-verifier-expert`, `asm-engineering`, `sootup-engineering`, `wala-engineering`, `java-obfuscation-specialist`, and `binary-validation`; their respective indexes were read when shown in the command outputs. During this audit it additionally read the wrapper `SKILL.md` and indexes for `differential-testing`, `jvm-runtime-engineering`, and `obfuscation-architect`. The main agent requested the canonical OBA `SKILL.md` files for `java-obfuscation-specialist`, `asm-engineering`, and `jvm-verifier-expert`; the combined output was truncated, so only the visible portions can be asserted as consulted.

Technical documents directly consulted at least in part, under `~/.agents/skills/`, were:

- `program-semantics/SEMANTIC_PRESERVATION.md` (contract axes; first 80 lines).
- `asm-engineering/FRAME_COMPUTATION.md` (writer modes and hierarchy caveats; first 80 lines) and selected lines of `ASM_API_REFERENCE.md` concerning validators.
- `sootup-engineering/SOOTUP_ASM_INTEGRATION.md` and `wala-engineering/WALA_ANALYSIS_RECIPES.md` (first 80 lines of each, illustrative and version-sensitive).
- `java-obfuscation-specialist/JAVA_COMPATIBILITY.md` and `JAVA_OBFUSCATION_TECHNIQUES.md` (selected opening sections).
- `jvm-verifier-expert/VERIFIER_REFERENCE.md`, `differential-testing/DIFFERENTIAL_TESTING.md`, `jvm-runtime-engineering/RUNTIME_COMPATIBILITY.md`, and `obfuscation-architect/PIPELINE_DESIGN.md` (selected opening sections during this audit).

The indexes list additional material, but a listing is not a read. Some guides refer to unavailable historical sections or contain claims that must be checked against exact pinned APIs; the audit does not adopt those claims merely because they appear in a guide.

## Specialist reports and actual delegation

The original task authorized substantial specialist delegation. Three OBA specialist agent roles were used: `research-specialist` (library and hierarchy research/implementation), `jvm-specialist` (JVM/archive/CLI work), and `obfuscation-auditor` (read-only review). These are agent roles, not skill activation or proof of knowledge retrieval. No generic/default worker or explorer subagent was created.

The following are specialist self-reports, not independent transcripts of their tool calls:

- The JVM specialist reports reading `oba-workflow/SKILL.md`, `java-obfuscation-specialist/SKILL.md` and `INDEX.md`, `program-semantics/SKILL.md` and `INDEX.md`, `java-obfuscation-specialist/GUIDE.md` and `JAVA_COMPATIBILITY.md`, and `oba-workflow/references/legacy/docs/VALIDATION_FRAMEWORK.md`; for archive work it also reports `jvm-runtime-engineering/SKILL.md`, `binary-validation/SKILL.md`, and `oba-workflow/references/legacy/ADAPTIVE_AGENT_BEHAVIOR.md`. It reports discovery, not reading, of the other Java technique index entries.
- The research specialist reports reading `asm-engineering/SKILL.md`, `INDEX.md`, and `FRAME_COMPUTATION.md`, `program-semantics/SKILL.md` and `INDEX.md`, and `oba-workflow/SKILL.md`. It explicitly did not read the other ASM references or `GUIDE.md` for that task.
- The auditor reports reading `oba-workflow/SKILL.md`, `java-obfuscation-specialist/SKILL.md`, `INDEX.md`, `JAVA_COMPATIBILITY.md`, and selected `JAVA_OBFUSCATION_TECHNIQUES.md` sections; also `jvm-verifier-expert/SKILL.md`, `INDEX.md`, and selected `VERIFIER_REFERENCE.md` and `STACK_MAP_FRAMES.md` sections. It reports no complete knowledge-base or legacy standards read and no WALA/SootUp guide read for that audit.

## Decisions and gaps

OBA guidance directly informed the use of ASM as the emission authority and SootUp/WALA as bounded analysis views, distinct structural versus JVM/runtime gates, original-versus-transformed testing, rename consistency and collision checks, custom-attribute refusal, preservation of mapping information, and a conservative treatment of reflection, native code, signed archives, and unsupported multi-release transformations. These are design constraints, not proof of implementation completeness. Independent JVMS research and actual runtime tests also informed the decisions; no single guide is treated as a correctness oracle.

Usage before this audit was uneven: indexes and short wrappers were sometimes read without enough of the underlying technical references before substantial code was written. In particular, the current rename pass relies on a caller assertion rather than implementing the name-sink detection and keep-policy described in the OBA Java/runtime references, and it lacks the full differential and compatibility matrix described by OBA. These are outstanding engineering requirements. During this audit the main agent explicitly reactivated `oba-workflow` and read relevant renaming, verifier, runtime, differential, and pipeline references. Specialists were instructed to consult applicable OBA documentation before further assigned work. Reading references does not itself validate the engine; unresolved defects and untested behavior remain recorded separately in `PROJECT_STATE.md`.
