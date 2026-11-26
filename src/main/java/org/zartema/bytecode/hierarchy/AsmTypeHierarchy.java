package org.zartema.bytecode.hierarchy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;

/**
 * A deterministic, class-loader-free hierarchy for one effective class-loader view.
 * Names are resolved by application, dependency, then platform precedence; callers must
 * not combine definitions intended for mutually isolated class loaders or MR-JAR views.
 */
public final class AsmTypeHierarchy {
    private static final String OBJECT = "java/lang/Object";
    private static final String CLONEABLE = "java/lang/Cloneable";
    private static final String SERIALIZABLE = "java/io/Serializable";

    /** Three-valued outcomes prevent absent metadata from being reported as a negative fact. */
    public enum Answer { YES, NO, UNKNOWN }

    public enum Origin { APPLICATION, DEPENDENCY, PLATFORM }

    public record ClassInfo(String internalName, String superclassInternalName,
                            List<String> interfaceInternalNames, boolean interfaceType, Origin origin) {
        public ClassInfo {
            interfaceInternalNames = List.copyOf(interfaceInternalNames);
            Objects.requireNonNull(internalName, "internalName");
            Objects.requireNonNull(origin, "origin");
        }
    }

    public record Lookup(Answer answer, ClassInfo classInfo, String reason) { }
    public record Relation(Answer answer, String reason) { }
    public record CommonSuperClass(Answer answer, String internalName, String reason) { }

    private final Map<String, List<Definition>> applicationTypes;
    private final Map<String, List<Definition>> dependencyTypes;
    private final Map<String, List<Definition>> platformTypes;

