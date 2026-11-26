package org.zartema.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class MultiReleaseJarViewTest {
    @TempDir Path directory;

    @Test
    void selectsHighestEligibleReleaseAndFallsBackToBasePerPath() throws Exception {
        Path jar = fixture("precedence.jar", true);
        JarArchive archive = JarArchive.read(jar);

        assertEquals("base", text(MultiReleaseJarView.select(archive, 8), "sample/value.txt"));
        assertEquals("v9", text(MultiReleaseJarView.select(archive, 9), "sample/value.txt"));
        assertEquals("v9", text(MultiReleaseJarView.select(archive, 17), "sample/value.txt"));
        assertEquals("v21", text(MultiReleaseJarView.select(archive, 21), "sample/value.txt"));
        assertEquals("base-only", text(MultiReleaseJarView.select(archive, 21), "sample/base-only.txt"));
        assertEquals("v21-only", text(MultiReleaseJarView.select(archive, 21), "sample/v21-only.txt"));
        assertEquals(21, MultiReleaseJarView.select(archive, 21).find("sample/value.txt").version());
    }

    @Test
    void selectionIsIndependentOfPhysicalEntryOrder() throws Exception {
        Path versionsFirst = orderedVersionsFixture("versions-first.jar", true);
        Path baseFirst = orderedVersionsFixture("base-first.jar", false);

        MultiReleaseJarView first = MultiReleaseJarView.select(JarArchive.read(versionsFirst), 21);
        MultiReleaseJarView second = MultiReleaseJarView.select(JarArchive.read(baseFirst), 21);

        assertEquals("META-INF/versions/21/sample/order.txt",
                first.find("sample/order.txt").physicalName());
        assertEquals(first.find("sample/order.txt").physicalName(),
                second.find("sample/order.txt").physicalName());
        assertEquals("v21", text(first, "sample/order.txt"));
        assertEquals("v21", text(second, "sample/order.txt"));
    }

    @Test
    void selectsReleaseSpecificClassesAsTheJdk21RuntimeDoes() throws Exception {
        Path jar = fixture("jdk-semantics.jar", true);
        JarArchive archive = JarArchive.read(jar);

        for (int target : new int[] {8, 9, 17, 21, 22}) {
            MultiReleaseJarView view = MultiReleaseJarView.select(archive, target);
            for (String logicalName : List.of("sample/value.txt", "sample/MrRuntimeFixture.class")) {
                MultiReleaseJarView.EffectiveEntry selected = view.find(logicalName);
                assertNotNull(selected);
                try (JarFile jdk = new JarFile(jar.toFile(), false, ZipFile.OPEN_READ,
                        Runtime.Version.parse(Integer.toString(target)))) {
                    JarEntry runtimeEntry = jdk.getJarEntry(logicalName);
                    assertNotNull(runtimeEntry, "JDK JarFile selection at release " + target);
                    assertEquals(logicalName, runtimeEntry.getName());
                    assertEquals(selected.physicalName(), runtimeEntry.getRealName(),
                            "JDK 21 JarFile physical selection at release " + target);
                    assertTrue(jdk.versionedStream().anyMatch(entry -> entry.getName().equals(logicalName)
                                    && entry.getRealName().equals(selected.physicalName())),
                            "JDK 21 versionedStream selection at release " + target);
                    try (var input = jdk.getInputStream(runtimeEntry)) {
                        assertArrayEquals(selected.entry().content(), input.readAllBytes());
                    }
                }
            }
        }

        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] {jar.toUri().toURL()}, null)) {
            Class<?> selected = Class.forName("sample.MrRuntimeFixture", true, loader);
            Method origin = selected.getMethod("origin");
            assertEquals("v21", origin.invoke(null), "the test JVM should use its Java 21 MR-JAR view");
        }
    }

    @Test
    void unmarkedVersionDirectoryIsNotAnOverrideAndRemainsLiteral() throws Exception {
        Path jar = fixture("unmarked.jar", false);
        MultiReleaseJarView view = MultiReleaseJarView.select(JarArchive.read(jar), 21);

        assertTrue(!view.multiRelease());
        assertEquals("base", text(view, "sample/value.txt"));
        assertEquals("v21", text(view, "META-INF/versions/21/sample/value.txt"));
    }

    @Test
    void ignoresNonconformingAndSubNineVersionDirectoriesAsSpecified() throws Exception {
        Path jar = directory.resolve("ignored-versions.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest(true))) {
            put(output, "sample/value.txt", "base");
            put(output, "META-INF/versions/08/sample/value.txt", "leading-zero");
            put(output, "META-INF/versions/8/sample/value.txt", "pre-nine");
            put(output, "META-INF/versions/nope/sample/value.txt", "not-a-release");
            put(output, "META-INF/versions/9/sample/value.txt", "v9");
        }
        MultiReleaseJarView view = MultiReleaseJarView.select(JarArchive.read(jar), 21);

        assertEquals("v9", text(view, "sample/value.txt"));
        assertNull(view.find("META-INF/versions/08/sample/value.txt"));
    }

    @Test
    void rejectsVersionedMetaInfResourcesAndInvalidTargetRelease() throws Exception {
        Path jar = directory.resolve("forbidden-meta-inf.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest(true))) {
            put(output, "META-INF/versions/9/META-INF/services/sample.Service", "provider");
        }
        assertThrows(IOException.class, () -> MultiReleaseJarView.select(JarArchive.read(jar), 21));
        assertThrows(IllegalArgumentException.class,
                () -> MultiReleaseJarView.select(JarArchive.read(fixture("target-check.jar", true)), 7));
    }

    @Test
    void rejectsAmbiguousCaseVariantManifestPaths() throws Exception {
        Path jar = directory.resolve("duplicate-manifest.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest(true))) {
            put(output, "meta-inf/manifest.mf", "Manifest-Version: 1.0\nMulti-Release: false\n\n");
        }
        assertThrows(IOException.class, () -> MultiReleaseJarView.select(JarArchive.read(jar), 21));
    }

    @Test
    void selectedViewIsImmutableAndSortedByLogicalName() throws Exception {
        MultiReleaseJarView view = MultiReleaseJarView.select(JarArchive.read(fixture("sorted.jar", true)), 21);
        assertEquals(view.entries().stream().map(MultiReleaseJarView.EffectiveEntry::logicalName).sorted().toList(),
                view.entries().stream().map(MultiReleaseJarView.EffectiveEntry::logicalName).toList());
        assertThrows(UnsupportedOperationException.class, () -> view.entries().clear());
    }

    private Path fixture(String name, boolean marked) throws IOException {
        Path jar = directory.resolve(name);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest(marked))) {
            put(output, "sample/value.txt", "base");
            put(output, "sample/base-only.txt", "base-only");
            put(output, "META-INF/versions/9/sample/value.txt", "v9");
            put(output, "META-INF/versions/21/sample/value.txt", "v21");
            put(output, "META-INF/versions/21/sample/v21-only.txt", "v21-only");
            put(output, "sample/MrRuntimeFixture.class", runtimeClass("base", Opcodes.V1_8));
            put(output, "META-INF/versions/9/sample/MrRuntimeFixture.class", runtimeClass("v9", Opcodes.V9));
            put(output, "META-INF/versions/21/sample/MrRuntimeFixture.class", runtimeClass("v21", Opcodes.V21));
        }
        return jar;
    }

    private Path orderedVersionsFixture(String name, boolean versionsFirst) throws IOException {
        Path jar = directory.resolve(name);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest(true))) {
            if (versionsFirst) {
                put(output, "META-INF/versions/9/sample/order.txt", "v9");
                put(output, "META-INF/versions/21/sample/order.txt", "v21");
                put(output, "sample/order.txt", "base");
            } else {
                put(output, "sample/order.txt", "base");
                put(output, "META-INF/versions/21/sample/order.txt", "v21");
                put(output, "META-INF/versions/9/sample/order.txt", "v9");
            }
        }
        return jar;
    }

    private static Manifest manifest(boolean marked) {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (marked) manifest.getMainAttributes().putValue("Multi-Release", "TRUE");
        return manifest;
    }

    private static String text(MultiReleaseJarView view, String logicalName) {
        MultiReleaseJarView.EffectiveEntry entry = view.find(logicalName);
        assertNotNull(entry, "missing effective entry " + logicalName);
        return new String(entry.entry().content(), StandardCharsets.UTF_8);
    }

    private static void put(JarOutputStream output, String name, String content) throws IOException {
        put(output, name, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void put(JarOutputStream output, String name, byte[] content) throws IOException {
        output.putNextEntry(new JarEntry(name));
        output.write(content);
        output.closeEntry();
    }

    private static byte[] runtimeClass(String origin, int classVersion) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(classVersion, Opcodes.ACC_PUBLIC, "sample/MrRuntimeFixture", null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "origin", "()Ljava/lang/String;", null, null);
        method.visitCode();
        method.visitLdcInsn(origin);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
