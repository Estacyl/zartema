package org.zartema.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class AnalysisSnapshotCoordinatorTest {
    @TempDir Path directory;

    @Test
    void comparesWalaAndSootUpFactsFromOneDeterministicSnapshot() throws Exception {
        Path jar = directory.resolve("same-input.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            put(output, "sample/Base.class", classBytes("sample/Base", "java/lang/Object", null));
            put(output, "sample/Marker.class", interfaceBytes("sample/Marker"));
            put(output, "sample/Child.class", classBytes("sample/Child", "sample/Base", "sample/Marker"));
        }
        String before = digest(jar);

        AnalysisSnapshotCoordinator.Report report = AnalysisSnapshotCoordinator.analyze(jar);

        assertEquals(before, report.snapshotSha256());
        assertTrue(report.classesOnlyInWala().isEmpty());
        assertTrue(report.classesOnlyInSootUp().isEmpty());
        assertTrue(report.hierarchyDisagreements().isEmpty());
        assertTrue(report.ambiguousClassIdentities().isEmpty());
        assertTrue(report.missingHierarchyReferences().isEmpty());
        assertFalse(report.mayAuthorizeRenaming());
        assertFalse(report.sootUpUnresolvedAccountingAvailable());
    }

    @Test
    void reportsAmbiguousDuplicateClassIdentityAndUnresolvedSuperclass() throws Exception {
        Path jar = directory.resolve("ambiguous-input.jar");
        byte[] duplicate = classBytes("sample/Duplicate", "java/lang/Object", null);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            put(output, "sample/Duplicate.class", duplicate);
            put(output, "shadow/Duplicate.class", duplicate);
            put(output, "sample/UnknownChild.class",
                    classBytes("sample/UnknownChild", "external/MissingBase", null));
        }

        AnalysisSnapshotCoordinator.Report report = AnalysisSnapshotCoordinator.analyze(jar);

        assertTrue(report.ambiguousClassIdentities().contains("sample/Duplicate"));
        assertTrue(report.missingHierarchyReferences().contains("external/MissingBase"),
                report.missingHierarchyReferences().toString());
        assertFalse(report.mayAuthorizeRenaming());
    }

    @Test
    void enforcesArchiveLimitsBeforePassingInputToAnalysisLibraries() throws Exception {
        Path jar = directory.resolve("oversized-class.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            put(output, "sample/Big.class", classBytes("sample/Big", "java/lang/Object", null));
        }
        var limits = new org.zartema.archive.JarArchive.Limits(10, 8, 1024);
        var error = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> AnalysisSnapshotCoordinator.analyze(jar, limits));
        assertTrue(error.getMessage().contains("limit"), error.getMessage());
    }

    private static void put(JarOutputStream output, String name, byte[] bytes) throws IOException {
        output.putNextEntry(new JarEntry(name));
        output.write(bytes);
        output.closeEntry();
    }

    private static byte[] classBytes(String name, String superclass, String iface) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, superclass,
                iface == null ? null : new String[] {iface});
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] interfaceBytes(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                name, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static String digest(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
