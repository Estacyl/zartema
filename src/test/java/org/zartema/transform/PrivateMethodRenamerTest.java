package org.zartema.transform;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.zartema.archive.JarArchive;

class PrivateMethodRenamerTest {
    @TempDir Path directory;

    @Test void renamesPrivateStaticMethodAndPreservesExecutedBehavior() throws Exception {
        Path input = directory.resolve("input.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(input))) {
            out.putNextEntry(new JarEntry("fixture/App.class")); out.write(fixture()); out.closeEntry();
        }
        var selector = new PrivateMethodRenamer.MethodSelector("fixture/App", "secret", "()I");
        var result = PrivateMethodRenamer.rename(JarArchive.read(input), List.of(selector), true);
        Path output = directory.resolve("output.jar");
        result.archive().writeDeterministic(output);
        assertEquals(execute(input), execute(output));
        assertEquals("42", execute(output).strip());
        var restored = result.mapping().translate(new StackTraceElement("fixture.App", "$z0", "App.java", 12));
        assertEquals("secret", restored.getMethodName());
    }

    @Test void rewritesMethodHandleConstantsAndProducesReproducibleOutput() throws Exception {
        Path input = directory.resolve("input.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(input))) {
            out.putNextEntry(new JarEntry("fixture/App.class")); out.write(fixture(true)); out.closeEntry();
        }
        var selector = new PrivateMethodRenamer.MethodSelector("fixture/App", "secret", "()I");
        var result = PrivateMethodRenamer.rename(JarArchive.read(input), List.of(selector), true);
        Path first = directory.resolve("first.jar"), second = directory.resolve("second.jar");
        result.archive().writeDeterministic(first);
        PrivateMethodRenamer.rename(JarArchive.read(input), List.of(selector), true).archive().writeDeterministic(second);
        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
        assertEquals(execute(input), execute(first));
        assertEquals("42", execute(first).strip());
    }

