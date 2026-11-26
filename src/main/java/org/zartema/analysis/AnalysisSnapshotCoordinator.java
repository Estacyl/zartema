package org.zartema.analysis;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.objectweb.asm.ClassReader;
import org.zartema.analysis.wala.WalaHierarchyAnalyzer;
import org.zartema.archive.JarArchive;

/**
 * Runs WALA and SootUp against the same staged, non-versioned JAR snapshot and compares
 * directly comparable application identity and direct hierarchy facts. The result is
 * diagnostic evidence only: agreement is not proof of correctness and cannot authorize a rename.
 */
public final class AnalysisSnapshotCoordinator {
    private AnalysisSnapshotCoordinator() { }

    public record HierarchyDifference(String internalName, List<String> differences) {
        public HierarchyDifference {
            differences = List.copyOf(differences);
        }
    }

    /** Detached cross-check results. All collections have stable ordering. */
    public record Report(
            String snapshotSha256,
            WalaHierarchyAnalyzer.Report wala,
            SootUpAnalyzer.AnalysisReport sootUp,
            List<String> classesOnlyInWala,
            List<String> classesOnlyInSootUp,
            List<String> ambiguousClassIdentities,
            List<String> malformedClassEntries,
            List<String> unresolvedWalaTypes,
            List<String> missingHierarchyReferences,
            List<HierarchyDifference> hierarchyDisagreements,
            boolean sootUpUnresolvedAccountingAvailable) {
        public Report {
            classesOnlyInWala = sorted(classesOnlyInWala);
            classesOnlyInSootUp = sorted(classesOnlyInSootUp);
            ambiguousClassIdentities = sorted(ambiguousClassIdentities);
            malformedClassEntries = sorted(malformedClassEntries);
            unresolvedWalaTypes = sorted(unresolvedWalaTypes);
            missingHierarchyReferences = sorted(missingHierarchyReferences);
            hierarchyDisagreements = hierarchyDisagreements.stream()
                    .sorted(Comparator.comparing(HierarchyDifference::internalName)).toList();
        }

        /**
         * This coordinator never treats agreement or completeness as transformation approval.
         * Consumers must perform pass-specific safety analysis independently.
         */
        public boolean mayAuthorizeRenaming() {
            return false;
        }

        private static List<String> sorted(List<String> values) {
            return values.stream().sorted().toList();
        }
    }

    /**
     * Creates one private staged copy, runs both adapters over that exact file, and verifies
     * its digest did not change between or during analyses.
     */
    public static Report analyze(Path inputJar) throws IOException {
        return analyzeInternal(inputJar, null);
    }

    /** Analyze with explicit decompressed archive payload limits. */
    public static Report analyze(Path inputJar, JarArchive.Limits limits) throws IOException {
        return analyzeInternal(inputJar, java.util.Objects.requireNonNull(limits, "limits"));
    }

    private static Report analyzeInternal(Path inputJar, JarArchive.Limits limits) throws IOException {
        if (inputJar == null || !Files.isRegularFile(inputJar)) {
            throw new IOException("Analysis input must be an existing regular JAR file: " + inputJar);
        }
        Path directory = Files.createTempDirectory("zartema-analysis-snapshot-");
        Path snapshot = directory.resolve("snapshot.jar");
        try {
            Files.copy(inputJar, snapshot);
            snapshot.toFile().setReadOnly();
            String digest = sha256(snapshot);
            ArchiveInventory inventory = inventory(limits == null ? JarArchive.read(snapshot) : JarArchive.read(snapshot, limits));

            WalaHierarchyAnalyzer.Report wala = WalaHierarchyAnalyzer.analyze(snapshot);
            requireDigest(snapshot, digest, "WALA");
            SootUpAnalyzer.AnalysisReport sootUp = SootUpAnalyzer.analyze(snapshot);
            requireDigest(snapshot, digest, "SootUp");
            return compare(digest, inventory, wala, sootUp);
        } finally {
            snapshot.toFile().setWritable(true);
            Files.deleteIfExists(snapshot);
            Files.deleteIfExists(directory);
        }
    }

