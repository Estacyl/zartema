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
import org.objectweb.asm.Type;

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
    void inventoriesLiteralAndUnknownMemberNamesAtRecognizedReflectionSinks() throws Exception {
        Path jar = directory.resolve("reflection-sinks.jar");
        writeReflectionFixture(jar);

        SootUpAnalyzer.AnalysisReport report = SootUpAnalyzer.analyze(jar);
        SootUpAnalyzer.AnalysisReport repeated = SootUpAnalyzer.analyze(jar);

        assertEquals(report.reflectionSinks(), repeated.reflectionSinks());
        assertEquals(5, report.reflectionSinks().size(), report.reflectionSinks().toString());

        var classLiteral = evidenceFor(report, "literal");
        assertEquals("java/lang/Class", classLiteral.sinkOwnerInternalName());
        assertEquals("getDeclaredMethod", classLiteral.sinkMethodName());
        assertEquals("(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;",
                classLiteral.sinkMethodDescriptor());
        assertEquals(0, classLiteral.memberNameArgumentIndex());
        assertEquals(java.util.Optional.of("target"), classLiteral.constantMemberName());

        assertTrue(evidenceFor(report, "parameter").constantMemberName().isEmpty());
        assertTrue(evidenceFor(report, "derived").constantMemberName().isEmpty());

        var handleLiteral = evidenceFor(report, "findVirtualLiteral");
        assertEquals("java/lang/invoke/MethodHandles$Lookup", handleLiteral.sinkOwnerInternalName());
        assertEquals("findVirtual", handleLiteral.sinkMethodName());
        assertEquals(1, handleLiteral.memberNameArgumentIndex());
        assertEquals(java.util.Optional.of("target"), handleLiteral.constantMemberName());
        assertTrue(evidenceFor(report, "findVirtualParameter").constantMemberName().isEmpty());
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

    private static SootUpAnalyzer.ReflectionSinkEvidence evidenceFor(
            SootUpAnalyzer.AnalysisReport report, String callerMethodPart) {
        return report.reflectionSinks().stream()
                .filter(evidence -> evidence.callerMethodSignature().contains(callerMethodPart))
                .findFirst().orElseThrow(() -> new AssertionError("Missing reflection evidence for " + callerMethodPart));
    }

    private static void writeReflectionFixture(Path jar) throws IOException {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "sample/Example", null, "java/lang/Object", null);

        MethodVisitor literal = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "literal", "()V", null, null);
        literal.visitCode();
        emitClassMethodLookup(literal, "target", true);
        literal.visitInsn(Opcodes.RETURN);
        literal.visitMaxs(3, 0);
        literal.visitEnd();

        MethodVisitor parameter = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "parameter", "(Ljava/lang/String;)V", null, null);
        parameter.visitCode();
        emitClassMethodLookup(parameter, null, true);
        parameter.visitInsn(Opcodes.RETURN);
        parameter.visitMaxs(3, 1);
        parameter.visitEnd();

        MethodVisitor derived = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "derived", "(Ljava/lang/String;)V", null, null);
        derived.visitCode();
        derived.visitLdcInsn(Type.getObjectType("sample/Example"));
        derived.visitLdcInsn("pre");
        derived.visitVarInsn(Opcodes.ALOAD, 0);
        derived.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat",
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        derived.visitInsn(Opcodes.ICONST_0);
        derived.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class");
        derived.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getMethod",
                "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);
        derived.visitInsn(Opcodes.POP);
        derived.visitInsn(Opcodes.RETURN);
        derived.visitMaxs(3, 1);
        derived.visitEnd();

        MethodVisitor findLiteral = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "findVirtualLiteral", "()V", null, null);
        findLiteral.visitCode();
        emitLookupMethod(findLiteral, "target", true);
        findLiteral.visitInsn(Opcodes.RETURN);
        findLiteral.visitMaxs(4, 0);
        findLiteral.visitEnd();

        MethodVisitor findParameter = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "findVirtualParameter", "(Ljava/lang/String;)V", null, null);
        findParameter.visitCode();
        emitLookupMethod(findParameter, null, true);
        findParameter.visitInsn(Opcodes.RETURN);
        findParameter.visitMaxs(4, 1);
        findParameter.visitEnd();

        writer.visitEnd();
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            put(output, "sample/Example.class", writer.toByteArray());
        }
    }

    private static void emitClassMethodLookup(MethodVisitor method, String name, boolean declared) {
        method.visitLdcInsn(Type.getObjectType("sample/Example"));
        if (name != null) method.visitLdcInsn(name);
        else method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class");
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class",
                declared ? "getDeclaredMethod" : "getMethod",
                "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);
        method.visitInsn(Opcodes.POP);
    }

    private static void emitLookupMethod(MethodVisitor method, String name, boolean virtual) {
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/invoke/MethodHandles", "lookup",
                "()Ljava/lang/invoke/MethodHandles$Lookup;", false);
        method.visitLdcInsn(Type.getObjectType("sample/Example"));
        if (name != null) method.visitLdcInsn(name);
        else method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitLdcInsn(Type.getMethodType("()V"));
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup",
                virtual ? "findVirtual" : "findStatic",
                "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false);
        method.visitInsn(Opcodes.POP);
    }

    private static byte[] simpleClassBytes(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitEnd();
        return writer.toByteArray();
    }
}
