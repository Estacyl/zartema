package org.zartema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.zartema.mapping.NameMapping;

class CliTest {
    @TempDir Path directory;

    @Test
    void renameCommandWritesMappingAndExecutableVerifiedJar() throws Exception {
        Path input = fixtureJar("input.jar");
        Path output = directory.resolve("output.jar");
        Path mapping = directory.resolve("mapping.zmap");

        ProcessResult cli = launch("rename-private-static", input.toString(), output.toString(),
                "--mapping", mapping.toString(), "--method", "fixture/App#secret()I",
                "--acknowledge-complete-direct-callers-and-no-name-sensitive-uses");

        assertEquals(0, cli.exitCode, cli.output);
        assertTrue(cli.output.contains("Renamed 1 private static method(s)."));
        assertTrue(cli.output.contains("Analyzed archive SHA-256:"));
        assertTrue(cli.output.contains("JVM load check: 1 classes"), cli.output);
        assertTrue(Files.isRegularFile(output));
        assertTrue(Files.isRegularFile(mapping));
        NameMapping.read(Files.newBufferedReader(mapping, StandardCharsets.UTF_8));
        ProcessResult execution = execute(output);
        assertEquals(0, execution.exitCode, execution.output);
        assertEquals("42" + System.lineSeparator(), execution.output);
    }