    @Test void refusesAmbiguousSymbolicOwnerReferences() throws Exception {
        Path input = directory.resolve("alias.jar");
        var owner = new ClassWriter(0);
        owner.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "fixture/A", null, "java/lang/Object", null);
        var target = owner.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "secret", "()I", null, null);
        target.visitCode(); target.visitInsn(Opcodes.ICONST_1); target.visitInsn(Opcodes.IRETURN); target.visitMaxs(1, 0); target.visitEnd();
        var caller = owner.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "caller", "()I", null, null);
        caller.visitCode(); caller.visitMethodInsn(Opcodes.INVOKESTATIC, "fixture/B", "secret", "()I", false);
        caller.visitInsn(Opcodes.IRETURN); caller.visitMaxs(1, 0); caller.visitEnd(); owner.visitEnd();
        var child = new ClassWriter(0); child.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "fixture/B", null, "fixture/A", null); child.visitEnd();
        try (var out = new JarOutputStream(Files.newOutputStream(input))) {
            out.putNextEntry(new JarEntry("fixture/A.class")); out.write(owner.toByteArray()); out.closeEntry();
            out.putNextEntry(new JarEntry("fixture/B.class")); out.write(child.toByteArray()); out.closeEntry();
        }
        assertThrows(IOException.class, () -> PrivateMethodRenamer.rename(JarArchive.read(input),
                List.of(new PrivateMethodRenamer.MethodSelector("fixture/A", "secret", "()I")), true));
    }

    @Test void rejectsSelectedClassWithNestMemberOutsideTheArchive() throws Exception {
        Path input = directory.resolve("nest.jar");
        var owner = new ClassWriter(0);
        owner.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "fixture/Host", null, "java/lang/Object", null);
        owner.visitNestMember("fixture/External");
        var target = owner.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "secret", "()I", null, null);
        target.visitCode(); target.visitInsn(Opcodes.ICONST_1); target.visitInsn(Opcodes.IRETURN); target.visitMaxs(1, 0); target.visitEnd(); owner.visitEnd();
        try (var out = new JarOutputStream(Files.newOutputStream(input))) {
            out.putNextEntry(new JarEntry("fixture/Host.class")); out.write(owner.toByteArray()); out.closeEntry();
        }
        assertThrows(IOException.class, () -> PrivateMethodRenamer.rename(JarArchive.read(input),
                List.of(new PrivateMethodRenamer.MethodSelector("fixture/Host", "secret", "()I")), true));
    }

    @Test void keepRulesOverrideExplicitSelectionAndReportReasons() throws Exception {
        Path input = directory.resolve("keep.jar");
        byte[] original = fixture();
        try (var out = new JarOutputStream(Files.newOutputStream(input))) {
            out.putNextEntry(new JarEntry("fixture/App.class")); out.write(original); out.closeEntry();
        }
        var selector = new PrivateMethodRenamer.MethodSelector("fixture/App", "secret", "()I");
        var rules = org.zartema.transform.keep.KeepRuleSet.builder()
                .keepMethod("fixture/App", "secret", "()I", "reflective contract").build();
        var result = PrivateMethodRenamer.rename(JarArchive.read(input), List.of(selector), true, rules);
        assertFalse(result.decisions().getFirst().renamed());
        assertEquals(List.of("reflective contract"), result.decisions().getFirst().reasons());
        assertArrayEquals(original, result.archive().entries().getFirst().content());
        Path output = directory.resolve("kept.jar"); result.archive().writeDeterministic(output);
        assertEquals(execute(input), execute(output));
        assertEquals("secret", result.mapping().translate(new StackTraceElement("fixture.App", "secret", "App.java", 1)).getMethodName());
    }

    @Test void keepsOneSelectedMethodWhileRenamingAnother() throws Exception {
        Path input = directory.resolve("mixed.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(input))) {
            out.putNextEntry(new JarEntry("fixture/App.class")); out.write(fixture()); out.closeEntry();
        }
        var selected = List.of(new PrivateMethodRenamer.MethodSelector("fixture/App", "secret", "()I"),
                new PrivateMethodRenamer.MethodSelector("fixture/App", "other", "()I"));
        var rules = org.zartema.transform.keep.KeepRuleSet.builder()
                .keepMethod("fixture/App", "secret", "()I", "external name").build();
        var result = PrivateMethodRenamer.rename(JarArchive.read(input), selected, true, rules);
        assertEquals(1, result.decisions().stream().filter(PrivateMethodRenamer.Decision::renamed).count());
        var names = new java.util.HashSet<String>();
        new org.objectweb.asm.ClassReader(result.archive().entries().getFirst().content())
                .accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
                    @Override public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                        names.add(name); return null;
                    }
                }, 0);
        assertTrue(names.contains("secret"));
        assertFalse(names.contains("other"));
        assertTrue(names.contains("$z0"));
        assertEquals("other", result.mapping().translate(new StackTraceElement("fixture.App", "$z0", "App.java", 1)).getMethodName());
        Path output = directory.resolve("mixed-output.jar"); result.archive().writeDeterministic(output);
        assertEquals(execute(input), execute(output));
    }

    @Test void requiresExplicitAssumptionAndRejectsMissingTargets() throws Exception {
        Path input = directory.resolve("input.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(input))) {
            out.putNextEntry(new JarEntry("fixture/App.class")); out.write(fixture()); out.closeEntry();
        }
        var archive = JarArchive.read(input);
        var selector = new PrivateMethodRenamer.MethodSelector("fixture/App", "secret", "()I");
        assertThrows(IllegalArgumentException.class, () -> PrivateMethodRenamer.rename(archive, List.of(selector), false));
        assertThrows(IOException.class, () -> PrivateMethodRenamer.rename(archive,
                List.of(new PrivateMethodRenamer.MethodSelector("fixture/App", "missing", "()V")), true));
        assertThrows(IOException.class, () -> PrivateMethodRenamer.rename(archive,
                List.of(new PrivateMethodRenamer.MethodSelector("fixture/App", "main", "([Ljava/lang/String;)V")), true));
    }

    private static String execute(Path jar) throws Exception {
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xverify:all", "-cp", jar.toString(), "fixture.App").redirectErrorStream(true).start();
        String text = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), text);
        return text;
    }

    private static byte[] fixture() { return fixture(false); }

    private static byte[] fixture(boolean handle) {
        var c = new ClassWriter(0);
        c.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "fixture/App", null, "java/lang/Object", null);
        var secret = c.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "secret", "()I", null, null);
        secret.visitCode(); secret.visitIntInsn(Opcodes.BIPUSH, 42); secret.visitInsn(Opcodes.IRETURN);
        secret.visitMaxs(1, 0); secret.visitEnd();
        var other = c.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "other", "()I", null, null);
        other.visitCode(); other.visitInsn(Opcodes.ICONST_0); other.visitInsn(Opcodes.IRETURN); other.visitMaxs(1, 0); other.visitEnd();
        var main = c.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
        main.visitCode(); main.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        if (handle) {
            main.visitLdcInsn(new org.objectweb.asm.Handle(Opcodes.H_INVOKESTATIC, "fixture/App", "secret", "()I", false));
            main.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "invokeExact", "()I", false);
        } else {
            main.visitMethodInsn(Opcodes.INVOKESTATIC, "fixture/App", "secret", "()I", false);
        }
        main.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitInsn(Opcodes.RETURN); main.visitMaxs(2, 1); main.visitEnd(); c.visitEnd();
        return c.toByteArray();
    }
}
