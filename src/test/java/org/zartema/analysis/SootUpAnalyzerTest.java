package org.zartema.analysis;

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
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

class SootUpAnalyzerTest {
    @TempDir Path directory;

    @Test
    void analyzesMethodsStatementsInvocationsAndControlFlow() throws Exception {
        Path jar = directory.resolve("analysis-input.jar");
        writeFixture(jar);

        SootUpAnalyzer.AnalysisReport report = SootUpAnalyzer.analyze(jar);

        assertEquals(1, report.classCount());
        assertEquals(2, report.methodCount());
        assertEquals(2, report.concreteMethodCount());
        assertTrue(report.statementCount() >= 6);
        assertTrue(report.invocationCount() >= 1);
        assertTrue(report.controlFlowEdgeCount() >= 5);
        assertTrue(report.classes().stream().anyMatch(type -> type.internalName().equals("sample/Example")));
    }

    @Test
    void rejectsMultiReleaseJarUntilTargetReleaseSelectionExists() throws Exception {
        Path jar = directory.resolve("multi-release.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Multi-Release", "true");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            put(output, "sample/Example.class", simpleClassBytes("sample/Example"));
            put(output, "META-INF/versions/21/sample/Example.class", simpleClassBytes("sample/Example"));
        }

        IOException failure = assertThrows(IOException.class, () -> SootUpAnalyzer.analyze(jar));
        assertTrue(failure.getMessage().contains("versioned"));
    }

    @Test
    void rejectsPhysicalVersionedEntriesWithoutManifestMarker() throws Exception {
        Path jar = directory.resolve("unmarked-versioned.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            put(output, "sample/Example.class", simpleClassBytes("sample/Example"));
            put(output, "META-INF/versions/21/sample/Example.class", simpleClassBytes("sample/Example"));
        }

        IOException failure = assertThrows(IOException.class, () -> SootUpAnalyzer.analyze(jar));
        assertTrue(failure.getMessage().contains("versioned"));
    }

    private static void writeFixture(Path jar) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "sample/Example", null, "java/lang/Object", null);

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();

        MethodVisitor run = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "(I)I", null, null);
        run.visitCode();
        org.objectweb.asm.Label nonZero = new org.objectweb.asm.Label();
        run.visitVarInsn(Opcodes.ILOAD, 0);
        run.visitJumpInsn(Opcodes.IFEQ, nonZero);
        run.visitVarInsn(Opcodes.ILOAD, 0);
        run.visitInsn(Opcodes.IRETURN);
        run.visitLabel(nonZero);
        run.visitInsn(Opcodes.ICONST_1);
        run.visitInsn(Opcodes.IRETURN);
        run.visitMaxs(1, 1);
        run.visitEnd();

        writer.visitEnd();
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("sample/Example.class"));
            output.write(writer.toByteArray());
            output.closeEntry();
        }
    }

    private static void put(JarOutputStream output, String name, byte[] bytes) throws IOException {
        output.putNextEntry(new JarEntry(name));
        output.write(bytes);
        output.closeEntry();
    }

    private static byte[] simpleClassBytes(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }
}