    @Test
    void refusesPublicationWhenJvmCannotResolveInputMemberType() throws Exception {
        Path input = directory.resolve("missing-type.jar");
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "fixture/App", null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "missing", "Lmissing/Dependency;", null, null).visitEnd();
        var method = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "secret", "()I", null, null);
        method.visitCode(); method.visitInsn(Opcodes.ICONST_1); method.visitInsn(Opcodes.IRETURN); method.visitMaxs(1, 0); method.visitEnd();
        writer.visitEnd();
        try (var jar = new JarOutputStream(Files.newOutputStream(input))) {
            jar.putNextEntry(new JarEntry("fixture/App.class")); jar.write(writer.toByteArray()); jar.closeEntry();
        }
        Path output = directory.resolve("rejected.jar");
        Path mapping = directory.resolve("rejected.zmap");
        ProcessResult result = launch("rename-private-static", input.toString(), output.toString(),
                "--mapping", mapping.toString(), "--method", "fixture/App#secret()I",
                "--acknowledge-complete-direct-callers-and-no-name-sensitive-uses");
        assertEquals(1, result.exitCode, result.output);
        assertTrue(result.output.contains("Input JVM load check failed"), result.output);
        assertFalse(Files.exists(output));
        assertFalse(Files.exists(mapping));
    }

    @Test
    void reflectionAnalysisKeepsSelectedMethodInCliWorkflow() throws Exception {
        Path input = directory.resolve("reflective.jar");
        try (var jar = new JarOutputStream(Files.newOutputStream(input))) {
            jar.putNextEntry(new JarEntry("fixture/App.class"));
            jar.write(fixtureClass(true));
            jar.closeEntry();
        }
        Path output = directory.resolve("reflective-output.jar");
        ProcessResult result = launch("rename-private-static", input.toString(), output.toString(),
                "--mapping", directory.resolve("reflective.zmap").toString(), "--method", "fixture/App#secret()I",
                "--acknowledge-complete-direct-callers-and-no-name-sensitive-uses");
        assertEquals(0, result.exitCode, result.output);
        assertTrue(result.output.contains("SootUp found a reflective member lookup"), result.output);
        assertEquals(execute(input), execute(output));
    }

    @Test
    void publicationDoesNotOverwriteAFileCreatedAfterPreflight() throws Exception {
        Path stage = directory.resolve("stage");
        Path destination = directory.resolve("destination");
        Files.writeString(stage, "new");
        Files.writeString(destination, "existing");
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> Main.publishNewFile(stage, destination));
        assertEquals("existing", Files.readString(destination));
        assertEquals("new", Files.readString(stage));
    }

    @Test
    void detectsChangesToVerifiedStagedArtifact() throws Exception {
        Path stage = directory.resolve("verified-stage");
        Files.writeString(stage, "verified bytes");
        byte[] digest = Main.sha256(stage);
        Main.requireUnchanged(stage, digest);
        Files.writeString(stage, "changed bytes");
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> Main.requireUnchanged(stage, digest));
    }

    @Test
    void keepMethodOptionPreservesSelectedMethodAndReportsIt() throws Exception {
        Path input = fixtureJar("keep-input.jar");
        Path output = directory.resolve("kept.jar");
        Path mapping = directory.resolve("kept.zmap");
        ProcessResult result = launch("rename-private-static", input.toString(), output.toString(),
                "--mapping", mapping.toString(), "--method", "fixture/App#secret()I",
                "--keep-method", "fixture/App#secret()I",
                "--acknowledge-complete-direct-callers-and-no-name-sensitive-uses");
        assertEquals(0, result.exitCode, result.output);
        assertTrue(result.output.contains("Kept 1"), result.output);
        assertEquals(execute(input).output, execute(output).output);
        try (var jar = new java.util.jar.JarFile(output.toFile())) {
            var reader = new org.objectweb.asm.ClassReader(jar.getInputStream(jar.getJarEntry("fixture/App.class")));
            var names = new java.util.ArrayList<String>();
            reader.accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
                @Override public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                    names.add(name); return null;
                }
            }, 0);
            assertTrue(names.contains("secret"), names.toString());
        }
    }

    @Test
    void keepClassOptionPreservesSelectedMethod() throws Exception {
        Path input = fixtureJar("class-keep.jar");
        Path output = directory.resolve("class-kept.jar");
        ProcessResult result = launch("rename-private-static", input.toString(), output.toString(),
                "--mapping", directory.resolve("class-kept.zmap").toString(), "--method", "fixture/App#secret()I",
                "--keep-class", "fixture/App", "--acknowledge-complete-direct-callers-and-no-name-sensitive-uses");
        assertEquals(0, result.exitCode, result.output);
        assertTrue(result.output.contains("Kept 1"), result.output);
        assertEquals(execute(input).output, execute(output).output);
    }

    @Test
    void inspectCommandRemainsAvailable() throws Exception {
        ProcessResult result = launch("inspect", fixtureJar("inspect.jar").toString());
        assertEquals(0, result.exitCode, result.output);
        assertTrue(result.output.contains("fixture/App.class  fixture/App"));
        assertTrue(result.output.contains("Classes: 1"));
    }

    @Test
    void inspectCommandAcceptsExplicitReleaseView() throws Exception {
        ProcessResult result = launch("inspect", fixtureJar("inspect-release.jar").toString(), "--release", "21");
        assertEquals(0, result.exitCode, result.output);
        assertTrue(result.output.contains("fixture/App.class <- fixture/App.class"), result.output);
        assertTrue(result.output.contains("Release: 21; multi-release: false"), result.output);
    }

    @Test
    void requiresAcknowledgmentSelectorAndMappingAndDoesNotPublishPartialOutput() throws Exception {
        Path input = fixtureJar("input.jar");
        Path output = directory.resolve("output.jar");
        Path mapping = directory.resolve("mapping.zmap");

        ProcessResult noAcknowledgment = launch("rename-private-static", input.toString(), output.toString(),
                "--mapping", mapping.toString(), "--method", "fixture/App#secret()I");
        assertEquals(2, noAcknowledgment.exitCode);
        assertTrue(noAcknowledgment.output.contains("acknowledge-complete-direct-callers-and-no-name-sensitive-uses"));
        assertFalse(Files.exists(output));
        assertFalse(Files.exists(mapping));

        ProcessResult noSelector = launch("rename-private-static", input.toString(), output.toString(),
                "--mapping", mapping.toString(), "--acknowledge-complete-direct-callers-and-no-name-sensitive-uses");
        assertEquals(2, noSelector.exitCode);
        assertFalse(Files.exists(output));
        assertFalse(Files.exists(mapping));

        ProcessResult noMapping = launch("rename-private-static", input.toString(), output.toString(),
                "--method", "fixture/App#secret()I", "--acknowledge-complete-direct-callers-and-no-name-sensitive-uses");
        assertEquals(2, noMapping.exitCode);
        assertFalse(Files.exists(output));
    }

    @Test
    void rejectsInputOutputAndOutputMappingAliasesIncludingSymlinks() throws Exception {
        Path input = fixtureJar("input.jar");
        Path mapping = directory.resolve("mapping.zmap");
        ProcessResult sameInput = launch("rename-private-static", input.toString(), input.toString(),
                "--mapping", mapping.toString(), "--method", "fixture/App#secret()I",
                "--acknowledge-complete-direct-callers-and-no-name-sensitive-uses");
        assertEquals(1, sameInput.exitCode);
        assertTrue(sameInput.output.contains("resolves to the input"));

        Path same = directory.resolve("same-path");
        ProcessResult outputMappingAlias = launch("rename-private-static", input.toString(), same.toString(),
                "--mapping", same.toString(), "--method", "fixture/App#secret()I",
                "--acknowledge-complete-direct-callers-and-no-name-sensitive-uses");
        assertEquals(1, outputMappingAlias.exitCode);
        assertTrue(outputMappingAlias.output.contains("same file"));

        Path inputAlias = directory.resolve("input-alias.jar");
        Files.createSymbolicLink(inputAlias, input.getFileName());
        ProcessResult symlinkAlias = launch("rename-private-static", input.toString(), inputAlias.toString(),
                "--mapping", mapping.toString(), "--method", "fixture/App#secret()I",
                "--acknowledge-complete-direct-callers-and-no-name-sensitive-uses");
        assertEquals(1, symlinkAlias.exitCode);
        assertTrue(symlinkAlias.output.contains("resolves to the input"));

        Path mappingAlias = directory.resolve("mapping-alias.zmap");
        Files.createSymbolicLink(mappingAlias, input.getFileName());
        ProcessResult symlinkMappingAlias = launch("rename-private-static", input.toString(),
                directory.resolve("other-output.jar").toString(), "--mapping", mappingAlias.toString(),
                "--method", "fixture/App#secret()I", "--acknowledge-complete-direct-callers-and-no-name-sensitive-uses");
        assertEquals(1, symlinkMappingAlias.exitCode);
        assertTrue(symlinkMappingAlias.output.contains("Mapping path resolves to the input"));

        Path shared = directory.resolve("shared-destination");
        Files.writeString(shared, "existing");
        Path outputAlias = directory.resolve("output-alias.jar");
        Files.createSymbolicLink(outputAlias, shared.getFileName());
        ProcessResult symlinkOutputMappingAlias = launch("rename-private-static", input.toString(), outputAlias.toString(),
                "--mapping", shared.toString(), "--method", "fixture/App#secret()I",
                "--acknowledge-complete-direct-callers-and-no-name-sensitive-uses");
        assertEquals(1, symlinkOutputMappingAlias.exitCode);
        assertTrue(symlinkOutputMappingAlias.output.contains("same file"));
        assertEquals("existing", Files.readString(shared));
    }

    @Test
    void reportsMappingPublicationFailureWithoutReportingSuccessOrLeavingOutput() throws Exception {
        Path input = fixtureJar("input.jar");
        Path output = directory.resolve("output.jar");
        Path mappingDirectory = Files.createDirectory(directory.resolve("mapping-directory"));

        ProcessResult result = launch("rename-private-static", input.toString(), output.toString(),
                "--mapping", mappingDirectory.toString(), "--method", "fixture/App#secret()I",
                "--acknowledge-complete-direct-callers-and-no-name-sensitive-uses");

        assertEquals(1, result.exitCode);
        assertFalse(result.output.contains("Renamed 1 private static method(s)."));
        assertFalse(Files.exists(output));
    }

    private Path fixtureJar(String name) throws Exception {
        Path jar = directory.resolve(name);
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("fixture/App.class"));
            output.write(fixtureClass());
            output.closeEntry();
        }
        return jar;
    }

    private static byte[] fixtureClass() {
        return fixtureClass(false);
    }

    private static byte[] fixtureClass(boolean reflection) {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "fixture/App", null, "java/lang/Object", null);
        var secret = writer.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "secret", "()I", null, null);
        secret.visitCode();
        secret.visitIntInsn(Opcodes.BIPUSH, 42);
        secret.visitInsn(Opcodes.IRETURN);
        secret.visitMaxs(1, 0);
        secret.visitEnd();
        var main = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
        main.visitCode();
        if (reflection) {
            main.visitLdcInsn(org.objectweb.asm.Type.getObjectType("fixture/App"));
            main.visitLdcInsn("secret");
            main.visitInsn(Opcodes.ICONST_0);
            main.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class");
            main.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getDeclaredMethod",
                    "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);
            main.visitInsn(Opcodes.POP);
        }
        main.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        main.visitMethodInsn(Opcodes.INVOKESTATIC, "fixture/App", "secret", "()I", false);
        main.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(I)V", false);
        main.visitInsn(Opcodes.RETURN);
        main.visitMaxs(reflection ? 3 : 2, 1);
        main.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ProcessResult execute(Path jar) throws Exception {
        return process(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xverify:all", "-cp", jar.toString(), "fixture.App");
    }

    private static ProcessResult launch(String... arguments) throws Exception {
        var command = new java.util.ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(Main.class.getName());
        command.addAll(java.util.List.of(arguments));
        return process(command.toArray(String[]::new));
    }

    private static ProcessResult process(String... command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(process.waitFor(), output);
    }

    private record ProcessResult(int exitCode, String output) { }
}
