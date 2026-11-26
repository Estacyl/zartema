package org.zartema.archive;

import java.io.ByteArrayInputStream;
import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Immutable snapshot of the physical entries in a JAR/ZIP archive. */
public final class JarArchive {
    private static final int BUFFER_SIZE = 8192;
    private static final Limits DEFAULT_LIMITS = new Limits(100_000, 256L * 1024 * 1024, 1024L * 1024 * 1024);

    /**
     * Resource bounds applied to the decompressed entry payloads retained by an archive.
     * These limits bound the aggregate bytes in the owned entry arrays; they do not bound JVM
     * object/index overhead, ZIP implementation buffers, or defensive copies requested through
     * {@link Entry#content()}. Each individual payload must also fit in one Java byte array.
     */
    public record Limits(int maxEntries, long maxEntryBytes, long maxTotalBytes) {
        public Limits {
            if (maxEntries < 1 || maxEntryBytes < 0 || maxTotalBytes < 0) {
                throw new IllegalArgumentException("Archive limits must be positive (byte limits may be zero)");
            }
        }
    }

    /** Immutable archive entry; content is defensively copied when supplied by a caller and on output. */
    public static final class Entry {
        private final String name;
        private final boolean directory;
        private final byte[] content;
        /** Takes ownership of a private array that is never exposed directly. */
        private Entry(String name, boolean directory, byte[] ownedContent) {
            this.name = name;
            this.directory = directory;
            this.content = ownedContent;
        }

        public String name() { return name; }
        public boolean directory() { return directory; }
        public byte[] content() { return content.clone(); }

    }

    private final Path source;
    private final List<Entry> entries;
    private final Limits limits;
    private final boolean protectedMetadata;

    private JarArchive(Path source, List<Entry> entries, Limits limits, boolean protectedMetadata) {
        this.source = source;
        this.entries = List.copyOf(entries);
        this.limits = limits;
        this.protectedMetadata = protectedMetadata;
    }

    /** Reads all physical entries, including every {@code META-INF/versions/N} variant. */
    public static JarArchive read(Path path) throws IOException {
        return read(path, DEFAULT_LIMITS);
    }

    /** Reads all physical entries subject to explicit decompressed-size limits. */
    public static JarArchive read(Path path, Limits limits) throws IOException {
        if (path == null || limits == null) throw new IllegalArgumentException("Path and limits are required");
        Path normalized = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) throw new IOException("Archive is not a regular file: " + normalized);

        var entries = new ArrayList<Entry>();
        var names = new HashSet<String>();
        long totalBytes = 0;
        try (ZipFile zip = new ZipFile(normalized.toFile())) {
            if (zip.size() > limits.maxEntries()) {
                throw new IOException("Archive entry count exceeds configured limit: " + zip.size());
            }
            Enumeration<? extends ZipEntry> enumeration = zip.entries();
            while (enumeration.hasMoreElements()) {
                ZipEntry sourceEntry = enumeration.nextElement();
                String name = sourceEntry.getName();
                validateEntryName(name);
                if (!names.add(name)) throw new IOException("Duplicate archive entry: " + name);
                long declaredSize = sourceEntry.getSize();
                if (declaredSize < 0) {
                    throw new IOException("Archive entry has no declared uncompressed size: " + name);
                }
                if (declaredSize > limits.maxEntryBytes()) {
                    throw new IOException("Archive entry exceeds configured size limit: " + name);
                }
                long remaining = limits.maxTotalBytes() - totalBytes;
                if (declaredSize > remaining) {
                    throw new IOException("Archive total uncompressed size exceeds configured limit: " + name);
                }
                if (declaredSize > Integer.MAX_VALUE) {
                    throw new IOException("Archive entry cannot fit in a Java byte array: " + name);
                }
                byte[] content;
                try (InputStream input = zip.getInputStream(sourceEntry)) {
                    content = readDeclaredSize(input, (int) declaredSize, name);
                }
                CRC32 inputCrc = new CRC32();
                inputCrc.update(content);
                if (sourceEntry.getCrc() < 0 || sourceEntry.getCrc() != inputCrc.getValue()) {
                    throw new IOException("Archive entry CRC-32 mismatch: " + name);
                }
                totalBytes += content.length;
                entries.add(new Entry(name, sourceEntry.isDirectory(), content));
            }
        } catch (IllegalArgumentException | java.util.zip.ZipException exception) {
            throw new IOException("Invalid archive: " + normalized, exception);
        }