    private AsmTypeHierarchy(Builder builder) {
        applicationTypes = immutableCopy(builder.applicationTypes);
        dependencyTypes = immutableCopy(builder.dependencyTypes);
        platformTypes = immutableCopy(builder.platformTypes);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Looks up a type using application, then dependency, then platform precedence. */
    public Lookup lookup(String internalName) {
        Objects.requireNonNull(internalName, "internalName");
        if (OBJECT.equals(internalName)) {
            return new Lookup(Answer.YES,
                    new ClassInfo(OBJECT, null, List.of(), false, Origin.PLATFORM), "intrinsic JVM root type");
        }
        Lookup app = from(applicationTypes, internalName, Origin.APPLICATION);
        if (app.answer() != Answer.NO) return app;
        Lookup dependency = from(dependencyTypes, internalName, Origin.DEPENDENCY);
        if (dependency.answer() != Answer.NO) return dependency;
        Lookup platform = from(platformTypes, internalName, Origin.PLATFORM);
        if (platform.answer() != Answer.NO) return platform;
        return new Lookup(Answer.NO, null, "type bytes were not supplied: " + internalName);
    }

    /** Returns whether {@code source} is assignable to {@code target}, or UNKNOWN if facts are missing. */
    public Relation isAssignableFrom(String target, String source) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(source, "source");
        if ((source.startsWith("[") && !isValidArrayDescriptor(source))
                || (target.startsWith("[") && !isValidArrayDescriptor(target))) {
            return new Relation(Answer.UNKNOWN, "malformed array descriptor");
        }
        if (target.equals(source)) return new Relation(Answer.YES, "identical type names");
        if (OBJECT.equals(target)) return new Relation(Answer.YES, "java/lang/Object is the root reference type");

        boolean sourceArray = source.startsWith("[");
        boolean targetArray = target.startsWith("[");
        if (sourceArray) {
            if (!targetArray && (CLONEABLE.equals(target) || SERIALIZABLE.equals(target))) {
                return new Relation(Answer.YES, "arrays implement Cloneable and Serializable");
            }
            if (!targetArray) return new Relation(Answer.NO, "array types are not assignable to this class or interface");
            return new Relation(Answer.UNKNOWN, "array component hierarchy is not indexed");
        }
        if (targetArray) {
            Lookup sourceLookup = lookup(source);
            return sourceLookup.answer() == Answer.YES
                    ? new Relation(Answer.NO, "ordinary class types are not assignable to array types")
                    : new Relation(Answer.UNKNOWN, "source type is unresolved while checking array assignability");
        }

        ArrayDeque<Path> pending = new ArrayDeque<>();
        pending.add(new Path(source, Set.of(source)));
        boolean incomplete = false;
        boolean found = false;
        Set<String> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            Path path = pending.removeFirst();
            String name = path.typeName();
            if (name.equals(target)) found = true;
            if (!visited.add(name)) continue;

            Lookup current = lookup(name);
            if (current.answer() != Answer.YES) {
                incomplete = true;
                continue;
            }
            ClassInfo info = current.classInfo();
            List<String> parents = new ArrayList<>(info.interfaceInternalNames());
            if (info.superclassInternalName() != null) parents.add(info.superclassInternalName());
            parents.sort(String::compareTo);
            for (String parent : parents) {
                if (path.ancestors().contains(parent)) {
                    incomplete = true;
                    continue;
                }
                if (parent.equals(target)) found = true;
                Set<String> ancestry = new HashSet<>(path.ancestors());
                ancestry.add(parent);
                pending.addLast(new Path(parent, ancestry));
            }
        }
        if (incomplete) return new Relation(Answer.UNKNOWN, "one or more supertype paths are unresolved or cyclic");
        if (found) return new Relation(Answer.YES, "target is a known supertype");
        return new Relation(Answer.NO, "all supplied supertype paths were examined");
    }

    /** Finds a safe common reference type. Unknown hierarchy facts are returned as UNKNOWN. */
    public CommonSuperClass commonSuperClass(String first, String second) {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(second, "second");
        if ((first.startsWith("[") && !isValidArrayDescriptor(first))
                || (second.startsWith("[") && !isValidArrayDescriptor(second))) {
            return unknown("malformed array descriptor");
        }
        if (first.equals(second)) return resolved(first, "identical type names");
        if (OBJECT.equals(first) || OBJECT.equals(second)) return resolved(OBJECT, "java/lang/Object is a common root");

        if (first.startsWith("[") || second.startsWith("[")) {
            if (first.startsWith("[") && second.startsWith("[")) {
                ArrayComponent a = arrayComponent(first);
                ArrayComponent b = arrayComponent(second);
                if (a == null || b == null) return unknown("malformed array descriptor in frame merge");
                if (!a.reference() || !b.reference()) {
                    return resolved(OBJECT, "distinct primitive component arrays merge to Object");
                }
                CommonSuperClass component = commonSuperClass(a.typeName(), b.typeName());
                if (component.answer() != Answer.YES) return unknown("array component merge is unresolved: " + component.reason());
                String merged = component.internalName().startsWith("[")
                        ? "[" + component.internalName()
                        : "[L" + component.internalName() + ";";
                return resolved(merged, "recursively merged reference array components");
            }
            String other = first.startsWith("[") ? second : first;
            String array = first.startsWith("[") ? first : second;
            Relation assignable = isAssignableFrom(other, array);
            if (assignable.answer() == Answer.YES) return resolved(other, assignable.reason());
            if (assignable.answer() == Answer.UNKNOWN) return unknown("array merge depends on unresolved type metadata");
            return resolved(OBJECT, "Object is a safe common supertype for array and non-array references");
        }

        Relation firstFromSecond = isAssignableFrom(first, second);
        if (firstFromSecond.answer() == Answer.YES) return resolved(first, firstFromSecond.reason());
        Relation secondFromFirst = isAssignableFrom(second, first);
        if (secondFromFirst.answer() == Answer.YES) return resolved(second, secondFromFirst.reason());
        if (firstFromSecond.answer() == Answer.UNKNOWN || secondFromFirst.answer() == Answer.UNKNOWN) {
            return unknown("assignability could not be decided from supplied class bytes");
        }

        Lookup firstInfo = lookup(first);
        Lookup secondInfo = lookup(second);
        if (firstInfo.answer() != Answer.YES || secondInfo.answer() != Answer.YES) {
            return unknown("one or both merge types are not indexed");
        }
        if (firstInfo.classInfo().interfaceType() || secondInfo.classInfo().interfaceType()) {
            return resolved(OBJECT, "incomparable interface merge uses Object as a safe supertype");
        }

        List<String> firstAncestors = classAncestors(first);
        if (firstAncestors == null) return unknown("first class has an unresolved or cyclic superclass chain");
        Set<String> firstSet = Set.copyOf(firstAncestors);
        List<String> secondAncestors = classAncestors(second);
        if (secondAncestors == null) return unknown("second class has an unresolved or cyclic superclass chain");
        String commonClass = secondAncestors.stream().filter(firstSet::contains).findFirst().orElse(null);
        if (commonClass == null) return unknown("class chains have no indexed common root");

        return resolved(commonClass, "nearest common class ancestor");
    }

    private List<String> classAncestors(String typeName) {
        List<String> ancestors = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String current = typeName;
        while (current != null) {
            if (!seen.add(current)) return null;
            ancestors.add(current);
            if (OBJECT.equals(current)) return ancestors;
            Lookup lookup = lookup(current);
            if (lookup.answer() != Answer.YES || lookup.classInfo().interfaceType()) return null;
            current = lookup.classInfo().superclassInternalName();
        }
        return null;
    }

    private static Lookup from(Map<String, List<Definition>> types, String name, Origin origin) {
        List<Definition> matches = types.get(name);
        if (matches == null) return new Lookup(Answer.NO, null, "not present in " + origin.name().toLowerCase());
        if (matches.size() != 1) return new Lookup(Answer.UNKNOWN, null, "byte-distinct definitions in " + origin.name().toLowerCase());
        return new Lookup(Answer.YES, matches.get(0).info(), "resolved from " + origin.name().toLowerCase());
    }

    private static Map<String, List<Definition>> immutableCopy(Map<String, List<Definition>> source) {
        Map<String, List<Definition>> copy = new TreeMap<>();
        source.forEach((name, values) -> copy.put(name, List.copyOf(values)));
        return Map.copyOf(copy);
    }

    private static ArrayComponent arrayComponent(String descriptor) {
        if (descriptor.length() < 2 || descriptor.charAt(0) != '[') return null;
        String component = descriptor.substring(1);
        if (component.startsWith("[")) return new ArrayComponent(true, component);
        if (component.length() == 1 && "ZCBSIFJD".contains(component)) return new ArrayComponent(false, null);
        if (component.startsWith("L") && component.endsWith(";") && component.length() > 2
                && component.indexOf(';') == component.length() - 1) {
            return new ArrayComponent(true, component.substring(1, component.length() - 1));
        }
        return null;
    }

    private static boolean isValidArrayDescriptor(String descriptor) {
        int dimensions = 0;
        while (dimensions < descriptor.length() && descriptor.charAt(dimensions) == '[') dimensions++;
        if (dimensions == 0 || dimensions > 255 || dimensions == descriptor.length()) return false;
        String component = descriptor.substring(dimensions);
        if (component.length() == 1) return "ZCBSIFJD".contains(component);
        if (component.charAt(0) != 'L' || component.charAt(component.length() - 1) != ';') return false;
        String name = component.substring(1, component.length() - 1);
        return !name.isEmpty() && name.indexOf('.') < 0 && name.indexOf('[') < 0
                && name.indexOf(';') < 0 && !name.startsWith("/") && !name.endsWith("/")
                && !name.contains("//");
    }

    private static CommonSuperClass resolved(String name, String reason) {
        return new CommonSuperClass(Answer.YES, name, reason);
    }

    private static CommonSuperClass unknown(String reason) {
        return new CommonSuperClass(Answer.UNKNOWN, null, reason);
    }

    private record Path(String typeName, Set<String> ancestors) { }
    private record ArrayComponent(boolean reference, String typeName) { }
    private record Definition(ClassInfo info, byte[] bytes) {
        private Definition { bytes = bytes.clone(); }
    }

    public static final class Builder {
        private final Map<String, List<Definition>> applicationTypes = new TreeMap<>();
        private final Map<String, List<Definition>> dependencyTypes = new TreeMap<>();
        private final Map<String, List<Definition>> platformTypes = new TreeMap<>();

        private Builder() { }

        public Builder addApplicationClass(byte[] classBytes) {
            add(applicationTypes, classBytes, Origin.APPLICATION);
            return this;
        }

        public Builder addDependencyClass(byte[] classBytes) {
            add(dependencyTypes, classBytes, Origin.DEPENDENCY);
            return this;
        }

        public Builder addPlatformClass(byte[] classBytes) {
            add(platformTypes, classBytes, Origin.PLATFORM);
            return this;
        }

        public Builder addApplicationClasses(Collection<byte[]> classBytes) {
            classBytes.forEach(this::addApplicationClass);
            return this;
        }

        public Builder addDependencyClasses(Collection<byte[]> classBytes) {
            classBytes.forEach(this::addDependencyClass);
            return this;
        }

        public AsmTypeHierarchy build() {
            return new AsmTypeHierarchy(this);
        }

        private static void add(Map<String, List<Definition>> destination, byte[] bytes, Origin origin) {
            Objects.requireNonNull(bytes, "classBytes");
            ClassReader reader = new ClassReader(bytes);
            List<String> interfaces = Arrays.stream(reader.getInterfaces()).sorted().toList();
            ClassInfo info = new ClassInfo(reader.getClassName(), reader.getSuperName(), interfaces,
                    (reader.getAccess() & Opcodes.ACC_INTERFACE) != 0, origin);
            List<Definition> definitions = destination.computeIfAbsent(info.internalName(), ignored -> new ArrayList<>());
            if (definitions.stream().noneMatch(existing -> Arrays.equals(existing.bytes(), bytes))) {
                definitions.add(new Definition(info, bytes));
            }
        }
    }
}
