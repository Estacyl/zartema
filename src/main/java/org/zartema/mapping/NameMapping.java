package org.zartema.mapping;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable name mapping. The producer must include identity mappings for unchanged classes
 * when checking output-name collisions and entries for unchanged methods in participating classes
 * when translating traces. A stack trace does not carry descriptors, so missing inventory may make
 * an apparently unique translation wrong. Invalid input or a known ambiguity is rejected. */
public final class NameMapping {
    private static final String HEADER = "zartema-mapping-v1";
    private record Method(String owner, String original, String descriptor, String renamed) { }
    private final Map<String, String> classes;
    private final Map<String, String> reverseClasses;
    private final List<Method> methods;

    private NameMapping(Map<String, String> classes, List<Method> methods) {
        this.classes = Map.copyOf(classes);
        this.methods = List.copyOf(methods);
        var reverse = new HashMap<String, String>();
        classes.forEach((original, renamed) -> {
            if (reverse.putIfAbsent(renamed, original) != null)
                throw new IllegalArgumentException("Duplicate output class: " + renamed);
        });
        reverseClasses = Map.copyOf(reverse);
        var originalKeys = new java.util.HashSet<List<String>>();
        var outputKeys = new java.util.HashSet<List<String>>();
        for (Method m : methods) {
            if (!originalKeys.add(List.of(m.owner, m.original, m.descriptor)))
                throw new IllegalArgumentException("Duplicate input method");
            if (!outputKeys.add(List.of(classes.getOrDefault(m.owner, m.owner), m.renamed, m.descriptor)))
                throw new IllegalArgumentException("Duplicate output method");
        }
    }

    public static Builder builder() { return new Builder(); }

    /** Builder uses JVM internal class names and JVM method descriptors. */
    public static final class Builder {
        private final Map<String, String> classes = new HashMap<>();
        private final List<Method> methods = new ArrayList<>();
        public Builder mapClass(String original, String renamed) {
            checkClass(original); checkClass(renamed);
            if (classes.putIfAbsent(original, renamed) != null)
                throw new IllegalArgumentException("Duplicate input class: " + original);
            return this;
        }
        public Builder mapMethod(String owner, String original, String descriptor, String renamed) {
            checkClass(owner); checkMethodName(original); checkMethodName(renamed);
            checkDescriptor(descriptor);
            if ((original.startsWith("<") || renamed.startsWith("<")) && !original.equals(renamed))
                throw new IllegalArgumentException("Special methods cannot be renamed");
            if (original.equals("<init>") && !descriptor.endsWith(")V") || original.equals("<clinit>") && !descriptor.equals("()V"))
                throw new IllegalArgumentException("Invalid special method descriptor");
            methods.add(new Method(owner, original, descriptor, renamed));
            return this;
        }
        public NameMapping build() { return new NameMapping(classes, methods); }
    }

    /** Translate a Java stack frame, failing if its method name is ambiguous without a descriptor. */
    public StackTraceElement translate(StackTraceElement frame) {
        Objects.requireNonNull(frame, "frame");
        String outputOwner = frame.getClassName().replace('.', '/');
        String originalOwner = reverseClasses.getOrDefault(outputOwner, outputOwner);
        String originalMethod = null;
        for (Method method : methods) {
            if (method.owner.equals(originalOwner) && method.renamed.equals(frame.getMethodName())) {
                if (originalMethod != null && !originalMethod.equals(method.original))
                    throw new IllegalArgumentException("Ambiguous stack frame: " + frame);
                originalMethod = method.original;
            }
        }
        return new StackTraceElement(frame.getClassLoaderName(), frame.getModuleName(), frame.getModuleVersion(),
                originalOwner.replace('/', '.'), originalMethod == null ? frame.getMethodName() : originalMethod,
                frame.getFileName(), frame.getLineNumber());
    }

