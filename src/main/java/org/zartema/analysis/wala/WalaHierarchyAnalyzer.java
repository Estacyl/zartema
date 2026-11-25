package org.zartema.analysis.wala;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.ipa.callgraph.AnalysisScope;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.types.ClassLoaderReference;
import com.ibm.wala.types.TypeReference;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarFile;

/** Builds an independent WALA class hierarchy for a single, non-multi-release JAR. */
public final class WalaHierarchyAnalyzer {
    private WalaHierarchyAnalyzer() { }

    /** Three-valued result: missing hierarchy information is never treated as proof of NO. */
    public enum Assignability { YES, NO, UNKNOWN }

    /** Immutable application-class facts derived from WALA's class hierarchy. */
    public record ClassFact(
            String internalName,
            boolean interfaceType,
            String superclassInternalName,
            List<String> directInterfaceInternalNames,
            List<String> allSupertypeInternalNames) {
        public ClassFact {
            directInterfaceInternalNames = List.copyOf(directInterfaceInternalNames);
            allSupertypeInternalNames = List.copyOf(allSupertypeInternalNames);
        }
    }

    /**
     * A detached result: it contains no WALA objects and remains valid after the analysis scope
     * and its JAR handles have been closed.
     */
    public record Report(
            int hierarchyClassCount,
            List<ClassFact> applicationClasses,
            List<String> resolvedInternalNames,
            List<String> unresolvedInternalNames) {
        public Report {
            applicationClasses = List.copyOf(applicationClasses);
            resolvedInternalNames = resolvedInternalNames.stream().sorted().toList();
            unresolvedInternalNames = unresolvedInternalNames.stream().sorted().toList();
        }

        /** Returns whether WALA found {@code subtype} assignable to {@code supertype}. */
        public Assignability assignability(String subtype, String supertype) {
            ClassFact fact = applicationClasses.stream()
                    .filter(candidate -> candidate.internalName().equals(subtype))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown application type: " + subtype));
            if (subtype.equals(supertype) || fact.allSupertypeInternalNames().contains(supertype)) {
                return Assignability.YES;
            }
            if (Collections.binarySearch(resolvedInternalNames, supertype) < 0
                    || !unresolvedInternalNames.isEmpty()) {
                return Assignability.UNKNOWN;
            }
            return Assignability.NO;
        }
    }

    /**
     * Analyzes a JAR against the running JDK's primordial classes. The archive must not be a
     * multi-release JAR or contain physical versioned entries; WALA 1.8.0's bytecode scope is
     * built from physical archive entries and this adapter does not yet select a coherent
     * runtime-specific variant.
     */
    public static Report analyze(Path jarPath) throws IOException {
        if (!Files.isRegularFile(jarPath)) {
            throw new IOException("Input JAR is not a regular file: " + jarPath);
        }

        try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
            if (jar.isMultiRelease() || hasVersionedEntries(jar)) {
                throw new IOException("WALA hierarchy analysis does not yet support versioned JAR entries");
            }

            AnalysisScope scope = AnalysisScope.createJavaAnalysisScope();
            scope.addStdLibs(false, ClassLoaderReference.Primordial);
            scope.addToScope(ClassLoaderReference.Application, jar);

            final IClassHierarchy hierarchy;
            try {
                hierarchy = ClassHierarchyFactory.make(scope);
            } catch (RuntimeException | com.ibm.wala.ipa.cha.ClassHierarchyException exception) {
                throw new IOException("WALA could not construct the class hierarchy", exception);
            }

            var facts = new ArrayList<ClassFact>();
            var resolvedNames = new java.util.TreeSet<String>();
            for (IClass type : hierarchy) {
                resolvedNames.add(internalName(type.getReference()));
                if (!type.getClassLoader().getReference().equals(ClassLoaderReference.Application)) continue;
                IClass superclass = type.getSuperclass();
                facts.add(new ClassFact(
                        internalName(type.getReference()),
                        type.isInterface(),
                        superclass == null ? null : internalName(superclass.getReference()),
                        internalNames(type.getDirectInterfaces()),
                        allSupertypes(type)));
            }
            facts.sort(Comparator.comparing(ClassFact::internalName));

            List<String> unresolved = hierarchy.getUnresolvedClasses().stream()
                    .map(WalaHierarchyAnalyzer::internalName)
                    .sorted()
                    .toList();
            return new Report(hierarchy.getNumberOfClasses(), facts, List.copyOf(resolvedNames), unresolved);
        }
    }

    private static boolean hasVersionedEntries(JarFile jar) {
        return jar.stream().anyMatch(entry -> entry.getName().startsWith("META-INF/versions/"));
    }

    private static List<String> allSupertypes(IClass type) {
        var names = new java.util.TreeSet<String>();
        IClass current = type.getSuperclass();
        while (current != null) {
            names.add(internalName(current.getReference()));
            current = current.getSuperclass();
        }
        type.getAllImplementedInterfaces().stream()
                .map(parent -> internalName(parent.getReference()))
                .forEach(names::add);
        return List.copyOf(names);
    }

    private static List<String> internalNames(Collection<? extends IClass> types) {
        return types.stream().map(type -> internalName(type.getReference())).sorted().toList();
    }

    private static String internalName(TypeReference reference) {
        String name = reference.getName().toString();
        return name.startsWith("L") ? name.substring(1) : name;
    }
}
