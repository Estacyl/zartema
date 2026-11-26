package org.zartema.archive;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * Immutable logical classpath entry view of a physical {@link JarArchive} for one Java
 * feature release. Direct lookup of physical versioned paths is outside this view. This selects
 * entries only; it does not validate class-file versions, API
 * compatibility, module descriptor consistency, signatures, or runtime linkage.
 */
public final class MultiReleaseJarView {
    private static final String VERSION_PREFIX = "META-INF/versions/";
    private static final String META_INF = "META-INF/";

    /** A logical runtime path and the physical archive entry from which it was selected. */
    public record EffectiveEntry(String logicalName, String physicalName, int version,
                                 JarArchive.Entry entry) {
        public EffectiveEntry {
            Objects.requireNonNull(logicalName, "logicalName");
            Objects.requireNonNull(physicalName, "physicalName");
            Objects.requireNonNull(entry, "entry");
            if (version != 0 && version < 9) {
                throw new IllegalArgumentException("Versioned entry release must be at least 9");
            }
            if (!physicalName.equals(entry.name())) {
                throw new IllegalArgumentException("Physical name must match the source archive entry");
            }
        }
    }

    private final int targetRelease;
    private final boolean multiRelease;
    private final List<EffectiveEntry> entries;

    private MultiReleaseJarView(int targetRelease, boolean multiRelease, List<EffectiveEntry> entries) {
        this.targetRelease = targetRelease;
        this.multiRelease = multiRelease;
        this.entries = List.copyOf(entries);
    }

    /**
     * Selects the highest eligible version independently for each logical path. Java 8 selects
     * only base entries. Directory records are omitted because they do not participate in JAR
     * class/resource lookup; all returned payload entries retain their source physical names.
     *
     * <p>For an archive without {@code Multi-Release: true}, version-looking paths remain at
     * their literal physical names and are not treated as overrides.</p>
     *
     * @param archive immutable physical archive snapshot
     * @param targetRelease Java feature release, at least 8
     * @throws IOException if the manifest is malformed or a marked archive has a structurally
     *         ambiguous supported version path (for example, versioned content under META-INF)
     */
    public static MultiReleaseJarView select(JarArchive archive, int targetRelease) throws IOException {
        Objects.requireNonNull(archive, "archive");
        if (targetRelease < 8) {
            throw new IllegalArgumentException("Target release must be at least Java 8: " + targetRelease);
        }
        boolean multiRelease = isMultiRelease(archive);
        Map<String, EffectiveEntry> selected = new HashMap<>();
        for (JarArchive.Entry entry : archive.entries()) {
            if (entry.directory()) continue;
            String physicalName = entry.name();
            VersionedPath versioned = multiRelease ? parseVersionedPath(physicalName) : null;
            if (versioned == null) {
                putIfAbsent(selected, new EffectiveEntry(physicalName, physicalName, 0, entry));
                continue;
            }
            if (versioned.structuralDirectory()) continue;
            if (versioned.release() >= 9 && versioned.logicalName().startsWith(META_INF)) {
                throw new IOException("Versioned entries under META-INF are unsupported by the JAR specification: "
                        + physicalName);
            }
            if (versioned.release() < 9 || versioned.release() > targetRelease) continue;
            EffectiveEntry candidate = new EffectiveEntry(versioned.logicalName(), physicalName,
                    versioned.release(), entry);
            EffectiveEntry current = selected.get(candidate.logicalName());
            if (current == null || candidate.version() > current.version()) {
                selected.put(candidate.logicalName(), candidate);
            } else if (candidate.version() == current.version()) {
                throw new IOException("Ambiguous entries for logical path " + candidate.logicalName()
                        + " at release " + candidate.version());
            }
        }
        List<EffectiveEntry> ordered = selected.values().stream()
                .sorted(Comparator.comparing(EffectiveEntry::logicalName)).toList();
        return new MultiReleaseJarView(targetRelease, multiRelease, ordered);
    }

    public int targetRelease() { return targetRelease; }
    public boolean multiRelease() { return multiRelease; }
    public List<EffectiveEntry> entries() { return entries; }

    /** Returns the selected entry for a logical path, or {@code null} when absent. */
    public EffectiveEntry find(String logicalName) {
        Objects.requireNonNull(logicalName, "logicalName");
        int low = 0;
        int high = entries.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            EffectiveEntry entry = entries.get(mid);
            int compare = entry.logicalName().compareTo(logicalName);
            if (compare == 0) return entry;
            if (compare < 0) low = mid + 1;
            else high = mid - 1;
        }
        return null;
    }

    private static boolean isMultiRelease(JarArchive archive) throws IOException {
        List<JarArchive.Entry> manifestEntries = archive.entries().stream()
                .filter(entry -> !entry.directory() && entry.name().equalsIgnoreCase("META-INF/MANIFEST.MF"))
                .toList();
        if (manifestEntries.size() > 1) {
            throw new IOException("Archive contains multiple case-insensitive manifest paths");
        }
        if (manifestEntries.isEmpty()) return false;
        final Manifest manifest;
        try {
            manifest = new Manifest(new ByteArrayInputStream(manifestEntries.getFirst().content()));
        } catch (IOException | RuntimeException exception) {
            throw new IOException("Cannot parse JAR manifest", exception);
        }
        String value = manifest.getMainAttributes().getValue(Attributes.Name.MULTI_RELEASE);
        return value != null && "true".equalsIgnoreCase(value);
    }

    /**
     * Returns null for paths not in the release namespace. Invalid numeric directory names are
     * ignored as prescribed by the JAR specification; overflow and malformed recognized paths
     * fail closed instead of silently selecting a different logical entry.
     */
    private static VersionedPath parseVersionedPath(String name) throws IOException {
        if (!name.startsWith(VERSION_PREFIX)) return null;
        int versionEnd = name.indexOf('/', VERSION_PREFIX.length());
        if (versionEnd < 0) {
            if (name.endsWith("/")) return new VersionedPath(0, "", true);
            throw new IOException("Malformed versioned JAR path: " + name);
        }
        String versionText = name.substring(VERSION_PREFIX.length(), versionEnd);
        if (!versionText.matches("[1-9][0-9]*")) {
            return new VersionedPath(0, "", true);
        }
        final int release;
        try {
            release = Integer.parseInt(versionText);
        } catch (NumberFormatException exception) {
            throw new IOException("Versioned JAR release is outside the supported integer range: " + name,
                    exception);
        }
        String logicalName = name.substring(versionEnd + 1);
        if (logicalName.isEmpty()) {
            return new VersionedPath(release, "", true);
        }
        return new VersionedPath(release, logicalName, false);
    }

    private static void putIfAbsent(Map<String, EffectiveEntry> selected, EffectiveEntry candidate)
            throws IOException {
        EffectiveEntry existing = selected.putIfAbsent(candidate.logicalName(), candidate);
        if (existing != null && existing.version() == 0) {
            throw new IOException("Duplicate logical path in effective archive view: " + candidate.logicalName());
        }
    }

    private record VersionedPath(int release, String logicalName, boolean structuralDirectory) { }
}
