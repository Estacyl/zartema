# ADR 0001: Separate artifact ownership from analysis

Status: proposed; only the inspection slice is implemented.

Use class-file bytes as the artifact authority and ASM for class-file operations. Treat WALA and SootUp as independently scoped consumers that return engine-owned findings. This avoids treating a lossy high-level IR as a class-file round-trip format. Alternatives considered: emitting from a high-level IR would simplify some transformations but risks losing metadata and bytecode distinctions; using ASM alone would omit the requested program-analysis capabilities. Analysis snapshots and explicit invalidation are required before facts can guide edits. Runtime views, unresolved references, library API behavior, and conversion limitations remain open.

Apache-2.0 is provisionally selected for project-owned code because it is a recognized permissive license with an express patent grant. ASM 9.11.1 is BSD-3-Clause. WALA 1.8.0 identifies EPL-2.0 and SootUp 3.0.1 identifies LGPL-2.1 in their upstream materials; they are declared dependencies, pending operational integration and distribution review. The complete dependency tree and distribution obligations remain to be assessed before a release or bundled distribution; no legal compatibility conclusion is claimed.
