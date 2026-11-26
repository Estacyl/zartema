package org.zartema.workflow;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.zartema.analysis.AnalysisSnapshotCoordinator;
import org.zartema.archive.JarArchive;
import org.zartema.transform.PrivateMethodRenamer;
import org.zartema.transform.keep.KeepRuleSet;

/** Runs scoped analyses against the same immutable archive before applying the rename pass. */
public final class AnalysisAwareRenamer {
    private static final String POLICY = "non-multi-release; application-only; running-jdk; no-dependencies; wala-1.8.0; sootup-3.0.1; v1";
    private AnalysisAwareRenamer() { }

    /** Identity of the analyzed snapshot and its explicit, currently fixed scope. */
    public record SnapshotIdentity(String archiveSha256, String runtimeVersion, String runtimeVendor,
                                   String runtimeImageSha256, String policy) { }
    public record Result(PrivateMethodRenamer.Result transform, AnalysisSnapshotCoordinator.Report analysis,
                         SnapshotIdentity identity) { }

    /**
     * Recomputes facts for each invocation; callers cannot supply or reuse a stale analysis report.
     * WALA hierarchy uncertainty, SootUp missing owner facts and cross-library disagreement veto
     * affected selections. This is not proof of absence of reflection or external callers.
     * The running JDK and empty application-dependency list are part of the fixed current scope.
     */
    public static Result rename(JarArchive archive, List<PrivateMethodRenamer.MethodSelector> requested,
                                boolean closedWorldAndNameSensitiveUsesExcluded, KeepRuleSet userRules) throws IOException {
        Objects.requireNonNull(archive, "archive");
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(userRules, "userRules");
        if (!closedWorldAndNameSensitiveUsesExcluded)
            throw new IllegalArgumentException("Explicit closed-world and name-sensitive-use assumptions are required");
        Path runtimeImage = Path.of(System.getProperty("java.home"), "lib", "modules");
        if (!Files.isRegularFile(runtimeImage)) throw new IOException("Running JDK module image is not available for snapshot identity");
        String runtimeDigest = sha256(runtimeImage);
        Path directory = Files.createTempDirectory("zartema-preflight-");
        try {
            Path stagedJar = directory.resolve("input.jar");
            archive.writeDeterministic(stagedJar);
            AnalysisSnapshotCoordinator.Report analysis = AnalysisSnapshotCoordinator.analyze(stagedJar);
            if (!runtimeDigest.equals(sha256(runtimeImage)))
                throw new IOException("Running JDK image changed during analysis");
            var identity = new SnapshotIdentity(analysis.snapshotSha256(), System.getProperty("java.runtime.version"),
                    System.getProperty("java.vendor"), runtimeDigest, POLICY);
            if (!analysis.malformedClassEntries().isEmpty())
                throw new IOException("Analysis encountered malformed class entries: " + analysis.malformedClassEntries());
            var builder = KeepRuleSet.builder();
            for (var rule : userRules.rules()) {
                if (rule.scope() == KeepRuleSet.Scope.CLASS) builder.keepClass(rule.owner(), rule.reason());
                else builder.keepMethod(rule.owner(), rule.methodName(), rule.descriptor(), rule.reason());
            }
            Set<String> walaNames = analysis.wala().applicationClasses().stream()
                    .map(fact -> fact.internalName()).collect(java.util.stream.Collectors.toUnmodifiableSet());
            Set<String> sootNames = analysis.sootUp().classes().stream()
                    .map(fact -> fact.internalName()).collect(java.util.stream.Collectors.toUnmodifiableSet());
            Set<String> resolvedNames = Set.copyOf(analysis.wala().resolvedInternalNames());
            for (var selection : requested) {
                String owner = selection.owner();
                boolean unresolved = !walaNames.contains(owner) || analysis.ambiguousClassIdentities().contains(owner);
                for (JarArchive.Entry entry : archive.entries()) {
                    if (entry.name().equals(owner + ".class")) {
                        var reader = new ClassReader(entry.content());
                        if (reader.getSuperName() != null && !resolvedNames.contains(reader.getSuperName())) unresolved = true;
                        for (String parent : reader.getInterfaces()) if (!resolvedNames.contains(parent)) unresolved = true;
                    }
                }
                if (unresolved) builder.keepMethod(owner, selection.name(), selection.descriptor(), "WALA owner or direct hierarchy is unresolved or ambiguous");
                if (!sootNames.contains(owner)) builder.keepMethod(owner, selection.name(), selection.descriptor(), "SootUp owner is unavailable in the analyzed view");
                if (analysis.hierarchyDisagreements().stream().anyMatch(fact -> fact.internalName().equals(owner)))
                    builder.keepMethod(owner, selection.name(), selection.descriptor(), "WALA and SootUp direct hierarchy facts disagree");
                if (analysis.sootUp().reflectionSinks().stream().anyMatch(sink ->
                        sink.constantMemberName().isEmpty() || sink.constantMemberName().get().equals(selection.name())))
                    builder.keepMethod(owner, selection.name(), selection.descriptor(),
                            "SootUp found a reflective member lookup with a matching or unknown name");
            }
            var transformed = PrivateMethodRenamer.rename(archive, requested, true, builder.build());
            return new Result(transformed, analysis, identity);
        } finally {
            Files.deleteIfExists(directory.resolve("input.jar"));
            Files.deleteIfExists(directory);
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }
}
