package org.zartema.bytecode.hierarchy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class AsmTypeHierarchyTest {
    @Test
    void resolvesApplicationAndDependencyInheritanceWithoutClassLoading() {
        AsmTypeHierarchy hierarchy = AsmTypeHierarchy.builder()
                .addApplicationClass(classBytes("sample/Parent", "java/lang/Object", null, false))
                .addApplicationClass(classBytes("sample/Child", "sample/Parent", null, false))
                .addDependencyClass(interfaceBytes("lib/Named"))
                .addApplicationClass(classBytes("sample/NamedChild", "sample/Parent", new String[] {"lib/Named"}, false))
                .build();

        assertEquals(AsmTypeHierarchy.Answer.YES, hierarchy.isAssignableFrom("sample/Parent", "sample/Child").answer());
        assertEquals(AsmTypeHierarchy.Answer.YES, hierarchy.isAssignableFrom("lib/Named", "sample/NamedChild").answer());
        assertEquals("sample/Parent", hierarchy.commonSuperClass("sample/Parent", "sample/Child").internalName());
    }

    @Test
    void reportsUnknownForMissingTypesAndDoesNotGuessACommonSuperclass() {
        AsmTypeHierarchy hierarchy = AsmTypeHierarchy.builder()
                .addApplicationClass(classBytes("sample/Child", "missing/Dependency", null, false))
                .addApplicationClass(classBytes("sample/Other", "missing/Dependency", null, false))
                .build();

        assertEquals(AsmTypeHierarchy.Answer.UNKNOWN,
                hierarchy.isAssignableFrom("sample/Parent", "sample/Child").answer());
        assertEquals(AsmTypeHierarchy.Answer.UNKNOWN,
                hierarchy.commonSuperClass("sample/Child", "sample/Other").answer());
    }

    @Test
    void applicationDefinitionShadowsDependencyAndConflictingDefinitionsAreUnknown() {
        byte[] appClass = classBytes("sample/Type", "java/lang/Object", null, false);
        byte[] depClass = classBytes("sample/Type", "missing/Dependency", null, false);
        AsmTypeHierarchy applicationWins = AsmTypeHierarchy.builder()
                .addDependencyClass(depClass)
                .addApplicationClass(appClass)
                .build();
        assertEquals("java/lang/Object", applicationWins.lookup("sample/Type").classInfo().superclassInternalName());

        AsmTypeHierarchy ambiguous = AsmTypeHierarchy.builder()
                .addDependencyClass(depClass)
                .addDependencyClass(appClass)
                .build();
        assertEquals(AsmTypeHierarchy.Answer.UNKNOWN, ambiguous.lookup("sample/Type").answer());
        assertEquals(AsmTypeHierarchy.Answer.UNKNOWN,
                ambiguous.isAssignableFrom("sample/Other", "sample/Type").answer());
    }

    @Test
    void ambiguousResolutionDoesNotDependOnDependencyInsertionOrder() {
        byte[] first = classBytes("sample/Type", "java/lang/Object", null, false);
        byte[] second = classBytes("sample/Type", "missing/Parent", null, false);
        var left = AsmTypeHierarchy.builder().addDependencyClass(first).addDependencyClass(second).build();
        var right = AsmTypeHierarchy.builder().addDependencyClass(second).addDependencyClass(first).build();

        assertEquals(left.lookup("sample/Type"), right.lookup("sample/Type"));
    }

    @Test
    void byteDistinctSameTierDefinitionsAreAmbiguousEvenWhenHierarchyMatches() {
        byte[] first = classBytes("sample/Type", "java/lang/Object", null, false);
        ClassWriter variant = new ClassWriter(0);
        variant.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "sample/Type", null, "java/lang/Object", null);
        variant.visitField(Opcodes.ACC_PUBLIC, "extra", "I", null, null).visitEnd();
        variant.visitEnd();

        var hierarchy = AsmTypeHierarchy.builder().addDependencyClass(first)
                .addDependencyClass(variant.toByteArray()).build();
        assertEquals(AsmTypeHierarchy.Answer.UNKNOWN, hierarchy.lookup("sample/Type").answer());
    }

    @Test
    void sharedInterfaceJoinUsesCommonClassFrameAndVerifiesOnJvm() throws Exception {
        AsmTypeHierarchy hierarchy = AsmTypeHierarchy.builder()
                .addApplicationClass(interfaceWithValue("sample/Valued"))
                .addApplicationClass(implementation("sample/First", "sample/Valued", 1))
                .addApplicationClass(implementation("sample/Second", "sample/Valued", 2))
                .build();
        assertEquals("java/lang/Object", hierarchy.commonSuperClass("sample/First", "sample/Second").internalName());
        AsmHierarchyClassWriter writer = new AsmHierarchyClassWriter(ClassWriter.COMPUTE_FRAMES, hierarchy);
        byte[] factory = writeInterfaceBranchMerge(writer);
        Path directory = Files.createTempDirectory("zartema-interface-verify-");
        writeClass(directory, "sample/Factory", factory);
        writeClass(directory, "sample/Valued", interfaceWithValue("sample/Valued"));
        writeClass(directory, "sample/First", implementation("sample/First", "sample/Valued", 1));
        writeClass(directory, "sample/Second", implementation("sample/Second", "sample/Valued", 2));
        String classpath = directory + System.getProperty("path.separator") + System.getProperty("java.class.path");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xverify:all", "-cp", classpath, InterfaceFrameVerifierMain.class.getName())
                .redirectErrorStream(true).start();
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "JVM verifier child process timed out");
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.exitValue(), output);
    }

    @Test
    void cyclicHierarchyDoesNotProducePositiveAssignability() {
        AsmTypeHierarchy hierarchy = AsmTypeHierarchy.builder()
                .addApplicationClass(classBytes("sample/CycleA", "sample/CycleB", null, false))
                .addApplicationClass(classBytes("sample/CycleB", "sample/CycleA", null, false))
                .build();
        assertEquals(AsmTypeHierarchy.Answer.UNKNOWN,
                hierarchy.isAssignableFrom("sample/CycleA", "sample/CycleB").answer());
    }

    @Test
    void identicalMalformedArrayDescriptorsAreUnknown() {
        AsmTypeHierarchy hierarchy = AsmTypeHierarchy.builder().build();
        assertEquals(AsmTypeHierarchy.Answer.UNKNOWN, hierarchy.commonSuperClass("[V", "[V").answer());
        assertEquals(AsmTypeHierarchy.Answer.UNKNOWN, hierarchy.commonSuperClass("[Lbad.name;", "[Lbad.name;").answer());
    }

    @Test
    void arrayMergePreservesComponentTypeAndVerifiesAaloadOnJvm() throws Exception {
        var hierarchy = AsmTypeHierarchy.builder()
                .addApplicationClass(classBytes("sample/Base", "java/lang/Object", null, false))
                .addApplicationClass(classBytes("sample/Text", "sample/Base", null, false))
                .addApplicationClass(classBytes("sample/NumberLike", "sample/Base", null, false))
                .build();
        assertEquals("[Lsample/Base;", hierarchy.commonSuperClass("[Lsample/Text;", "[Lsample/NumberLike;").internalName());

        AsmHierarchyClassWriter writer = new AsmHierarchyClassWriter(ClassWriter.COMPUTE_FRAMES, hierarchy);
        byte[] factory = writeArrayBranchMerge(writer);
        Path directory = Files.createTempDirectory("zartema-array-verify-");
        writeClass(directory, "sample/Factory", factory);
        writeClass(directory, "sample/Base", classBytes("sample/Base", "java/lang/Object", null, false));
        writeClass(directory, "sample/Text", classBytes("sample/Text", "sample/Base", null, false));
        writeClass(directory, "sample/NumberLike", classBytes("sample/NumberLike", "sample/Base", null, false));
        String classpath = directory + System.getProperty("path.separator") + System.getProperty("java.class.path");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xverify:all", "-cp", classpath, ArrayFrameVerifierMain.class.getName())
                .redirectErrorStream(true).start();
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), "JVM verifier child process timed out");
        String output = new String(process.getInputStream().readAllBytes());
        assertEquals(0, process.exitValue(), output);
    }

    private static void writeClass(Path root, String internalName, byte[] bytes) throws Exception {
        Path path = root.resolve(internalName + ".class");
        Files.createDirectories(path.getParent());
        Files.write(path, bytes);
    }

    private static byte[] writeArrayBranchMerge(ClassWriter writer) {
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "sample/Factory", null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "chooseElement",
                "(Z)Ljava/lang/Object;", null, null);
        method.visitCode();
        var right = new org.objectweb.asm.Label();
        var end = new org.objectweb.asm.Label();
        method.visitVarInsn(Opcodes.ILOAD, 0);
        method.visitJumpInsn(Opcodes.IFEQ, right);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitTypeInsn(Opcodes.ANEWARRAY, "sample/Text");
        method.visitJumpInsn(Opcodes.GOTO, end);
        method.visitLabel(right);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitTypeInsn(Opcodes.ANEWARRAY, "sample/NumberLike");
        method.visitLabel(end);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitInsn(Opcodes.AALOAD);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] writeInterfaceBranchMerge(ClassWriter writer) {
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "sample/Factory", null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "call", "(Z)I", null, null);
        method.visitCode();
        var second = new org.objectweb.asm.Label();
        var join = new org.objectweb.asm.Label();
        method.visitVarInsn(Opcodes.ILOAD, 0);
        method.visitJumpInsn(Opcodes.IFEQ, second);
        instantiate(method, "sample/First");
        method.visitJumpInsn(Opcodes.GOTO, join);
        method.visitLabel(second);
        instantiate(method, "sample/Second");
        method.visitLabel(join);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "sample/Valued", "value", "()I", true);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] interfaceWithValue(String name) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                name, null, "java/lang/Object", null);
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "value", "()I", null, null).visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] implementation(String name, String iface, int value) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", new String[] {iface});
        var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC, "value", "()I", null, null);
        method.visitCode();
        method.visitIntInsn(Opcodes.BIPUSH, value);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    @Test
    void writerFailsLoudlyWhenFrameMergeCannotBeResolved() {
        AsmTypeHierarchy hierarchy = AsmTypeHierarchy.builder()
                .addApplicationClass(classBytes("sample/Left", "missing/Base", null, false))
                .addApplicationClass(classBytes("sample/Right", "missing/Base", null, false))
                .build();
        AsmHierarchyClassWriter writer = new AsmHierarchyClassWriter(ClassWriter.COMPUTE_FRAMES, hierarchy);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> writeBranchMerge(writer));
        assertTrue(failure.getMessage().contains("unresolved"));
    }

    @Test
    void computesFramesThroughTheExplicitHierarchyWriter() throws Exception {
        AsmTypeHierarchy hierarchy = AsmTypeHierarchy.builder()
                .addApplicationClass(classBytes("sample/Base", "java/lang/Object", null, false))
                .addApplicationClass(classBytes("sample/Left", "sample/Base", null, false))
                .addApplicationClass(classBytes("sample/Right", "sample/Base", null, false))
                .build();
        AsmHierarchyClassWriter writer = new AsmHierarchyClassWriter(ClassWriter.COMPUTE_FRAMES, hierarchy);
        byte[] factoryBytes = writeBranchMerge(writer);

        ByteArrayClassLoader loader = new ByteArrayClassLoader(Map.of(
                "sample.Factory", factoryBytes,
                "sample.Base", classBytes("sample/Base", "java/lang/Object", null, false),
                "sample.Left", classBytes("sample/Left", "sample/Base", null, false),
                "sample.Right", classBytes("sample/Right", "sample/Base", null, false)));
        Class<?> factory = loader.loadClass("sample.Factory");
        var choose = factory.getMethod("choose", boolean.class);
        assertEquals("sample.Left", choose.invoke(null, true).getClass().getName());
        assertEquals("sample.Right", choose.invoke(null, false).getClass().getName());
    }

    private static byte[] writeBranchMerge(ClassWriter writer) {
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "sample/Factory", null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "choose", "(Z)Ljava/lang/Object;", null, null);
        method.visitCode();
        var right = new org.objectweb.asm.Label();
        var end = new org.objectweb.asm.Label();
        method.visitVarInsn(Opcodes.ILOAD, 0);
        method.visitJumpInsn(Opcodes.IFEQ, right);
        instantiate(method, "sample/Left");
        method.visitJumpInsn(Opcodes.GOTO, end);
        method.visitLabel(right);
        instantiate(method, "sample/Right");
        method.visitLabel(end);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void instantiate(org.objectweb.asm.MethodVisitor method, String internalName) {
        method.visitTypeInsn(Opcodes.NEW, internalName);
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, internalName, "<init>", "()V", false);
    }

    private static byte[] classBytes(String name, String superclass, String[] interfaces, boolean isInterface) {
        ClassWriter writer = new ClassWriter(0);
        int access = Opcodes.ACC_PUBLIC | (isInterface ? Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT : 0);
        writer.visit(Opcodes.V21, access, name, null, superclass, interfaces);
        if (!isInterface) {
            var constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            constructor.visitCode();
            constructor.visitVarInsn(Opcodes.ALOAD, 0);
            constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, superclass, "<init>", "()V", false);
            constructor.visitInsn(Opcodes.RETURN);
            constructor.visitMaxs(1, 1);
            constructor.visitEnd();
        }
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

    private static final class ByteArrayClassLoader extends ClassLoader {
        private final Map<String, byte[]> classes;

        private ByteArrayClassLoader(Map<String, byte[]> classes) {
            super(ClassLoader.getPlatformClassLoader());
            this.classes = classes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes == null) throw new ClassNotFoundException(name);
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

}
