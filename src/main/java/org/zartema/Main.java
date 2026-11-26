package org.zartema;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import org.zartema.archive.JarArchive;
import org.zartema.mapping.NameMapping;
import org.zartema.transform.PrivateMethodRenamer;
import org.zartema.transform.keep.KeepRuleSet;
import org.zartema.workflow.AnalysisAwareRenamer;
import org.zartema.verify.JvmJarLoadVerifier;

/** Command-line entry point. */
public final class Main {
    private static final String INSPECT_USAGE = "Usage: zartema inspect <input.jar> [--release <feature-version>]";
    private static final String RENAME_USAGE = "Usage: zartema rename-private-static <input.jar> <output.jar> "
            + "--mapping <mapping-file> --method <owner#name(descriptor)return> "
            + "[--method <owner#name(descriptor)return> ...] [--keep-class <owner> ...] "
            + "[--keep-method <owner#name(descriptor)return> ...] "
            + "--acknowledge-complete-direct-callers-and-no-name-sensitive-uses";

    private Main() { }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        if (args.length > 0 && args[0].equals("inspect") && (args.length == 2
                || args.length == 4 && args[2].equals("--release"))) {
            try {
                if (args.length == 4) {
                    var report = JarInspector.inspectEffective(Path.of(args[1]), Integer.parseInt(args[3]));
                    report.classes().forEach(c -> System.out.printf("%s <- %s  %s  major=%d%n",
                            c.logicalName(), c.physicalName(), c.internalName(), c.majorVersion()));
                    System.out.printf("Release: %d; multi-release: %s; classes: %d; resources: %d%n",
                            report.targetRelease(), report.multiRelease(), report.classes().size(), report.resourceCount());
                    return 0;
                }
                var report = JarInspector.inspect(Path.of(args[1]));
                report.classes().forEach(c -> System.out.printf("%s  %s  major=%d%n", c.entryName(), c.internalName(), c.majorVersion()));
                System.out.printf("Classes: %d; resources: %d%n", report.classes().size(), report.resourceCount());
                return 0;
            } catch (IOException | IllegalArgumentException exception) {
                System.err.println("Inspection failed: " + exception.getMessage());
                return 1;
            }
        }
        if (args.length > 0 && args[0].equals("rename-private-static")) {
            try {
                return rename(args);
            } catch (IllegalArgumentException exception) {
                System.err.println("Invalid rename request: " + exception.getMessage());
                return 2;
            } catch (IOException exception) {
                System.err.println("Rename failed: " + exception.getMessage());
                return 1;
            }
        }
        System.err.println(args.length > 0 && args[0].equals("inspect") ? INSPECT_USAGE : RENAME_USAGE);
        return 2;
    }

    private static int rename(String[] args) throws IOException {
        if (args.length < 3) throw new IllegalArgumentException(RENAME_USAGE);
        Path input = Path.of(args[1]);
        Path output = Path.of(args[2]);
        Path mappingPath = null;
        boolean acknowledged = false;
        var selectors = new ArrayList<PrivateMethodRenamer.MethodSelector>();
        var keepRules = KeepRuleSet.builder();
        int index = 3;
        while (index < args.length) {
            switch (args[index]) {
                case "--mapping" -> {
                    if (mappingPath != null || index + 1 >= args.length) throw new IllegalArgumentException("--mapping must appear once with a path");
                    mappingPath = Path.of(args[index + 1]);
                    index += 2;
                }
                case "--method" -> {
                    if (index + 1 >= args.length) throw new IllegalArgumentException("--method requires a selector");
                    selectors.add(parseSelector(args[index + 1]));
                    index += 2;
                }
                case "--keep-class" -> {
                    if (index + 1 >= args.length) throw new IllegalArgumentException("--keep-class requires an internal class name");
                    keepRules.keepClass(args[index + 1], "operator keep-class rule");
                    index += 2;
                }
                case "--keep-method" -> {
                    if (index + 1 >= args.length) throw new IllegalArgumentException("--keep-method requires a selector");
                    var method = parseSelector(args[index + 1]);
                    keepRules.keepMethod(method.owner(), method.name(), method.descriptor(), "operator keep-method rule");
                    index += 2;
                }
                case "--acknowledge-complete-direct-callers-and-no-name-sensitive-uses" -> {
                    if (acknowledged) throw new IllegalArgumentException("Duplicate name-sensitive-use acknowledgment");
                    acknowledged = true;
                    index++;
                }
                default -> throw new IllegalArgumentException("Unknown rename option: " + args[index]);
            }
        }
        if (mappingPath == null) throw new IllegalArgumentException("--mapping is required");
        if (selectors.isEmpty()) throw new IllegalArgumentException("At least one --method selector is required");
        if (!acknowledged) throw new IllegalArgumentException(
                "Explicitly pass --acknowledge-complete-direct-callers-and-no-name-sensitive-uses only after confirming all direct bytecode callers are included and internal/external reflection, JNI/native code, agents/instrumentation, configuration, and other name-sensitive uses do not depend on selected names");

        Path inputIdentity = input.toAbsolutePath().normalize().toRealPath();
        Path outputPath = resolveDestination(output);
        Path mappingOutput = resolveDestination(mappingPath);
        Path outputIdentity = collisionIdentity(outputPath);
        Path mappingIdentity = collisionIdentity(mappingOutput);
        if (inputIdentity.equals(outputIdentity)) throw new IOException("Output resolves to the input artifact");
        if (inputIdentity.equals(mappingIdentity)) throw new IOException("Mapping path resolves to the input artifact");
        if (outputIdentity.equals(mappingIdentity)) throw new IOException("Output and mapping paths resolve to the same file");
        if (Files.exists(outputPath, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Refusing to overwrite existing output: " + outputPath);
        if (Files.exists(mappingOutput, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Refusing to overwrite existing mapping: " + mappingOutput);

        JarArchive archive = JarArchive.read(inputIdentity);
        var analyzed = AnalysisAwareRenamer.rename(archive, selectors, true, keepRules.build());
        PrivateMethodRenamer.Result result = analyzed.transform();
        Path outputStage = null;
        Path mappingStage = null;
        Path inputStage = null;
        boolean mappingPublished = false;
        try {
            outputStage = Files.createTempFile(outputPath.getParent(), ".zartema-output-", ".jar");
            mappingStage = Files.createTempFile(mappingOutput.getParent(), ".zartema-mapping-", ".tmp");
            inputStage = Files.createTempFile(outputPath.getParent(), ".zartema-input-", ".jar");
            archive.writeDeterministic(inputStage);
            result.archive().writeDeterministic(outputStage);
            byte[] inputDigest = sha256(inputStage);
            byte[] outputDigest = sha256(outputStage);
            var verifier = new JvmJarLoadVerifier();
            var options = JvmJarLoadVerifier.Options.forCurrentRuntime();
            requireJvmLoad(verifier.verify(inputStage, options), "Input");
            var outputVerification = verifier.verify(outputStage, options);
            requireJvmLoad(outputVerification, "Output");
            requireUnchanged(inputStage, inputDigest);
            try (BufferedWriter writer = Files.newBufferedWriter(mappingStage, StandardCharsets.UTF_8)) {
                result.mapping().write(writer);
            }
            requireUnchanged(outputStage, outputDigest);
            publishNewFile(mappingStage, mappingOutput);
            mappingPublished = true;
            publishNewFile(outputStage, outputPath);
            long renamed = result.decisions().stream().filter(PrivateMethodRenamer.Decision::renamed).count();
            long kept = result.decisions().size() - renamed;
            System.out.printf("Renamed %d private static method(s).%nKept %d selected method(s).%n", renamed, kept);
            System.out.printf("Analyzed archive SHA-256: %s%nAnalysis scope: %s%n",
                    analyzed.identity().archiveSha256(), analyzed.identity().policy());
            System.out.printf("JVM load check: %d classes on Java %s%n", outputVerification.classesLoaded(), outputVerification.runtimeVersion());
            result.decisions().stream().filter(decision -> !decision.renamed()).forEach(decision ->
                    System.out.printf("Kept %s#%s%s: %s%n", decision.method().owner(), decision.method().name(),
                            decision.method().descriptor(), String.join(", ", decision.reasons())));
            System.out.printf("Output: %s%nMapping: %s%n", outputPath, mappingOutput);
            return 0;
        } catch (IOException exception) {
            if (mappingPublished) {
                throw new IOException("Output publication failed after mapping publication; mapping may remain at "
                        + mappingOutput + ": " + exception.getMessage(), exception);
            }
            throw exception;
        } finally {
            if (outputStage != null) Files.deleteIfExists(outputStage);
            if (mappingStage != null) Files.deleteIfExists(mappingStage);
            if (inputStage != null) Files.deleteIfExists(inputStage);
        }
    }

    private static void requireJvmLoad(JvmJarLoadVerifier.Result result, String artifact) throws IOException {
        if (!result.passed()) throw new IOException(artifact + " JVM load check failed (" + result.failureKind()
                + "): " + result.className() + ": " + result.message());
    }

    static byte[] sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    static void requireUnchanged(Path path, byte[] expected) throws IOException {
        if (!MessageDigest.isEqual(expected, sha256(path)))
            throw new IOException("Staged artifact changed during JVM verification: " + path);
    }

    private static PrivateMethodRenamer.MethodSelector parseSelector(String value) {
        int separator = value.indexOf('#');
        int descriptorStart = value.indexOf('(', separator + 1);
        if (separator <= 0 || descriptorStart <= separator + 1 || descriptorStart == value.length() - 1) {
            throw new IllegalArgumentException("Method selector must be <internal-owner>#<name><descriptor>: " + value);
        }
        return new PrivateMethodRenamer.MethodSelector(value.substring(0, separator),
                value.substring(separator + 1, descriptorStart), value.substring(descriptorStart));
    }

    private static Path resolveDestination(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null || !Files.isDirectory(parent)) throw new IOException("Destination parent does not exist: " + parent);
        return parent.toRealPath().resolve(absolute.getFileName());
    }

    private static Path collisionIdentity(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return path.toRealPath();
        return path.getParent().toRealPath().resolve(path.getFileName());
    }

    /** Publish an already staged file without replacing a destination created concurrently. */
    static void publishNewFile(Path source, Path destination) throws IOException {
        try {
            Files.createLink(destination, source);
        } catch (UnsupportedOperationException exception) {
            throw new IOException("Destination filesystem does not support safe publication by hard link: " + destination, exception);
        }
    }
}