    private static Report compare(String digest, ArchiveInventory inventory,
                                  WalaHierarchyAnalyzer.Report wala,
                                  SootUpAnalyzer.AnalysisReport sootUp) {
        Map<String, WalaHierarchyAnalyzer.ClassFact> walaByName = new TreeMap<>();
        wala.applicationClasses().forEach(fact -> walaByName.put(fact.internalName(), fact));
        Map<String, SootUpAnalyzer.ClassSummary> sootByName = new TreeMap<>();
        sootUp.classes().forEach(fact -> sootByName.put(fact.internalName(), fact));

        Set<String> ambiguous = new TreeSet<>(inventory.duplicateIdentities());
        Set<String> comparable = new TreeSet<>(walaByName.keySet());
        comparable.retainAll(sootByName.keySet());
        comparable.removeAll(ambiguous);

        List<HierarchyDifference> differences = new ArrayList<>();
        for (String name : comparable) {
            WalaHierarchyAnalyzer.ClassFact wf = walaByName.get(name);
            SootUpAnalyzer.ClassSummary sf = sootByName.get(name);
            List<String> details = new ArrayList<>();
            if (!java.util.Objects.equals(wf.superclassInternalName(), sf.superclassInternalName())) {
                details.add("superclass: WALA=" + wf.superclassInternalName() + ", SootUp=" + sf.superclassInternalName());
            }
            if (!wf.directInterfaceInternalNames().equals(sf.interfaceInternalNames())) {
                details.add("direct interfaces: WALA=" + wf.directInterfaceInternalNames()
                        + ", SootUp=" + sf.interfaceInternalNames());
            }
            if (!details.isEmpty()) differences.add(new HierarchyDifference(name, details));
        }

        Set<String> missingHierarchy = new TreeSet<>();
        Set<String> walaResolved = Set.copyOf(wala.resolvedInternalNames());
        for (SootUpAnalyzer.ClassSummary type : sootUp.classes()) {
            if (type.superclassInternalName() != null && !walaResolved.contains(type.superclassInternalName())) {
                missingHierarchy.add(type.superclassInternalName());
            }
            for (String iface : type.interfaceInternalNames()) {
                if (!walaResolved.contains(iface)) missingHierarchy.add(iface);
            }
        }

        Set<String> walaNames = new TreeSet<>(walaByName.keySet());
        Set<String> sootNames = new TreeSet<>(sootByName.keySet());
        List<String> onlyWala = walaNames.stream().filter(name -> !sootNames.contains(name)).toList();
        List<String> onlySoot = sootNames.stream().filter(name -> !walaNames.contains(name)).toList();
        return new Report(digest, wala, sootUp, onlyWala, onlySoot,
                List.copyOf(ambiguous), inventory.malformedEntries(), wala.unresolvedInternalNames(),
                List.copyOf(missingHierarchy), differences, false);
    }

    private static ArchiveInventory inventory(JarArchive archive) {
        Map<String, Set<String>> pathsByIdentity = new TreeMap<>();
        List<String> malformed = new ArrayList<>();
        for (JarArchive.Entry entry : archive.entries()) {
            if (entry.directory() || !entry.name().endsWith(".class")
                    || entry.name().startsWith("META-INF/versions/")) continue;
            try {
                String internalName = new ClassReader(entry.content()).getClassName();
                pathsByIdentity.computeIfAbsent(internalName, ignored -> new TreeSet<>()).add(entry.name());
            } catch (RuntimeException exception) {
                malformed.add(entry.name() + ": " + exception.getClass().getSimpleName());
            }
        }
        Set<String> duplicates = new TreeSet<>();
        pathsByIdentity.forEach((identity, paths) -> {
            if (paths.size() > 1) duplicates.add(identity);
        });
        return new ArchiveInventory(List.copyOf(duplicates), malformed.stream().sorted().toList());
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private static void requireDigest(Path snapshot, String expected, String analyzer) throws IOException {
        if (!expected.equals(sha256(snapshot))) {
            throw new IOException(analyzer + " analysis changed the staged input snapshot");
        }
    }

    private record ArchiveInventory(List<String> duplicateIdentities, List<String> malformedEntries) { }
}
