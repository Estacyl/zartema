package org.zartema.analysis.wala;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class WalaHierarchyAnalyzerTest {
    @TempDir Path directory;

    @Test
    void reportsApplicationInheritanceAndInterfaceRelationsFromWala() throws Exception {
        Path jar = directory.resolve("hierarchy.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            put(output, "sample/Base.class", classBytes("sample/Base", "java/lang/Object", null));
            put(output, "sample/Named.class", interfaceBytes("sample/Named"));
            put(output, "sample/Child.class", classBytes("sample/Child", "sample/Base", "sample/Named"));
        }

        var report = WalaHierarchyAnalyzer.analyze(jar);

        assertEquals(3, report.applicationClasses().size());
        assertEquals(WalaHierarchyAnalyzer.Assignability.YES,
                report.assignability("sample/Child", "sample/Base"));
        assertEquals(WalaHierarchyAnalyzer.Assignability.YES,
                report.assignability("sample/Child", "sample/Named"));
        assertEquals(WalaHierarchyAnalyzer.Assignability.YES,
                report.assignability("sample/Child", "java/lang/Object"));
        assertEquals(WalaHierarchyAnalyzer.Assignability.NO,
                report.assignability("sample/Base", "sample/Child"));
        assertTrue(report.hierarchyClassCount() > report.applicationClasses().size());
    }

    @Test
    void reportsUnknownWhenUnresolvedTypesPreventProvingANegative() {
        var fact = new WalaHierarchyAnalyzer.ClassFact("sample/Child", false,
                "java/lang/Object", java.util.List.of(), java.util.List.of("java/lang/Object"));
        var report = new WalaHierarchyAnalyzer.Report(1, java.util.List.of(fact),
                java.util.List.of("sample/Child", "java/lang/Object"),
                java.util.List.of("missing/Dependency"));

        assertEquals(WalaHierarchyAnalyzer.Assignability.UNKNOWN,
                report.assignability("sample/Child", "sample/UnseenBase"));
        assertEquals(WalaHierarchyAnalyzer.Assignability.YES,
                report.assignability("sample/Child", "java/lang/Object"));
    }

    @Test
    void doesNotClaimNoForAnUnknownSupertypeName() throws Exception {
        Path jar = directory.resolve("known-hierarchy.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            put(output, "sample/Child.class", classBytes("sample/Child", "java/lang/Object", null));
        }

        var report = WalaHierarchyAnalyzer.analyze(jar);

        assertEquals(WalaHierarchyAnalyzer.Assignability.UNKNOWN,
                report.assignability("sample/Child", "sample/NotInScope"));
        assertEquals(WalaHierarchyAnalyzer.Assignability.NO,
                report.assignability("sample/Child", "java/lang/String"));
    }

    @Test
    void rejectsMultiReleaseJarUntilVersionAwareScopeSelectionExists() throws Exception {
        Path jar = directory.resolve("multi-release.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Multi-Release", "true");
        try (var output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            put(output, "sample/Thing.class", classBytes("sample/Thing", "java/lang/Object", null));
            put(output, "META-INF/versions/21/sample/Thing.class",
                    classBytes("sample/Thing", "java/lang/Object", null));
        }

        IOException failure = assertThrows(IOException.class, () -> WalaHierarchyAnalyzer.analyze(jar));
        assertTrue(failure.getMessage().contains("versioned"));
    }

    @Test
    void rejectsVersionedPathsEvenWithoutMultiReleaseManifestMarker() throws Exception {
        Path jar = directory.resolve("unmarked-versioned.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            put(output, "sample/Thing.class", classBytes("sample/Thing", "java/lang/Object", null));
            put(output, "META-INF/versions/21/sample/Thing.class",
                    classBytes("sample/Thing", "java/lang/Object", null));
        }

        IOException failure = assertThrows(IOException.class, () -> WalaHierarchyAnalyzer.analyze(jar));
        assertTrue(failure.getMessage().contains("versioned"));
    }

    private static void put(JarOutputStream output, String name, byte[] bytes) throws IOException {
        output.putNextEntry(new JarEntry(name));
        output.write(bytes);
        output.closeEntry();
    }

    private static byte[] classBytes(String name, String superclass, String implementedInterface) {
        var writer = new ClassWriter(0);
        String[] interfaces = implementedInterface == null ? null : new String[] {implementedInterface};
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, superclass, interfaces);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] interfaceBytes(String name) {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                name, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }
}