    /** Write a stable UTF-8-compatible, line-oriented mapping; the caller owns the writer. */
    public void write(Writer out) throws IOException {
        out.write(HEADER + "\n");
        for (var entry : classes.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList())
            out.write("C\t" + encode(entry.getKey()) + "\t" + encode(entry.getValue()) + "\n");
        for (Method m : methods.stream().sorted(Comparator.comparing(Method::owner)
                .thenComparing(Method::original).thenComparing(Method::descriptor).thenComparing(Method::renamed)).toList())
            out.write("M\t" + encode(m.owner) + "\t" + encode(m.original) + "\t" + encode(m.descriptor) + "\t" + encode(m.renamed) + "\n");
    }

    /** Read a versioned mapping. Malformed entries and conflicting mappings are rejected. */
    public static NameMapping read(Reader input) throws IOException {
        var in = new BufferedReader(input);
        if (!HEADER.equals(in.readLine())) throw new IOException("Unsupported mapping header");
        var builder = builder();
        String line;
        int number = 1;
        while ((line = in.readLine()) != null) {
            number++;
            String[] parts = line.split("\\t", -1);
            try {
                if (parts.length == 3 && parts[0].equals("C")) builder.mapClass(decode(parts[1]), decode(parts[2]));
                else if (parts.length == 5 && parts[0].equals("M")) builder.mapMethod(decode(parts[1]), decode(parts[2]), decode(parts[3]), decode(parts[4]));
                else throw new IllegalArgumentException("Unknown mapping entry");
            } catch (IllegalArgumentException exception) { throw new IOException("Invalid mapping at line " + number, exception); }
        }
        try { return builder.build(); }
        catch (IllegalArgumentException exception) { throw new IOException("Conflicting mappings", exception); }
    }

    private static void checkDescriptor(String descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        try {
            if (descriptor.charAt(0) != '(') throw new IllegalArgumentException();
            int i = 1;
            int slots = 0;
            while (descriptor.charAt(i) != ')') {
                char first = descriptor.charAt(i);
                slots += first == 'J' || first == 'D' ? 2 : 1;
                if (slots > 255) throw new IllegalArgumentException();
                i = parseFieldType(descriptor, i);
            }
            i++;
            if (descriptor.charAt(i) == 'V') i++; else i = parseFieldType(descriptor, i);
            if (i != descriptor.length()) throw new IllegalArgumentException();
        } catch (IndexOutOfBoundsException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid method descriptor: " + descriptor, exception);
        }
    }
    private static int parseFieldType(String descriptor, int start) {
        int i = start;
        while (descriptor.charAt(i) == '[') i++;
        if (i - start > 255) throw new IllegalArgumentException();
        char kind = descriptor.charAt(i++);
        if ("BCDFIJSZ".indexOf(kind) >= 0) return i;
        if (kind != 'L') throw new IllegalArgumentException();
        int end = descriptor.indexOf(';', i);
        if (end < 0) throw new IllegalArgumentException();
        checkClass(descriptor.substring(i, end));
        return end + 1;
    }
    private static void checkUnicode(String value) {
        if (!StandardCharsets.UTF_8.newEncoder().canEncode(value))
            throw new IllegalArgumentException("Unpaired surrogate cannot be encoded in mapping");
    }
    private static String encode(String s) { return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }
    private static String decode(String s) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(s);
            String decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (!encode(decoded).equals(s)) throw new IllegalArgumentException("Noncanonical encoding");
            return decoded;
        } catch (CharacterCodingException exception) { throw new IllegalArgumentException("Invalid UTF-8", exception); }
    }
    private static void checkClass(String name) {
        Objects.requireNonNull(name, "class name");
        checkUnicode(name);
        if (name.isEmpty() || name.startsWith("/") || name.endsWith("/") || name.contains("//") || name.indexOf('.') >= 0 || name.indexOf(';') >= 0 || name.indexOf('[') >= 0)
            throw new IllegalArgumentException("Invalid internal class name: " + name);
    }
    private static void checkMethodName(String name) {
        Objects.requireNonNull(name, "method name");
        checkUnicode(name);
        if (name.isEmpty() || name.indexOf('.') >= 0 || name.indexOf(';') >= 0 || name.indexOf('[') >= 0 || name.indexOf('/') >= 0 ||
                (name.indexOf('<') >= 0 || name.indexOf('>') >= 0) && !name.equals("<init>") && !name.equals("<clinit>"))
            throw new IllegalArgumentException("Invalid method name: " + name);
    }
}
