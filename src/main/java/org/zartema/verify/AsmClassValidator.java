package org.zartema.verify;

import java.io.IOException;
import java.util.Objects;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.util.CheckClassAdapter;

/** Checks ASM's class-file visitor rules and local data-flow constraints, not JVM linkage. */
public final class AsmClassValidator {
    private AsmClassValidator() { }

    /**
     * Rejects malformed input and ASM-detected structural or basic data-flow errors.
     * This is not a complete JVM verifier, a hierarchy-aware check, or a behavioral proof.
     * The caller must independently verify and execute output on the target JVM.
     *
     * @param bytes class-file bytes
     * @throws IOException if the class fails the checks
     */
    public static void validate(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length < 4 || bytes[0] != (byte) 0xca || bytes[1] != (byte) 0xfe
                || bytes[2] != (byte) 0xba || bytes[3] != (byte) 0xbe) {
            throw new IOException("Invalid class-file magic");
        }
        try {
            ClassReader reader = new ClassReader(bytes);
            reader.accept(new CheckClassAdapter(new ClassWriter(0), true), ClassReader.EXPAND_FRAMES);
        } catch (RuntimeException exception) {
            throw new IOException("ASM rejected class structure or data flow", exception);
        }
    }
}
