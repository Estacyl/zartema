package org.zartema.mapping;

import static org.junit.jupiter.api.Assertions.*;
import java.io.StringReader;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

class NameMappingTest {
    @Test void mappingRoundTripIsDeterministicAndTranslatesTrace() throws Exception {
        var mapping = NameMapping.builder()
                .mapClass("example/Foo", "x/A")
                .mapMethod("example/Foo", "calculate", "(I)I", "a")
                .build();
        var out = new StringWriter();
        mapping.write(out);
        var parsed = NameMapping.read(new StringReader(out.toString()));
        var other = new StringWriter();
        parsed.write(other);
        assertEquals(out.toString(), other.toString());
        var frame = new StackTraceElement("loader", "module", "1", "x.A", "a", "Foo.java", 42);
        var translated = parsed.translate(frame);
        assertEquals("example.Foo", translated.getClassName());
        assertEquals("calculate", translated.getMethodName());
        assertEquals("loader", translated.getClassLoaderName());
        assertEquals("module", translated.getModuleName());
        assertEquals("1", translated.getModuleVersion());
        assertEquals("Foo.java", translated.getFileName());
        assertEquals(42, translated.getLineNumber());
    }

    @Test void refusesAmbiguousStackTraceMethodNames() {
        var mapping = NameMapping.builder().mapClass("example/Foo", "x/A")
                .mapMethod("example/Foo", "first", "()V", "a")
                .mapMethod("example/Foo", "second", "(I)V", "a").build();
        assertThrows(IllegalArgumentException.class, () ->
                mapping.translate(new StackTraceElement("x.A", "a", "Foo.java", 5)));
    }

    @Test void rejectsInvalidDescriptorsAndSpecialMethodRenames() {
        assertThrows(IllegalArgumentException.class, () -> NameMapping.builder().mapMethod("a/A", "f", "()Vjunk", "x"));
        assertThrows(IllegalArgumentException.class, () -> NameMapping.builder().mapMethod("a/A", "f", "(V)V", "x"));
        assertThrows(IllegalArgumentException.class, () -> NameMapping.builder().mapMethod("a/A", "<init>", "()V", "x"));
        assertThrows(IllegalArgumentException.class, () -> NameMapping.builder().mapClass("bad/\ud800", "x/X"));
    }

    @Test void rejectsDescriptorsExceedingJvmParameterSlotLimit() {
        assertDoesNotThrow(() -> NameMapping.builder().mapMethod("a/A", "f", "(" + "I".repeat(255) + ")V", "x"));
        assertThrows(IllegalArgumentException.class, () -> NameMapping.builder().mapMethod("a/A", "f", "(" + "I".repeat(254) + "J)V", "x"));
    }

    @Test void rejectsDuplicateOutputClassAndMalformedMapping() {
        assertThrows(IllegalArgumentException.class, () -> NameMapping.builder()
                .mapClass("a/A", "x/X").mapClass("b/B", "x/X").build());
        assertThrows(java.io.IOException.class, () -> NameMapping.read(new StringReader("zartema-mapping-v1\nC\t!\t!\n")));
    }
}
