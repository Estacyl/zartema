package org.zartema.verify;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.LinkOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Runs the target JVM's verifier over every ordinary class in a JAR using an isolated loader.
 * Requires a Java 21+ target executable because the fork helper is compiled for Java 21.
 * The child process does not initialize application classes or invoke their entrypoints.
 * Inputs are bounded, regular JAR files snapshotted into a private temporary directory;
 * directory dependencies and multi-release application JARs are unsupported.
 */
public final class JvmJarLoadVerifier {
    private static final String HELPER_CLASS = "org.zartema.verify.ForkedJarVerifier";
    private static final int MIN_OUTPUT_LIMIT = 1024;
    private static final long DEFAULT_MAXIMUM_ARCHIVE_BYTES = 256L * 1024 * 1024;
    private static final long DEFAULT_MAXIMUM_TOTAL_INPUT_BYTES = 512L * 1024 * 1024;
    private static final long DEFAULT_MAXIMUM_TOTAL_CLASS_BYTES = 256L * 1024 * 1024;
    private static final long DEFAULT_MAXIMUM_TOTAL_ARCHIVE_ENTRIES = 200_000;
    private static final long DEFAULT_CHILD_HEAP_BYTES = 512L * 1024 * 1024;
    private static final long DEFAULT_CHILD_METASPACE_BYTES = 256L * 1024 * 1024;
    private static final long MIN_CHILD_HEAP_BYTES = 32L * 1024 * 1024;
    private static final long MIN_CHILD_METASPACE_BYTES = 16L * 1024 * 1024;
    private static final int MAXIMUM_DEPENDENCIES = 256;

    public enum FailureKind {
        NONE,
        INVALID_INPUT,
        UNSUPPORTED_MULTI_RELEASE_JAR,
        UNSUPPORTED_MANIFEST_CLASSPATH,
        UNSUPPORTED_DIRECTORY_DEPENDENCY,
        CLASS_COUNT_LIMIT,
        INPUT_SIZE_LIMIT,
        CLASS_BYTES_LIMIT,
        INPUT_CHANGED,
        INPUT_CLEANUP_FAILED,
        DEPENDENCY_COUNT_LIMIT,
        ARCHIVE_ENTRY_LIMIT,
        START_FAILED,
        TARGET_RUNTIME_TOO_OLD,
        TIMEOUT,
        OUTPUT_LIMIT,
        JVM_VERIFY_ERROR,
        JVM_CLASS_FORMAT_ERROR,
        JVM_LINKAGE_ERROR,
        CHILD_REJECTED_INPUT,
        CHILD_PROCESS_FAILED,
        CHILD_PROTOCOL_ERROR
    }

    /** Options for one isolated target-JVM run. */
    public record Options(Path javaExecutable, List<Path> dependencies, Duration timeout,
                          int maximumOutputBytes, int maximumClasses, long maximumArchiveBytes,
                          long maximumTotalInputBytes, long maximumTotalClassBytes,
                          long maximumChildHeapBytes, long maximumChildMetaspaceBytes,
                          long maximumTotalArchiveEntries) {
        public Options(Path javaExecutable, List<Path> dependencies, Duration timeout,
                       int maximumOutputBytes, int maximumClasses, long maximumArchiveBytes,
                       long maximumTotalInputBytes, long maximumTotalClassBytes,
                       long maximumChildHeapBytes, long maximumChildMetaspaceBytes) {
            this(javaExecutable, dependencies, timeout, maximumOutputBytes, maximumClasses,
                    maximumArchiveBytes, maximumTotalInputBytes, maximumTotalClassBytes,
                    maximumChildHeapBytes, maximumChildMetaspaceBytes, DEFAULT_MAXIMUM_TOTAL_ARCHIVE_ENTRIES);
        }
        /** Preserves the original source constructor while applying conservative default resource ceilings. */
        public Options(Path javaExecutable, List<Path> dependencies, Duration timeout,
                       int maximumOutputBytes, int maximumClasses) {
            this(javaExecutable, dependencies, timeout, maximumOutputBytes, maximumClasses,
                    DEFAULT_MAXIMUM_ARCHIVE_BYTES, DEFAULT_MAXIMUM_TOTAL_INPUT_BYTES,
                    DEFAULT_MAXIMUM_TOTAL_CLASS_BYTES, DEFAULT_CHILD_HEAP_BYTES, DEFAULT_CHILD_METASPACE_BYTES,
                    DEFAULT_MAXIMUM_TOTAL_ARCHIVE_ENTRIES);
        }

        public Options {
            Objects.requireNonNull(javaExecutable, "javaExecutable");
            dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            if (maximumOutputBytes < MIN_OUTPUT_LIMIT) {
                throw new IllegalArgumentException("maximumOutputBytes must be at least " + MIN_OUTPUT_LIMIT);
            }
            if (maximumClasses < 1) {
                throw new IllegalArgumentException("maximumClasses must be positive");
            }
            if (maximumArchiveBytes < 1 || maximumTotalInputBytes < maximumArchiveBytes) {
                throw new IllegalArgumentException("input byte limits must be positive and total input must cover one archive");
            }
            if (maximumTotalClassBytes < 1) {
                throw new IllegalArgumentException("maximumTotalClassBytes must be positive");
            }
            if (maximumTotalArchiveEntries < 1) throw new IllegalArgumentException("maximumTotalArchiveEntries must be positive");
            if (maximumChildHeapBytes < MIN_CHILD_HEAP_BYTES) {
                throw new IllegalArgumentException("maximumChildHeapBytes must be at least " + MIN_CHILD_HEAP_BYTES);
            }
            if (maximumChildMetaspaceBytes < MIN_CHILD_METASPACE_BYTES) {
                throw new IllegalArgumentException("maximumChildMetaspaceBytes must be at least " + MIN_CHILD_METASPACE_BYTES);
            }
        }

        public static Options forCurrentRuntime() {
            Path bin = Path.of(System.getProperty("java.home"), "bin");
            String executable = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
            return new Options(bin.resolve(executable), List.of(), Duration.ofSeconds(30), 64 * 1024, 100_000);
        }
    }

