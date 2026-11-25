package org.zartema;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class JarInspectorTest {
    @TempDir Path directory;

    @Test void reportsBaseAndVersionedClassesSeparately() throws Exception {
        Path jar = directory.resolve("input.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            put(output, "example/Thing.class", classBytes("example/Thing"));
            put(output, "META-INF/versions/21/example/Thing.class", classBytes("example/Thing"));
            put(output, "resource.txt", new byte[] {1});
        }
        var result = JarInspector.inspect(jar);
        assertEquals(2, result.classes().size());
        assertEquals("example/Thing", result.classes().get(0).internalName());
        assertTrue(result.classes().stream().anyMatch(c -> c.entryName().startsWith("META-INF/versions/21/")));
        assertEquals(1, result.resourceCount());
    }

    @Test void rejectsMalformedClass() throws Exception {
        Path jar = directory.resolve("bad.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
            put(output, "Bad.class", new byte[] {1, 2, 3});
        }
        assertThrows(IOException.class, () -> JarInspector.inspect(jar));
    }

    private static void put(JarOutputStream output, String name, byte[] bytes) throws IOException {
        output.putNextEntry(new JarEntry(name)); output.write(bytes); output.closeEntry();
    }

    private static byte[] classBytes(String name) {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        writer.visitEnd(); return writer.toByteArray();
    }
}
