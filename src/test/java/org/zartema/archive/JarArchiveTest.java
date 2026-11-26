package org.zartema.archive;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TimeZone;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JarArchiveTest {
    @TempDir Path directory;

    @Test
    void preservesAllPhysicalMultiReleaseEntriesAndEmitsDeterministically() throws Exception {
        Path source = directory.resolve("physical-mr.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Multi-Release", "true");
        try (var output = new JarOutputStream(Files.newOutputStream(source), manifest)) {
            put(output, "sample/Api.class", new byte[] {1, 2, 3});
            put(output, "META-INF/versions/9/sample/Api.class", new byte[] {4, 5});
            put(output, "META-INF/versions/21/sample/Api.class", new byte[] {6, 7, 8, 9});
            put(output, "META-INF/services/sample.Service", "sample.Provider".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            put(output, "META-INF/", new byte[0]);
            put(output, "META-INF/INDEX.LIST", "JarIndex-Version: 1.0\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        JarArchive archive = JarArchive.read(source);
        assertEquals(List.of("META-INF/MANIFEST.MF", "sample/Api.class",
                        "META-INF/versions/9/sample/Api.class", "META-INF/versions/21/sample/Api.class",
                        "META-INF/services/sample.Service", "META-INF/", "META-INF/INDEX.LIST"),
                archive.entries().stream().map(JarArchive.Entry::name).toList());

        Path first = directory.resolve("first.jar");
        Path second = directory.resolve("second.jar");
        TimeZone originalZone = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"));
            archive.writeDeterministic(first);
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
            archive.writeDeterministic(second);
        } finally {
            TimeZone.setDefault(originalZone);
        }
        byte[] firstBytes = Files.readAllBytes(first);
        byte[] secondBytes = Files.readAllBytes(second);
        assertArrayEquals(firstBytes, secondBytes,
                () -> "timezone-dependent archive bytes at offset " + java.util.Arrays.mismatch(firstBytes, secondBytes));
        assertPhysicalContentEquals(archive, JarArchive.read(first));
        assertEquals(List.of("META-INF/", "META-INF/MANIFEST.MF", "META-INF/INDEX.LIST",
                        "META-INF/services/sample.Service",
                        "META-INF/versions/21/sample/Api.class", "META-INF/versions/9/sample/Api.class",
                        "sample/Api.class"),
                JarArchive.read(first).entries().stream().map(JarArchive.Entry::name).toList());
        try (var input = new JarInputStream(Files.newInputStream(first))) {
            assertEquals("true", input.getManifest().getMainAttributes().getValue("Multi-Release"));
        }
    }

    @Test
    void replacementReturnsANewArchiveAndLeavesOriginalContentUnchanged() throws Exception {
        Path source = simpleArchive("plain.jar", List.of(new NamedBytes("sample/Thing.class", new byte[] {1})));
        JarArchive original = JarArchive.read(source);
        byte[] replacement = {9, 8};
        JarArchive changed = original.withEntryContent("sample/Thing.class", replacement);
        replacement[0] = 0;

        assertNotSame(original, changed);
        assertArrayEquals(new byte[] {1}, original.entries().get(0).content());
        assertArrayEquals(new byte[] {9, 8}, changed.entries().get(0).content());
        Path output = directory.resolve("changed.jar");
        changed.writeDeterministic(output);
        assertArrayEquals(new byte[] {9, 8}, JarArchive.read(output).entries().get(0).content());
    }

    @Test
    void rejectsPayloadChangesWhenSignatureFilesArePresent() throws Exception {
        Path source = simpleArchive("signed.jar", List.of(
                new NamedBytes("sample/Thing.class", new byte[] {1}),
                new NamedBytes("META-INF/TEST.SF", new byte[] {2}),
                new NamedBytes("META-INF/TEST.RSA", new byte[] {3})));
        JarArchive archive = JarArchive.read(source);

        assertThrows(IOException.class, () -> archive.withEntryContent("sample/Thing.class", new byte[] {4}));
    }

    @Test
    void rejectsPayloadChangesWhenManifestContainsDigestsWithoutSignatureFiles() throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        Attributes section = new Attributes();
        section.putValue("SHA-256-Digest", "dGVzdA==");
        manifest.getEntries().put("sample/Thing.class", section);
        Path source = directory.resolve("manifest-digest.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(source), manifest)) {
            put(output, "sample/Thing.class", new byte[] {1});
        }
        JarArchive archive = JarArchive.read(source);

        assertThrows(IOException.class, () -> archive.withEntryContent("sample/Thing.class", new byte[] {4}));
    }

    @Test
    void rejectsIntroducingDigestMetadataThroughManifestReplacement() throws Exception {
        Manifest plain = new Manifest();
        plain.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        Path source = simpleArchive("plain-manifest.jar", List.of(
                new NamedBytes("META-INF/MANIFEST.MF", manifestBytes(plain)),
                new NamedBytes("sample/Thing.class", new byte[] {1})));
        Manifest withDigest = new Manifest();
        withDigest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        Attributes section = new Attributes();
        section.putValue("SHA-256-Digest", "dGVzdA==");
        withDigest.getEntries().put("sample/Thing.class", section);
        JarArchive archive = JarArchive.read(source);

        assertThrows(IOException.class,
                () -> archive.withEntryContent("META-INF/MANIFEST.MF", manifestBytes(withDigest)));
    }

    @Test
    void enforcesEntryAndTotalUncompressedSizeLimits() throws Exception {
        Path source = simpleArchive("large-entry.jar", List.of(new NamedBytes("data.bin", new byte[] {1, 2, 3})));

        assertThrows(IOException.class,
                () -> JarArchive.read(source, new JarArchive.Limits(5, 2, 5)));
        assertThrows(IOException.class,
                () -> JarArchive.read(source, new JarArchive.Limits(5, 5, 2)));
    }

    @Test
    void acceptsExactPayloadBoundsAndKeepsEntryContentDefensive() throws Exception {
        Path source = simpleArchive("exact-limits.jar", List.of(
                new NamedBytes("data.bin", new byte[] {1, 2, 3})));
        JarArchive.Limits exactBounds = new JarArchive.Limits(1, 3, 3);

        JarArchive archive = JarArchive.read(source, exactBounds);
        byte[] exposed = archive.entries().get(0).content();
        exposed[0] = 99;

        assertArrayEquals(new byte[] {1, 2, 3}, archive.entries().get(0).content());
        assertSame(archive, archive.withEntryContent("data.bin", new byte[] {1, 2, 3}));
        assertThrows(IOException.class,
                () -> archive.withEntryContent("data.bin", new byte[] {1, 2, 3, 4}));
    }

    @Test
    void rejectsReplacementThatWouldExceedAggregatePayloadLimit() throws Exception {
        Path source = simpleArchive("replacement-total-limit.jar", List.of(
                new NamedBytes("first.bin", new byte[] {1, 2, 3}),
                new NamedBytes("second.bin", new byte[] {4})));
        JarArchive archive = JarArchive.read(source, new JarArchive.Limits(2, 4, 4));

        assertThrows(IOException.class,
                () -> archive.withEntryContent("second.bin", new byte[] {5, 6}));
    }

    @Test
    void rejectsUnsafeNamesMalformedArchivesAndSourceOverwrite() throws Exception {
        Path traversal = simpleArchive("traversal.jar", List.of(new NamedBytes("../outside", new byte[] {1})));
        assertThrows(IOException.class, () -> JarArchive.read(traversal));

        Path malformed = directory.resolve("malformed.jar");
        Files.write(malformed, new byte[] {1, 2, 3, 4});
        assertThrows(IOException.class, () -> JarArchive.read(malformed));

        Path source = simpleArchive("same.jar", List.of(new NamedBytes("data.bin", new byte[] {1})));
        JarArchive archive = JarArchive.read(source);
        assertThrows(IOException.class, () -> archive.writeDeterministic(source));
    }

    @Test
    void rejectsEntryPayloadWhoseCrcDoesNotMatchItsDirectoryRecord() throws Exception {
        Path valid = directory.resolve("crc-source.jar");
        try (var output = new JarOutputStream(Files.newOutputStream(valid))) {
            putStored(output, "payload.bin", new byte[] {11, 12, 13, 14});
        }
        byte[] bytes = Files.readAllBytes(valid);
        int payloadOffset = indexOf(bytes, new byte[] {11, 12, 13, 14});
        assertTrue(payloadOffset >= 0);
        bytes[payloadOffset + 2] ^= 0x7f;
        Path corrupted = directory.resolve("crc-corrupt.jar");
        Files.write(corrupted, bytes);

        assertThrows(IOException.class, () -> JarArchive.read(corrupted));
    }

    private Path simpleArchive(String fileName, List<NamedBytes> entries) throws IOException {
        Path path = directory.resolve(fileName);
        try (var output = new JarOutputStream(Files.newOutputStream(path))) {
            for (NamedBytes entry : entries) put(output, entry.name(), entry.content());
        }
        return path;
    }

    private static void put(JarOutputStream output, String name, byte[] content) throws IOException {
        output.putNextEntry(new JarEntry(name));
        output.write(content);
        output.closeEntry();
    }

    private static void putStored(JarOutputStream output, String name, byte[] content) throws IOException {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(content);
        JarEntry entry = new JarEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(content.length);
        entry.setCompressedSize(content.length);
        entry.setCrc(crc.getValue());
        output.putNextEntry(entry);
        output.write(content);
        output.closeEntry();
    }

    private static byte[] manifestBytes(Manifest manifest) throws IOException {
        var output = new ByteArrayOutputStream();
        manifest.write(output);
        return output.toByteArray();
    }

    private static int indexOf(byte[] input, byte[] target) {
        outer: for (int start = 0; start <= input.length - target.length; start++) {
            for (int offset = 0; offset < target.length; offset++) {
                if (input[start + offset] != target[offset]) continue outer;
            }
            return start;
        }
        return -1;
    }

    private static void assertPhysicalContentEquals(JarArchive expected, JarArchive actual) {
        assertEquals(expected.entries().size(), actual.entries().size());
        for (JarArchive.Entry expectedEntry : expected.entries()) {
            JarArchive.Entry actualEntry = actual.entries().stream()
                    .filter(entry -> entry.name().equals(expectedEntry.name())).findFirst().orElseThrow();
            assertArrayEquals(expectedEntry.content(), actualEntry.content(), expectedEntry.name());
            assertEquals(expectedEntry.directory(), actualEntry.directory(), expectedEntry.name());
        }
    }

    private record NamedBytes(String name, byte[] content) { }
}