    /** Immutable result. Failure fields identify the first rejected class where available. */
    public record Result(boolean passed, FailureKind failureKind, String className,
                         String throwableType, String message, int classesLoaded,
                         String runtimeVersion, String output, boolean outputTruncated,
                         Duration elapsed) { }

    public Result verify(Path jar, Options options) {
        Objects.requireNonNull(jar, "jar");
        Objects.requireNonNull(options, "options");
        long startNanos = System.nanoTime();
        Path stagingDirectory = null;
        Result result;
        try {
            Path java = options.javaExecutable().toAbsolutePath().normalize();
            if (!Files.isRegularFile(java) || !Files.isExecutable(java)) {
                throw new InvalidInputException("Target Java executable is not executable: " + options.javaExecutable());
            }
            if (options.dependencies().size() > MAXIMUM_DEPENDENCIES) {
                throw new DependencyCountLimitException("At most " + MAXIMUM_DEPENDENCIES
                        + " explicit dependency JARs are supported");
            }
            stagingDirectory = createPrivateStagingDirectory();
            InputBudget budget = new InputBudget(options.maximumArchiveBytes(), options.maximumTotalInputBytes());
            Path stagedJar = stageInput(jar, "application.jar", "application", stagingDirectory, budget,
                    options.maximumArchiveBytes());
            List<Path> stagedDependencies = new ArrayList<>();
            for (Path dependency : options.dependencies()) {
                stagedDependencies.add(stageInput(dependency, "dependency-" + stagedDependencies.size() + ".jar",
                        "dependency", stagingDirectory, budget, options.maximumArchiveBytes()));
            }
            ArchiveMetrics applicationMetrics = inspectArchive(stagedJar, options.maximumClasses(), true,
                    options.maximumTotalClassBytes(), 0, options.maximumTotalArchiveEntries());
            long totalClassBytes = applicationMetrics.classBytes();
            long totalEntries = applicationMetrics.entryCount();
            for (Path dependency : stagedDependencies) {
                ArchiveMetrics metrics = inspectArchive(dependency, options.maximumClasses(), false,
                        options.maximumTotalClassBytes(), totalClassBytes,
                        options.maximumTotalArchiveEntries() - totalEntries);
                totalClassBytes = Math.addExact(totalClassBytes, metrics.classBytes());
                totalEntries = Math.addExact(totalEntries, metrics.entryCount());
            }
            result = runChild(stagedJar, stagedDependencies, applicationMetrics.classCount(), options, startNanos);
        } catch (UnsupportedMultiReleaseException exception) {
            result = failure(FailureKind.UNSUPPORTED_MULTI_RELEASE_JAR, null, null, exception.getMessage(),
                    0, "", "", false, startNanos);
        } catch (UnsupportedManifestClasspathException exception) {
            result = failure(FailureKind.UNSUPPORTED_MANIFEST_CLASSPATH, null, null, exception.getMessage(),
                    0, "", "", false, startNanos);
        } catch (UnsupportedDirectoryException exception) {
            result = failure(FailureKind.UNSUPPORTED_DIRECTORY_DEPENDENCY, null, null, exception.getMessage(),
                    0, "", "", false, startNanos);
        } catch (DependencyCountLimitException exception) {
            result = failure(FailureKind.DEPENDENCY_COUNT_LIMIT, null, null, exception.getMessage(),
                    0, "", "", false, startNanos);
        } catch (ArchiveEntryLimitException exception) {
            result = failure(FailureKind.ARCHIVE_ENTRY_LIMIT, null, null, exception.getMessage(),
                    0, "", "", false, startNanos);
        } catch (ClassCountLimitException exception) {
            result = failure(FailureKind.CLASS_COUNT_LIMIT, null, null, exception.getMessage(),
                    0, "", "", false, startNanos);
        } catch (ClassBytesLimitException exception) {
            result = failure(FailureKind.CLASS_BYTES_LIMIT, null, null, exception.getMessage(),
                    0, "", "", false, startNanos);
        } catch (InputSizeLimitException exception) {
            result = failure(FailureKind.INPUT_SIZE_LIMIT, null, null, exception.getMessage(),
                    0, "", "", false, startNanos);
        } catch (InputChangedException exception) {
            result = failure(FailureKind.INPUT_CHANGED, null, null, exception.getMessage(),
                    0, "", "", false, startNanos);
        } catch (InvalidInputException exception) {
            result = failure(FailureKind.INVALID_INPUT, null, null, exception.getMessage(),
                    0, "", "", false, startNanos);
        } catch (IOException | RuntimeException exception) {
            result = failure(FailureKind.INVALID_INPUT, null, exception.getClass().getName(), exception.getMessage(),
                    0, "", "", false, startNanos);
        }
        if (stagingDirectory != null) {
            try {
                deleteStagingDirectory(stagingDirectory);
            } catch (IOException exception) {
                return failure(FailureKind.INPUT_CLEANUP_FAILED, null, exception.getClass().getName(),
                        "Unable to remove verifier input snapshot: " + exception.getMessage(),
                        result.classesLoaded(), result.runtimeVersion(), result.output(), result.outputTruncated(), startNanos);
            }
        }
        return result;
    }

