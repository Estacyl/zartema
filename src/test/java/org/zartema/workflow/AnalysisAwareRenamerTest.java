package org.zartema.workflow;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
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
import org.zartema.transform.PrivateMethodRenamer;
import org.zartema.transform.keep.KeepRuleSet;

class AnalysisAwareRenamerTest {
    @TempDir Path directory;
    private final PrivateMethodRenamer.MethodSelector target =
            new PrivateMethodRenamer.MethodSelector("example/App", "secret", "()I");

    @Test void selectedResolvedMethodIsRenamedAndExecuted() throws Exception {
        Path input = fixture("java/lang/Object");
        var result = AnalysisAwareRenamer.rename(JarArchive.read(input), List.of(target), true, KeepRuleSet.builder().build());
        assertTrue(result.transform().decisions().getFirst().renamed());
        assertEquals(64, result.analysis().snapshotSha256().length());
        Path output = directory.resolve("out.jar"); result.transform().archive().writeDeterministic(output);
        assertEquals(execute(input), execute(output));
        assertEquals("7", execute(output).strip());
    }

    @Test void unresolvedOwnerHierarchyCausesKeepDecision() throws Exception {
        Path input = fixture("external/Missing");
        var result = AnalysisAwareRenamer.rename(JarArchive.read(input), List.of(target), true, KeepRuleSet.builder().build());
        assertFalse(result.transform().decisions().getFirst().renamed());
        assertTrue(result.transform().decisions().getFirst().reasons().stream().anyMatch(reason -> reason.contains("WALA")),
                result.transform().decisions().toString());
    }

    @Test void changedArchiveCreatesNewAnalysisIdentity() throws Exception {
        JarArchive complete = JarArchive.read(fixture("java/lang/Object"));
        JarArchive missing = JarArchive.read(fixture("external/Missing"));
        var first = AnalysisAwareRenamer.rename(complete, List.of(target), true, KeepRuleSet.builder().build());
        var second = AnalysisAwareRenamer.rename(missing, List.of(target), true, KeepRuleSet.builder().build());
        assertNotEquals(first.identity().archiveSha256(), second.identity().archiveSha256());
        assertTrue(first.transform().decisions().getFirst().renamed());
        assertFalse(second.transform().decisions().getFirst().renamed());
        assertEquals(first.identity().runtimeImageSha256(), second.identity().runtimeImageSha256());
    }

    @Test void reflectiveLookupKeepsMatchingOrUnknownMethodNames() throws Exception {
        for (boolean unknown : List.of(false, true)) {
            Path input = fixture("java/lang/Object", true, unknown);
            var result = AnalysisAwareRenamer.rename(JarArchive.read(input), List.of(target), true, KeepRuleSet.builder().build());
            assertFalse(result.analysis().sootUp().reflectionSinks().isEmpty());
            assertFalse(result.transform().decisions().getFirst().renamed());
            assertTrue(result.transform().decisions().getFirst().reasons().stream().anyMatch(reason -> reason.contains("SootUp")));
            Path output = directory.resolve(unknown ? "unknown.jar" : "literal.jar");
            result.transform().archive().writeDeterministic(output);
            assertEquals(execute(input), execute(output));
        }
    }

    private Path fixture(String parent) throws Exception {
        return fixture(parent, false, false);
    }

    private Path fixture(String parent, boolean reflection, boolean unknown) throws Exception {
        Path jar = directory.resolve("input.jar");
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "example/App", null, parent, null);
        var secret = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "secret", "()I", null, null);
        secret.visitCode(); secret.visitIntInsn(Opcodes.BIPUSH, 7); secret.visitInsn(Opcodes.IRETURN); secret.visitMaxs(1, 0); secret.visitEnd();
        var main = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
        main.visitCode();
        if (reflection) {
            main.visitLdcInsn(org.objectweb.asm.Type.getObjectType("example/App"));
            if (unknown) {
                main.visitLdcInsn("sec"); main.visitLdcInsn("ret");
                main.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;", false);
            } else main.visitLdcInsn("secret");
            main.visitInsn(Opcodes.ICONST_0); main.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class");
            main.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getDeclaredMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);
            main.visitInsn(Opcodes.POP);
        }
        main.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitMethodInsn(Opcodes.INVOKESTATIC, "example/App", "secret", "()I", false);
        main.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitInsn(Opcodes.RETURN); main.visitMaxs(reflection ? 3 : 2, 1); main.visitEnd(); writer.visitEnd();
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("example/App.class")); out.write(writer.toByteArray()); out.closeEntry();
        }
        return jar;
    }
    private String execute(Path jar) throws Exception {
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xverify:all", "-cp", jar.toString(), "example.App").redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output); return output;
    }
}
