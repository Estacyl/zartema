package org.zartema.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class JvmJarLoadVerifierTest {
    @TempDir Path temporaryDirectory;

    private final JvmJarLoadVerifier verifier = new JvmJarLoadVerifier();
    private final JvmJarLoadVerifier.Options options = JvmJarLoadVerifier.Options.forCurrentRuntime();

    @Test
    void verifiesAllClassesWithoutRunningStaticInitializers() throws Exception {
        Path marker = temporaryDirectory.resolve("initializer-ran");
        Path jar = jar("valid.jar", List.of(
                entry("fixture/Probe.class", initializerProbe("fixture/Probe", marker)),
                entry("fixture/Plain.class", validClass("fixture/Plain"))));

        JvmJarLoadVerifier.Result result = verifier.verify(jar, options);

        assertTrue(result.passed(), result.toString());
        assertEquals(2, result.classesLoaded());
        assertFalse(result.runtimeVersion().isBlank());
        assertFalse(Files.exists(marker), "load gate must not initialize application classes");
    }

    @Test
    void reportsActualJvmVerifyErrorAndDoesNotInitializeOtherClasses() throws Exception {
        Path marker = temporaryDirectory.resolve("initializer-ran-on-failure");
        Path jar = jar("invalid.jar", List.of(
                entry("a/Invalid.class", invalidReturnTypeClass("a/Invalid")),
                entry("z/Probe.class", initializerProbe("z/Probe", marker))));

        JvmJarLoadVerifier.Result result = verifier.verify(jar, options);

        assertFalse(result.passed());
        assertEquals(JvmJarLoadVerifier.FailureKind.JVM_VERIFY_ERROR, result.failureKind());
        assertEquals("a.Invalid", result.className());
        assertEquals("java.lang.VerifyError", result.throwableType());
        assertFalse(result.message().isBlank());
        assertFalse(Files.exists(marker), "failure path must not initialize application classes");
    }

    @Test
    void explicitDependencyIsUsedAndMissingDependencyIsReported() throws Exception {
        Path dependencyJar = jar("dependency.jar", List.of(
                entry("fixturedep/Parent.class", validClass("fixturedep/Parent"))));
        Path appJar = jar("with-dependency.jar", List.of(
                entry("fixture/Child.class", subclass("fixture/Child", "fixturedep/Parent"))));

        JvmJarLoadVerifier.Result withoutDependency = verifier.verify(appJar, options);
        JvmJarLoadVerifier.Options withDependency = new JvmJarLoadVerifier.Options(options.javaExecutable(),
                List.of(dependencyJar), options.timeout(), options.maximumOutputBytes(), options.maximumClasses());
        JvmJarLoadVerifier.Result with = verifier.verify(appJar, withDependency);

        assertFalse(withoutDependency.passed());
        assertEquals(JvmJarLoadVerifier.FailureKind.JVM_LINKAGE_ERROR, withoutDependency.failureKind());
        assertTrue(withoutDependency.throwableType().endsWith("NoClassDefFoundError"), withoutDependency.toString());
        assertTrue(with.passed(), with.toString());
    }

    @Test
    void rejectsMultiReleaseArchivesBeforeLaunchingTargetJvm() throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MULTI_RELEASE, "true");
        Path jar = jar("multi-release.jar", List.of(entry("fixture/Plain.class", validClass("fixture/Plain"))), manifest);

        JvmJarLoadVerifier.Result result = verifier.verify(jar, options);

        assertFalse(result.passed());
        assertEquals(JvmJarLoadVerifier.FailureKind.UNSUPPORTED_MULTI_RELEASE_JAR, result.failureKind());
        assertTrue(result.message().contains("Multi-release"));
    }

    @Test
    void rejectsClassesShadowedByPlatformLoader() throws Exception {
        Path jar = jar("shadowed.jar", List.of(
                entry("org/w3c/dom/Node.class", validClass("org/w3c/dom/Node"))));

        JvmJarLoadVerifier.Result result = verifier.verify(jar, options);

        assertFalse(result.passed());
        assertEquals(JvmJarLoadVerifier.FailureKind.JVM_LINKAGE_ERROR, result.failureKind());
        assertEquals("org.w3c.dom.Node", result.className());
        assertEquals("java.lang.LinkageError", result.throwableType());
        assertTrue(result.message().contains("shadowed by parent loader"));
    }

    @Test
    void demonstratesManifestClasspathExpansionAndRejectsItForApplicationAndDependencies() throws Exception {
        Path escapedJar = jar("escaped.jar", List.of(
                entry("escape/OnlyFromManifest.class", validClass("escape/OnlyFromManifest"))));
        Manifest mainManifest = manifestWithClassPath("escaped.jar");
        Path mainWithManifestPath = jar("main-with-class-path.jar",
                List.of(entry("fixture/Main.class", validClass("fixture/Main"))), mainManifest);
        try (URLClassLoader loader = new URLClassLoader(new URL[]{mainWithManifestPath.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            Class<?> escaped = Class.forName("escape.OnlyFromManifest", false, loader);
            assertSame(loader, escaped.getClassLoader(), "Java 21 URLClassLoader follows manifest Class-Path");
        }

        assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"),
                "pre-launch sentinel uses a Unix executable script");
        Path launchMarker = temporaryDirectory.resolve("target-java-was-launched");
        Path targetTrap = targetJavaTrap(launchMarker);
        JvmJarLoadVerifier.Options trapOptions = new JvmJarLoadVerifier.Options(targetTrap, List.of(),
                Duration.ofSeconds(2), options.maximumOutputBytes(), options.maximumClasses());
        JvmJarLoadVerifier.Result applicationResult = verifier.verify(mainWithManifestPath, trapOptions);
        assertEquals(JvmJarLoadVerifier.FailureKind.UNSUPPORTED_MANIFEST_CLASSPATH,
                applicationResult.failureKind(), applicationResult.toString());
        assertFalse(Files.exists(launchMarker), "application manifest must be rejected before process launch");

        Path dependencyWithManifestPath = jar("dependency-with-class-path.jar",
                List.of(entry("fixturedep/Dependency.class", validClass("fixturedep/Dependency"))),
                manifestWithClassPath("escaped.jar"));
        try (URLClassLoader loader = new URLClassLoader(new URL[]{dependencyWithManifestPath.toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            Class<?> escaped = Class.forName("escape.OnlyFromManifest", false, loader);
            assertSame(loader, escaped.getClassLoader(), "an explicit dependency URL also expands its manifest Class-Path");
        }
        Path cleanMain = jar("clean-main.jar", List.of(entry("fixture/Main.class", validClass("fixture/Main"))));
        JvmJarLoadVerifier.Options dependencyTrap = new JvmJarLoadVerifier.Options(targetTrap,
                List.of(dependencyWithManifestPath), Duration.ofSeconds(2), options.maximumOutputBytes(),
                options.maximumClasses());
        JvmJarLoadVerifier.Result dependencyResult = verifier.verify(cleanMain, dependencyTrap);
        assertEquals(JvmJarLoadVerifier.FailureKind.UNSUPPORTED_MANIFEST_CLASSPATH,
                dependencyResult.failureKind(), dependencyResult.toString());
        assertFalse(Files.exists(launchMarker), "explicit dependency manifest must be rejected before process launch");
    }

    @Test
    void enforcesTimeoutAndInputLimitsAsConfigurationInvariants() {
        assertThrows(IllegalArgumentException.class, () -> new JvmJarLoadVerifier.Options(
                options.javaExecutable(), List.of(), Duration.ZERO, 1024, 1));
        assertThrows(IllegalArgumentException.class, () -> new JvmJarLoadVerifier.Options(
                options.javaExecutable(), List.of(), Duration.ofSeconds(1), 100, 1));
    }

    @Test
    void rejectsOlderJavaAsVerifierRuntimeRequirementRatherThanArtifactFailure() throws Exception {
        Path olderJava = Path.of("/usr/lib/jvm/java-17-openjdk/bin/java");
        assumeFalse(!Files.isExecutable(olderJava), "Java 17 fixture runtime is not installed");
        Path jar = jar("java17-compatible.jar", List.of(entry("fixture/Plain.class", validClass17("fixture/Plain"))));
        JvmJarLoadVerifier.Options oldRuntime = new JvmJarLoadVerifier.Options(olderJava, List.of(),
                Duration.ofSeconds(10), options.maximumOutputBytes(), options.maximumClasses());

        JvmJarLoadVerifier.Result result = verifier.verify(jar, oldRuntime);

        assertFalse(result.passed());
        assertEquals(JvmJarLoadVerifier.FailureKind.TARGET_RUNTIME_TOO_OLD, result.failureKind(), result.toString());
        assertTrue(result.message().contains("requires Java 21"));
        assertTrue(result.message().contains("does not establish"));
    }

    @Test
    void boundsCompressedInputsAndDecompressedClassPayloadsIncludingManifest() throws Exception {
        Path app = jar("bounded.jar", List.of(entry("fixture/Plain.class", validClass("fixture/Plain"))));
        var smallArchive = boundedOptions(1024, 4096, 4096, 96L << 20, 64L << 20, List.of());
        assertTrue(verifier.verify(app, smallArchive).passed());

        Path largeDependency = jar("large-dependency.jar", List.of(entry("fixturedep/Parent.class", validClass("fixturedep/Parent"))));
        var inputLimited = boundedOptions(32, 128, 4096, 96L << 20, 64L << 20, List.of(largeDependency));
        assertEquals(JvmJarLoadVerifier.FailureKind.INPUT_SIZE_LIMIT,
                verifier.verify(app, inputLimited).failureKind());

        long aggregateLimit = Files.size(app) + Files.size(largeDependency) - 1;
        long largestArchive = Math.max(Files.size(app), Files.size(largeDependency));
        var aggregateLimited = boundedOptions(largestArchive, aggregateLimit, 4096, 96L << 20, 64L << 20,
                List.of(largeDependency));
        assertEquals(JvmJarLoadVerifier.FailureKind.INPUT_SIZE_LIMIT,
                verifier.verify(app, aggregateLimited).failureKind());

        var classLimited = boundedOptions(4096, 8192, 8, 96L << 20, 64L << 20, List.of());
        assertEquals(JvmJarLoadVerifier.FailureKind.CLASS_BYTES_LIMIT,
                verifier.verify(app, classLimited).failureKind());

        Manifest oversized = manifestWithClassPath(" ".repeat(1024 * 1024));
        Path largeManifest = jar("large-manifest.jar", List.of(entry("fixture/Plain.class", validClass("fixture/Plain"))), oversized);
        var manifestLimited = boundedOptions(4L << 20, 4L << 20, 4L << 20, 96L << 20, 64L << 20, List.of());
        assertEquals(JvmJarLoadVerifier.FailureKind.CLASS_BYTES_LIMIT,
                verifier.verify(largeManifest, manifestLimited).failureKind());
    }

    @Test
    void rejectsDirectoryDependenciesAndEnforcesChildMemorySettings() throws Exception {
        Path app = jar("memory.jar", List.of(entry("fixture/Plain.class", validClass("fixture/Plain"))));
        var directoryDependency = new JvmJarLoadVerifier.Options(options.javaExecutable(), List.of(temporaryDirectory),
                Duration.ofSeconds(10), options.maximumOutputBytes(), options.maximumClasses());
        assertEquals(JvmJarLoadVerifier.FailureKind.UNSUPPORTED_DIRECTORY_DEPENDENCY,
                verifier.verify(app, directoryDependency).failureKind());

        var capped = boundedOptions(4096, 8192, 4096, 96L << 20, 64L << 20, List.of());
        JvmJarLoadVerifier.Result result = verifier.verify(app, capped);
        assertTrue(result.passed(), result.toString());
    }

    @Test
    void capsDependencyCountBeforeStagingAndAggregateArchiveEntries() throws Exception {
        Path app = jar("entries-app.jar", List.of(
                entry("fixture/One.class", validClass("fixture/One")),
                entry("fixture/Two.class", validClass("fixture/Two"))));
        Path dep = jar("entries-dep.jar", List.of(entry("fixturedep/One.class", validClass("fixturedep/One"))));
        var entryLimited = new JvmJarLoadVerifier.Options(options.javaExecutable(), List.of(dep), Duration.ofSeconds(10),
                options.maximumOutputBytes(), options.maximumClasses(), 4096, 8192, 8192,
                96L << 20, 64L << 20, 2);
        assertEquals(JvmJarLoadVerifier.FailureKind.ARCHIVE_ENTRY_LIMIT,
                verifier.verify(app, entryLimited).failureKind());

        List<Path> tooManyDependencies = java.util.Collections.nCopies(257, dep);
        var dependencyLimited = new JvmJarLoadVerifier.Options(options.javaExecutable(), tooManyDependencies,
                Duration.ofSeconds(10), options.maximumOutputBytes(), options.maximumClasses());
        assertEquals(JvmJarLoadVerifier.FailureKind.DEPENDENCY_COUNT_LIMIT,
                verifier.verify(app, dependencyLimited).failureKind());
    }

    private JvmJarLoadVerifier.Options boundedOptions(long archive, long total, long classBytes,
                                                        long heap, long metaspace, List<Path> dependencies) {
        return new JvmJarLoadVerifier.Options(options.javaExecutable(), dependencies, Duration.ofSeconds(20),
                options.maximumOutputBytes(), options.maximumClasses(), archive, total, classBytes, heap, metaspace);
    }

    @Test
    void terminatesAChildThatExceedsItsConfiguredDeadline() throws Exception {
        Path jar = jar("timeout.jar", List.of());
        JvmJarLoadVerifier.Options deadline = new JvmJarLoadVerifier.Options(options.javaExecutable(), List.of(),
                Duration.ofNanos(1), options.maximumOutputBytes(), options.maximumClasses());

        JvmJarLoadVerifier.Result result = verifier.verify(jar, deadline);

        assertFalse(result.passed());
        assertEquals(JvmJarLoadVerifier.FailureKind.TIMEOUT, result.failureKind());
    }

    private Path jar(String filename, List<Entry> entries) throws IOException {
        return jar(filename, entries, null);
    }

    private Path jar(String filename, List<Entry> entries, Manifest manifest) throws IOException {
        Path path = temporaryDirectory.resolve(filename);
        try (JarOutputStream output = manifest == null
                ? new JarOutputStream(Files.newOutputStream(path))
                : new JarOutputStream(Files.newOutputStream(path), manifest)) {
            for (Entry entry : entries) {
                output.putNextEntry(new JarEntry(entry.name()));
                output.write(entry.bytes());
                output.closeEntry();
            }
        }
        return path;
    }

    private static Manifest manifestWithClassPath(String classPath) {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classPath);
        return manifest;
    }

    private Path targetJavaTrap(Path marker) throws IOException {
        Path script = temporaryDirectory.resolve("target-java-trap.sh");
        Files.writeString(script, "#!/bin/sh\nprintf launched > '" + marker + "'\nexit 87\n");
        assertTrue(script.toFile().setExecutable(true));
        return script;
    }

    private static Entry entry(String name, byte[] bytes) { return new Entry(name, bytes); }

    private static byte[] validClass(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] validClass17(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] subclass(String name, String parent) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, parent, null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] invalidReturnTypeClass(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "bad", "()I", null, null);
        method.visitCode();
        method.visitInsn(Opcodes.RETURN); // Deliberately invalid: void return in a method returning int.
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] initializerProbe(String className, Path marker) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        method.visitCode();
        method.visitTypeInsn(Opcodes.NEW, "java/io/File");
        method.visitInsn(Opcodes.DUP);
        method.visitLdcInsn(marker.toAbsolutePath().toString());
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/io/File", "<init>", "(Ljava/lang/String;)V", false);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/File", "createNewFile", "()Z", false);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(3, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private record Entry(String name, byte[] bytes) { }
}