        boolean protectedMetadata = inspectProtectedMetadata(entries);
        return new JarArchive(normalized, entries, limits, protectedMetadata);
    }

    /** Physical entries in their original directory order; output ordering is normalized separately. */
    public List<Entry> entries() { return entries; }

    /**
     * Returns a new snapshot with one entry payload replaced. Signed or digest-bearing archives
     * reject changed payloads because this API does not regenerate signatures or manifest digests.
     */
    public JarArchive withEntryContent(String name, byte[] replacement) throws IOException {
        if (name == null || replacement == null) throw new IllegalArgumentException("Name and content are required");
        validateEntryName(name);
        if (replacement.length > limits.maxEntryBytes()) {
            throw new IOException("Replacement entry exceeds configured size limit: " + name);
        }
        boolean found = false;
        long updatedTotal = 0;
        for (Entry entry : entries) {
            boolean target = entry.name.equals(name);
            found |= target;
            long updatedSize = target ? replacement.length : entry.content.length;
            if (updatedTotal > limits.maxTotalBytes() - updatedSize) {
                throw new IOException("Archive exceeds configured total size limit after replacement");
            }
            updatedTotal += updatedSize;
        }
        if (!found) throw new IOException("Archive entry does not exist: " + name);

        var changedEntries = new ArrayList<Entry>(entries.size());
        boolean changed = false;
        for (Entry entry : entries) {
            if (!entry.name.equals(name)) {
                changedEntries.add(entry);
                continue;
            }
            boolean entryChanged = !Arrays.equals(entry.content, replacement);
            if (entryChanged) {
                changed = true;
                if (entry.directory && replacement.length != 0) {
                    throw new IOException("Cannot assign a payload to directory entry: " + name);
                }
                if (protectedMetadata) {
                    throw new IOException("Cannot change payload in an archive with signature or digest metadata");
                }
            }
            changedEntries.add(entryChanged
                    ? new Entry(entry.name, entry.directory, replacement.clone())
                    : entry);
        }
        if (!changed) return this;
        boolean updatedProtectedMetadata = inspectProtectedMetadata(changedEntries);
        if (updatedProtectedMetadata) {
            throw new IOException("Cannot introduce signature or digest metadata without validating it");
        }
        return new JarArchive(source, changedEntries, limits, false);
    }

    /**
     * Emits a reproducible ZIP32 archive using sorted names, fixed DOS timestamps and uncompressed
     * payloads. ZIP comments and per-entry ZIP metadata are normalized away.
     */
    public void writeDeterministic(Path destination) throws IOException {
        if (destination == null) throw new IllegalArgumentException("Destination is required");
        Path output = destination.toAbsolutePath().normalize();
        if (output.equals(source)) throw new IOException("Refusing to overwrite the source archive");
        Path parent = output.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IOException("Output directory does not exist: " + parent);
        }
        Path temporary = Files.createTempFile(parent, ".zartema-archive-", ".tmp");
        try {
            var sortedEntries = entries.stream().sorted(Comparator
                    .comparingInt((Entry entry) -> emissionOrder(entry.name))
                    .thenComparing(Entry::name)).toList();
            if (sortedEntries.size() >= 0xffff) {
                throw new IOException("Deterministic ZIP32 emission supports at most 65534 entries");
            }
            var centralEntries = new ArrayList<CentralEntry>(sortedEntries.size());
            try (var stream = new CountingOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                for (Entry entry : sortedEntries) {
                    byte[] nameBytes = entry.name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    if (nameBytes.length > 0xffff) throw new IOException("Entry name is too long: " + entry.name);
                    if (entry.content.length >= 0xffffffffL) {
                        throw new IOException("Entry is too large for deterministic ZIP32 emission: " + entry.name);
                    }
                    CRC32 crc = new CRC32();
                    crc.update(entry.content);
                    long localOffset = stream.count();
                    if (localOffset >= 0xffffffffL) throw new IOException("Archive exceeds ZIP32 offset limits");
                    writeU4(stream, 0x04034b50L); // local file header
                    writeU2(stream, 20);           // version needed
                    writeU2(stream, 0x0800);        // UTF-8 names
                    writeU2(stream, 0);            // STORED
                    writeU2(stream, 0);            // 00:00:00 DOS time
                    writeU2(stream, 0x0021);       // 1980-01-01 DOS date
                    writeU4(stream, crc.getValue());
                    writeU4(stream, entry.content.length);
                    writeU4(stream, entry.content.length);
                    writeU2(stream, nameBytes.length);
                    writeU2(stream, 0);            // no extra fields
                    stream.write(nameBytes);
                    stream.write(entry.content);
                    centralEntries.add(new CentralEntry(nameBytes, crc.getValue(), entry.content.length, localOffset));
                }

                long centralOffset = stream.count();
                for (CentralEntry entry : centralEntries) writeCentralEntry(stream, entry);
                long centralSize = stream.count() - centralOffset;
                if (centralOffset >= 0xffffffffL || centralSize >= 0xffffffffL) {
                    throw new IOException("Archive exceeds ZIP32 central directory limits");
                }
                writeU4(stream, 0x06054b50L); // end of central directory
                writeU2(stream, 0);
                writeU2(stream, 0);
                writeU2(stream, centralEntries.size());
                writeU2(stream, centralEntries.size());
                writeU4(stream, centralSize);
                writeU4(stream, centralOffset);
                writeU2(stream, 0); // no archive comment
            }
            verifyWrittenArchive(temporary, sortedEntries);
            try {
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("Output filesystem does not support atomic archive replacement", exception);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeCentralEntry(OutputStream output, CentralEntry entry) throws IOException {
        writeU4(output, 0x02014b50L); // central directory file header
        writeU2(output, 20);           // version made by
        writeU2(output, 20);           // version needed
        writeU2(output, 0x0800);        // UTF-8 names
        writeU2(output, 0);            // STORED
        writeU2(output, 0);            // 00:00:00 DOS time
        writeU2(output, 0x0021);       // 1980-01-01 DOS date
        writeU4(output, entry.crc);
        writeU4(output, entry.size);
        writeU4(output, entry.size);
        writeU2(output, entry.nameBytes.length);
        writeU2(output, 0);            // no extra fields
        writeU2(output, 0);            // no entry comment
        writeU2(output, 0);            // disk number
        writeU2(output, 0);            // internal attributes
        writeU4(output, 0);            // external attributes
        writeU4(output, entry.localOffset);
        output.write(entry.nameBytes);
    }

    private static int emissionOrder(String name) {
        if (name.equals("META-INF/")) return 0;
        if (name.equals("META-INF/MANIFEST.MF")) return 1;
        return 2;
    }

    private static void writeU2(OutputStream output, long value) throws IOException {
        output.write((int) value & 0xff);
        output.write((int) (value >>> 8) & 0xff);
    }

    private static void writeU4(OutputStream output, long value) throws IOException {
        writeU2(output, value);
        writeU2(output, value >>> 16);
    }

    private void verifyWrittenArchive(Path output, List<Entry> expected) throws IOException {
        try (ZipFile zip = new ZipFile(output.toFile())) {
            if (zip.size() != expected.size()) throw new IOException("Emitted archive entry count changed");
            Enumeration<? extends ZipEntry> actualEntries = zip.entries();
            for (Entry wanted : expected) {
                if (!actualEntries.hasMoreElements()) throw new IOException("Emitted archive lost entry: " + wanted.name);
                ZipEntry actual = actualEntries.nextElement();
                if (!wanted.name.equals(actual.getName()) || wanted.directory != actual.isDirectory()
                        || actual.getSize() != wanted.content.length) {
                    throw new IOException("Emitted archive metadata changed for entry: " + wanted.name);
                }
                try (InputStream input = zip.getInputStream(actual)) {
                    verifyPayload(input, wanted);
                }
            }
            if (actualEntries.hasMoreElements()) throw new IOException("Emitted archive contains unexpected entries");
        }
    }

    private static byte[] readDeclaredSize(InputStream input, int expectedSize, String name) throws IOException {
        byte[] content = new byte[expectedSize];
        int offset = 0;
        while (offset < expectedSize) {
            int count = input.read(content, offset, expectedSize - offset);
            if (count < 0) {
                throw new IOException("Archive entry ended before its declared size: " + name);
            }
            if (count == 0) {
                int value = input.read();
                if (value < 0) throw new IOException("Archive entry ended before its declared size: " + name);
                content[offset++] = (byte) value;
            } else {
                offset += count;
            }
        }
        if (input.read() != -1) throw new IOException("Archive entry exceeds its declared size: " + name);
        return content;
    }

    private static void verifyPayload(InputStream input, Entry expected) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int offset = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count > expected.content.length - offset) {
                throw new IOException("Emitted archive payload grew for entry: " + expected.name);
            }
            for (int i = 0; i < count; i++) {
                if (buffer[i] != expected.content[offset + i]) {
                    throw new IOException("Emitted archive payload changed for entry: " + expected.name);
                }
            }
            offset += count;
        }
        if (offset != expected.content.length) {
            throw new IOException("Emitted archive payload was truncated for entry: " + expected.name);
        }
    }

    private static void validateEntryName(String name) throws IOException {
        if (name.isEmpty() || name.startsWith("/") || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            throw new IOException("Unsafe archive entry name: " + name);
        }
        String[] components = name.split("/", -1);
        for (int i = 0; i < components.length; i++) {
            String component = components[i];
            if (component.equals(".") || component.equals("..")) {
                throw new IOException("Unsafe archive entry name: " + name);
            }
            if (components[i].isEmpty() && !(i == components.length - 1 && name.endsWith("/"))) {
                throw new IOException("Unsafe archive entry name: " + name);
            }
        }
    }

    private static boolean inspectProtectedMetadata(List<Entry> entries) {
        for (Entry entry : entries) {
            if (isSignatureFile(entry.name)) return true;
            if (!entry.name.equalsIgnoreCase("META-INF/MANIFEST.MF")) continue;
            try {
                Manifest manifest = new Manifest(new ByteArrayInputStream(entry.content));
                if (containsDigestAttribute(manifest.getMainAttributes())) return true;
                for (Attributes attributes : manifest.getEntries().values()) {
                    if (containsDigestAttribute(attributes)) return true;
                }
            } catch (IOException | RuntimeException exception) {
                // An unreadable manifest cannot safely be edited because digest metadata is unknown.
                return true;
            }
        }
        return false;
    }

    private static boolean containsDigestAttribute(Attributes attributes) {
        return attributes.keySet().stream()
                .map(Object::toString)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .anyMatch(name -> name.contains("digest"));
    }

    private static boolean isSignatureFile(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("META-INF/")) return false;
        String leaf = upper.substring("META-INF/".length());
        if (leaf.contains("/")) return false;
        return leaf.endsWith(".SF") || leaf.endsWith(".RSA") || leaf.endsWith(".DSA")
                || leaf.endsWith(".EC") || leaf.startsWith("SIG-");
    }

    private record CentralEntry(byte[] nameBytes, long crc, long size, long localOffset) { }

    private static final class CountingOutputStream extends FilterOutputStream {
        private long count;

        private CountingOutputStream(OutputStream output) { super(output); }
        private long count() { return count; }

        @Override public void write(int value) throws IOException {
            out.write(value);
            count++;
        }

        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            out.write(bytes, offset, length);
            count += length;
        }
    }
}