    private Result runChild(Path normalizedJar, List<Path> normalizedDependencies, int classCount,
                            Options options, long startNanos) {

        String helperLocation;
        try {
            helperLocation = Path.of(JvmJarLoadVerifier.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toAbsolutePath().normalize().toString();
        } catch (Exception exception) {
            return failure(FailureKind.START_FAILED, null, exception.getClass().getName(),
                    "Cannot locate verifier helper classes: " + exception.getMessage(),
                    0, "", "", false, startNanos);
        }

        List<String> command = new ArrayList<>();
        command.add(options.javaExecutable().toAbsolutePath().normalize().toString());
        command.add("-Xverify:all");
        command.add("-Xmx" + options.maximumChildHeapBytes());
        command.add("-XX:MaxMetaspaceSize=" + options.maximumChildMetaspaceBytes());
        command.add("-cp");
        command.add(helperLocation);
        command.add(HELPER_CLASS);
        command.add(normalizedJar.toString());
        command.addAll(normalizedDependencies.stream().map(Path::toString).toList());

        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            // A caller's CLASSPATH must not affect the child system loader.
            builder.environment().remove("CLASSPATH");
            builder.environment().remove("JAVA_TOOL_OPTIONS");
            builder.environment().remove("_JAVA_OPTIONS");
            builder.environment().remove("JDK_JAVA_OPTIONS");
            process = builder.start();
        } catch (IOException | RuntimeException exception) {
            return failure(FailureKind.START_FAILED, null, exception.getClass().getName(),
                    exception.getMessage(), 0, "", "", false, startNanos);
        }

        CompletableFuture<CapturedOutput> capture = CompletableFuture.supplyAsync(
                () -> capture(process.getInputStream(), options.maximumOutputBytes()));
        boolean finished;
        try {
            finished = process.waitFor(options.timeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return failure(FailureKind.CHILD_PROCESS_FAILED, null, exception.getClass().getName(),
                    "Interrupted while waiting for target JVM", 0, "", "", false, startNanos);
        }
        if (!finished) {
            process.destroyForcibly();
            try {
                process.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            CapturedOutput output = completedCapture(capture);
            return failure(FailureKind.TIMEOUT, null, null,
                    "Target JVM exceeded timeout " + options.timeout(), 0, "", output.text(),
                    output.truncated(), startNanos);
        }
        CapturedOutput output = completedCapture(capture);
        if (output.truncated()) {
            return failure(FailureKind.OUTPUT_LIMIT, null, null,
                    "Target JVM output exceeded " + options.maximumOutputBytes() + " bytes",
                    0, "", output.text(), true, startNanos);
        }
        return parseResult(process.exitValue(), output.text(), classCount, options, startNanos);
    }

    private static ArchiveMetrics inspectArchive(Path jar, int maximumClasses, boolean application,
                                                  long maximumClassBytes, long alreadyReadClassBytes,
                                                  long remainingEntryBudget)
            throws IOException, UnsupportedMultiReleaseException, UnsupportedManifestClasspathException,
            ClassCountLimitException, ClassBytesLimitException, ArchiveEntryLimitException {
        final int maximumManifestBytes = 1024 * 1024;
        try (JarFile jarFile = new JarFile(jar.toFile(), false)) {
            var manifestEntry = jarFile.getJarEntry(JarFile.MANIFEST_NAME);
            java.util.jar.Manifest manifest = null;
            if (manifestEntry != null) {
                byte[] bytes;
                try (InputStream input = jarFile.getInputStream(manifestEntry)) {
                    bytes = readBoundedEntry(input, manifestEntry, maximumManifestBytes,
                            "manifest in " + jar);
                }
                manifest = new java.util.jar.Manifest(new java.io.ByteArrayInputStream(bytes));
            }
            String classPath = manifest == null ? null
                    : manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
            if (classPath != null) {
                throw new UnsupportedManifestClasspathException("JAR manifest Class-Path is unsupported in "
                        + (application ? "application " : "dependency ") + jar + ": " + classPath);
            }
            if (application && manifest != null && "true".equalsIgnoreCase(manifest.getMainAttributes()
                    .getValue(Attributes.Name.MULTI_RELEASE))) {
                throw new UnsupportedMultiReleaseException("Multi-release JARs are not supported by this verifier yet");
            }
            Set<String> names = new HashSet<>();
            int count = 0;
            long classBytes = 0;
            long entryCount = 0;
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (++entryCount > remainingEntryBudget) {
                    throw new ArchiveEntryLimitException("JAR entries exceed the total configured limit");
                }
                String name = entry.getName();
                if (application && name.startsWith("META-INF/versions/")) {
                    throw new UnsupportedMultiReleaseException("Versioned JAR entry is not supported: " + name);
                }
                if (entry.isDirectory() || !name.endsWith(".class") || name.equals("module-info.class")) continue;
                if (!names.add(name)) throw new IOException("Duplicate class entry: " + name);
                count++;
                if (application && count > maximumClasses) {
                    throw new ClassCountLimitException("JAR contains more than " + maximumClasses + " classes");
                }
                long remaining = maximumClassBytes - alreadyReadClassBytes - classBytes;
                if (remaining < 0) throw new ClassBytesLimitException("Class byte limit exceeded");
                try (InputStream input = jarFile.getInputStream(entry)) {
                    long read = countBoundedEntry(input, entry, remaining, "class entry " + name);
                    classBytes = Math.addExact(classBytes, read);
                }
            }
            return new ArchiveMetrics(application ? count : 0, classBytes, entryCount);
        }
    }

    private static byte[] readBoundedEntry(InputStream input, JarEntry entry, long maximum, String description)
            throws IOException, ClassBytesLimitException {
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(maximum, 8192));
        byte[] buffer = new byte[8192];
        long count = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            count += read;
            if (count > maximum) throw new ClassBytesLimitException(description + " exceeds " + maximum + " bytes");
            output.write(buffer, 0, read);
        }
        checkEntrySize(entry, count, description);
        return output.toByteArray();
    }

    private static long countBoundedEntry(InputStream input, JarEntry entry, long maximum, String description)
            throws IOException, ClassBytesLimitException {
        byte[] buffer = new byte[8192];
        long count = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            count += read;
            if (count > maximum) throw new ClassBytesLimitException(description + " exceeds remaining class byte budget " + maximum);
        }
        checkEntrySize(entry, count, description);
        return count;
    }

    private static void checkEntrySize(JarEntry entry, long actual, String description) throws IOException {
        if (entry.getSize() >= 0 && entry.getSize() != actual) {
            throw new IOException("ZIP size mismatch for " + description + ": declared=" + entry.getSize()
                    + ", actual=" + actual);
        }
    }

    private static CapturedOutput capture(InputStream input, int maximumBytes) {
        try (input; ByteArrayOutputStream captured = new ByteArrayOutputStream(Math.min(maximumBytes, 8192))) {
            byte[] buffer = new byte[4096];
            int read;
            boolean truncated = false;
            while ((read = input.read(buffer)) != -1) {
                int allowed = Math.min(read, maximumBytes - captured.size());
                if (allowed > 0) captured.write(buffer, 0, allowed);
                if (allowed < read) truncated = true;
            }
            return new CapturedOutput(captured.toString(StandardCharsets.UTF_8), truncated);
        } catch (IOException exception) {
            return new CapturedOutput("[failed reading target JVM output: " + exception + "]", true);
        }
    }

    private static CapturedOutput completedCapture(CompletableFuture<CapturedOutput> capture) {
        try {
            return capture.get(3, TimeUnit.SECONDS);
        } catch (Exception exception) {
            capture.cancel(true);
            return new CapturedOutput("[failed to collect target JVM output: " + exception + "]", true);
        }
    }

    private static Result parseResult(int exitCode, String output, int expectedClassCount, Options options, long startNanos) {
        String resultLine = output.lines().filter(line -> line.startsWith("ZARTEMA_RESULT\t"))
                .reduce((first, second) -> second).orElse(null);
        if (resultLine == null) {
            if (output.contains("UnsupportedClassVersionError") && output.contains("ForkedJarVerifier")) {
                return failure(FailureKind.TARGET_RUNTIME_TOO_OLD, null, "java.lang.UnsupportedClassVersionError",
                        "Verifier helper requires Java 21 or newer; target runtime could not load it. "
                                + "This does not establish the application's supported class-file version.",
                        0, "", output, false, startNanos);
            }
            return failure(FailureKind.CHILD_PROCESS_FAILED, null, null,
                    "Target JVM exited with code " + exitCode + " without a verifier result",
                    0, "", output, false, startNanos);
        }
        String[] fields = resultLine.split("\\t", -1);
        try {
            if (fields.length == 6 && fields[1].equals("PASS")) {
                int loaded = Integer.parseInt(fields[2]);
                String runtime = decode(fields[3]);
                long actualHeap = Long.parseLong(fields[4]);
                long actualMetaspace = Long.parseLong(fields[5]);
                if (exitCode != 0 || loaded != expectedClassCount || actualHeap <= 0
                        || actualHeap > options.maximumChildHeapBytes()
                        || actualMetaspace != options.maximumChildMetaspaceBytes()) {
                    return failure(FailureKind.CHILD_PROTOCOL_ERROR, null, null,
                            "Verifier result/resource mismatch: loaded=" + loaded + ", expected=" + expectedClassCount
                                    + ", exit=" + exitCode + ", maxHeap=" + actualHeap
                                    + ", maxMetaspace=" + actualMetaspace,
                            loaded, runtime, output, false, startNanos);
                }
                return new Result(true, FailureKind.NONE, null, null, null, loaded, runtime,
                        output, false, elapsed(startNanos));
            }
            if (fields.length >= 7 && fields[1].equals("FAIL")) {
                String throwableType = decode(fields[3]);
                return failure(classify(throwableType), decode(fields[2]), throwableType, decode(fields[4]),
                        parseCount(fields[5]), fields.length > 6 ? decode(fields[6]) : "", output, false, startNanos);
            }
        } catch (RuntimeException exception) {
            return failure(FailureKind.CHILD_PROTOCOL_ERROR, null, exception.getClass().getName(),
                    "Malformed verifier result: " + exception.getMessage(),
                    0, "", output, false, startNanos);
        }
        return failure(FailureKind.CHILD_PROTOCOL_ERROR, null, null,
                "Malformed verifier result from target JVM", 0, "", output, false, startNanos);
    }

    private static int parseCount(String value) {
        return Integer.parseInt(value);
    }

    private static FailureKind classify(String throwableType) {
        if (throwableType.equals("java.lang.VerifyError")) return FailureKind.JVM_VERIFY_ERROR;
        if (throwableType.equals("java.lang.ClassFormatError")
                || throwableType.equals("java.lang.UnsupportedClassVersionError")) {
            return FailureKind.JVM_CLASS_FORMAT_ERROR;
        }
        if (throwableType.equals("java.lang.NoClassDefFoundError")
                || throwableType.equals("java.lang.ClassNotFoundException")
                || throwableType.endsWith("LinkageError")) {
            return FailureKind.JVM_LINKAGE_ERROR;
        }
        return FailureKind.CHILD_REJECTED_INPUT;
    }

    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static Result failure(FailureKind kind, String className, String throwableType, String message,
                                  int loaded, String runtime, String output, boolean truncated, long startNanos) {
        return new Result(false, kind, className, throwableType, message, loaded, runtime,
                output, truncated, elapsed(startNanos));
    }

    private static Duration elapsed(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    private record CapturedOutput(String text, boolean truncated) { }
    private record ArchiveMetrics(int classCount, long classBytes, long entryCount) { }
    private static final class InputBudget {
        private final long perArchive;
        private final long total;
        private long used;
        private InputBudget(long perArchive, long total) { this.perArchive = perArchive; this.total = total; }
        private void add(long bytes) throws InputSizeLimitException {
            if (bytes > perArchive || used > total - bytes) {
                throw new InputSizeLimitException("JAR input byte limit exceeded (per archive " + perArchive
                        + ", aggregate " + total + ")");
            }
            used += bytes;
        }
    }

    private static Path createPrivateStagingDirectory() throws IOException {
        try {
            return Files.createTempDirectory("zartema-jvm-verify-",
                    PosixFilePermissions.asFileAttribute(Set.of(PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)));
        } catch (UnsupportedOperationException ignored) {
            return Files.createTempDirectory("zartema-jvm-verify-");
        }
    }

    private static Path stageInput(Path source, String name, String role, Path directory, InputBudget budget,
                                   long maximumArchiveBytes)
            throws IOException, InvalidInputException, UnsupportedDirectoryException,
            InputSizeLimitException, InputChangedException {
        Path normalized = source.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized)) throw new InvalidInputException("Symbolic-link " + role + " inputs are unsupported: " + source);
        BasicFileAttributes before = Files.readAttributes(normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (before.isDirectory()) throw new UnsupportedDirectoryException("Directory " + role + " inputs are unsupported: " + source);
        if (!before.isRegularFile()) throw new InvalidInputException(role + " input must be a regular file: " + source);
        if (before.size() > maximumArchiveBytes) throw new InputSizeLimitException(role + " archive exceeds " + maximumArchiveBytes + " bytes");
        Path staged = directory.resolve(name);
        long copied = 0;
        try (FileChannel input = FileChannel.open(normalized, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             FileChannel output = FileChannel.open(staged, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var buffer = java.nio.ByteBuffer.allocate(64 * 1024);
            while (true) {
                buffer.clear();
                int read = input.read(buffer);
                if (read < 0) break;
                copied += read;
                if (copied > maximumArchiveBytes) throw new InputSizeLimitException(role + " archive exceeds " + maximumArchiveBytes + " bytes while reading");
                budget.add(read);
                buffer.flip();
                while (buffer.hasRemaining()) output.write(buffer);
            }
            output.force(true);
        }
        BasicFileAttributes after = Files.readAttributes(normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!after.isRegularFile() || before.size() != copied || after.size() != copied
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || (before.fileKey() != null && !before.fileKey().equals(after.fileKey()))) {
            throw new InputChangedException(role + " input changed while taking verifier snapshot: " + source);
        }
        return staged;
    }

    private static void deleteStagingDirectory(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static final class UnsupportedMultiReleaseException extends Exception {
        private UnsupportedMultiReleaseException(String message) { super(message); }
    }
    private static final class UnsupportedManifestClasspathException extends Exception {
        private UnsupportedManifestClasspathException(String message) { super(message); }
    }
    private static final class ClassCountLimitException extends Exception {
        private ClassCountLimitException(String message) { super(message); }
    }
    private static final class InvalidInputException extends Exception { private InvalidInputException(String m) { super(m); } }
    private static final class UnsupportedDirectoryException extends Exception { private UnsupportedDirectoryException(String m) { super(m); } }
    private static final class InputSizeLimitException extends Exception { private InputSizeLimitException(String m) { super(m); } }
    private static final class ClassBytesLimitException extends Exception { private ClassBytesLimitException(String m) { super(m); } }
    private static final class InputChangedException extends Exception { private InputChangedException(String m) { super(m); } }
    private static final class DependencyCountLimitException extends Exception { private DependencyCountLimitException(String m) { super(m); } }
    private static final class ArchiveEntryLimitException extends Exception { private ArchiveEntryLimitException(String m) { super(m); } }
}
