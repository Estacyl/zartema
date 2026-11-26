package org.zartema.verify;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

class AsmClassValidatorTest {
    @Test void acceptsValidMethod() throws Exception {
        assertDoesNotThrow(() -> AsmClassValidator.validate(fixture(false)));
    }
    @Test void rejectsStackUnderflowAndTruncatedClass() {
        assertThrows(IOException.class, () -> AsmClassValidator.validate(fixture(true)));
        assertThrows(IOException.class, () -> AsmClassValidator.validate(new byte[] {1, 2, 3}));
    }
    private static byte[] fixture(boolean invalid) {
        var c = new ClassWriter(0);
        c.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "fixture/Check", null, "java/lang/Object", null);
        var m = c.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "value", "()I", null, null);
        m.visitCode();
        if (!invalid) m.visitInsn(Opcodes.ICONST_1);
        m.visitInsn(Opcodes.IRETURN); m.visitMaxs(1, 0); m.visitEnd(); c.visitEnd();
        return c.toByteArray();
    }
}
