package org.zartema.transform;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import org.objectweb.asm.Attribute;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.zartema.archive.JarArchive;
import org.zartema.mapping.NameMapping;
import org.zartema.verify.AsmClassValidator;
import org.zartema.transform.keep.KeepRuleSet;

/** An explicitly opted-in, closed-world private static method renaming pass. */
public final class PrivateMethodRenamer {
    private PrivateMethodRenamer() { }

    /** A method identity using an internal class name and JVM descriptor. */
    public record MethodSelector(String owner, String name, String descriptor) {
        public MethodSelector { Objects.requireNonNull(owner); Objects.requireNonNull(name); Objects.requireNonNull(descriptor); }
    }
    /** One selected method and the effective keep-rule outcome. */
    public record Decision(MethodSelector method, boolean renamed, List<String> reasons) {
        public Decision { reasons = List.copyOf(reasons); }
    }
    public record Result(JarArchive archive, NameMapping mapping, List<Decision> decisions) {
        public Result { decisions = List.copyOf(decisions); }
    }
    private record MethodInfo(MethodSelector selector, int access) { }
    private record ClassInfo(String entryName, byte[] bytes, String name, List<MethodInfo> methods, String nestHost, List<String> nestMembers) { }

    /**
     * Renames selected private static non-native methods to fresh archive-wide names.
     * The caller must affirm that the archive contains all direct bytecode callers and that
     * no name-sensitive consumers (including reflection anywhere, native code, agents and
     * configuration) rely on the selected names. Signed or digest-bearing archives and versioned entries are refused.
     * All classes are checked by ASM before and after the pass; this is not JVM verification
     * or proof of behavioral equivalence. No input or output file is modified by this method.
     */
    public static Result rename(JarArchive archive, List<MethodSelector> requested,
                                boolean closedWorldAndNameSensitiveUsesExcluded) throws IOException {
        return rename(archive, requested, closedWorldAndNameSensitiveUsesExcluded, KeepRuleSet.builder().build());
    }

    /** Applies exact keep rules before renaming; each selected method receives a decision. */
    public static Result rename(JarArchive archive, List<MethodSelector> requested,
                                boolean closedWorldAndNameSensitiveUsesExcluded, KeepRuleSet keepRules) throws IOException {
        Objects.requireNonNull(keepRules, "keepRules");
        Objects.requireNonNull(archive, "archive");
        Objects.requireNonNull(requested, "requested");
        if (!closedWorldAndNameSensitiveUsesExcluded)
            throw new IllegalArgumentException("Explicit closed-world and name-sensitive-use assumptions are required");
        if (requested.isEmpty()) throw new IllegalArgumentException("At least one method must be selected");
        Set<MethodSelector> selections = new HashSet<>(requested);
        if (selections.size() != requested.size()) throw new IllegalArgumentException("Duplicate method selection");
        var classes = new ArrayList<ClassInfo>();
        Set<String> names = new HashSet<>();
        Set<String> usedMethodNames = new HashSet<>();
        for (JarArchive.Entry entry : archive.entries()) {
            if (entry.name().startsWith("META-INF/versions/"))
                throw new IOException("Versioned archive entries are not supported by this pass");
            if (entry.name().equalsIgnoreCase("META-INF/MANIFEST.MF")) {
                Manifest manifest = new Manifest(new ByteArrayInputStream(entry.content()));
                if ("true".equalsIgnoreCase(manifest.getMainAttributes().getValue(Attributes.Name.MULTI_RELEASE)))
                    throw new IOException("Multi-release archives are not supported by this pass");
            }
            if (entry.directory() || !entry.name().endsWith(".class")) continue;
            byte[] bytes = entry.content();
            AsmClassValidator.validate(bytes);
            ClassReader reader = new ClassReader(bytes);
            String owner = reader.getClassName();
            if (!entry.name().equals(owner + ".class")) throw new IOException("Class entry name does not match its internal name: " + entry.name());
            if (!names.add(owner)) throw new IOException("Duplicate class name: " + owner);
            var methods = new ArrayList<MethodInfo>();
            var unsupported = new boolean[1];
            var nestHost = new String[1];
            var nestMembers = new ArrayList<String>();
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public void visitAttribute(Attribute attr) { unsupported[0] = true; }
                @Override public void visitNestHost(String host) { nestHost[0] = host; }
                @Override public void visitNestMember(String member) { nestMembers.add(member); }
                @Override public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                    return new FieldVisitor(Opcodes.ASM9) { @Override public void visitAttribute(Attribute attr) { unsupported[0] = true; } };
                }
                @Override public RecordComponentVisitor visitRecordComponent(String name, String descriptor, String signature) {
                    return new RecordComponentVisitor(Opcodes.ASM9) { @Override public void visitAttribute(Attribute attr) { unsupported[0] = true; } };
                }
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    methods.add(new MethodInfo(new MethodSelector(owner, name, descriptor), access));
                    usedMethodNames.add(name);
                    return new MethodVisitor(Opcodes.ASM9) { @Override public void visitAttribute(Attribute attr) { unsupported[0] = true; } };
                }
            }, 0);
            if (unsupported[0]) throw new IOException("Unsupported custom class-file attribute in " + owner);
            classes.add(new ClassInfo(entry.name(), bytes, owner, List.copyOf(methods), nestHost[0], List.copyOf(nestMembers)));
        }
        var available = new HashMap<MethodSelector, MethodInfo>();
        for (ClassInfo c : classes) for (MethodInfo m : c.methods) {
            if (available.putIfAbsent(m.selector, m) != null) throw new IOException("Duplicate method: " + m.selector);
        }
        List<Decision> decisions = new ArrayList<>();
        List<MethodSelector> requestedOrder = selections.stream().sorted(Comparator.comparing(MethodSelector::owner)
                .thenComparing(MethodSelector::name).thenComparing(MethodSelector::descriptor)).toList();
        for (MethodSelector selection : requestedOrder) {
            MethodInfo method = available.get(selection);
            if (method == null) throw new IOException("Selected method does not exist: " + selection);
            List<String> reasons = keepRules.matchingRules(selection.owner, selection.name, selection.descriptor)
                    .stream().map(KeepRuleSet.Rule::reason).toList();
            if (!reasons.isEmpty()) {
                decisions.add(new Decision(selection, false, reasons));
                selections.remove(selection);
                continue;
            }
            decisions.add(new Decision(selection, true, List.of()));
            int access = method.access;
            if ((access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) != (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)
                    || (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || selection.name.startsWith("<"))
                throw new IOException("Selected method is not a private static non-native method: " + selection);
        }
        // Every declared member of a selected method's nest must be present. This
        // cannot prove that arbitrary external callers or dynamic lookups are absent.
        Map<String, ClassInfo> classesByName = new HashMap<>();
        for (ClassInfo c : classes) classesByName.put(c.name, c);
        for (MethodSelector selection : selections) {
            ClassInfo selected = classesByName.get(selection.owner);
            String hostName = selected.nestHost == null ? selected.name : selected.nestHost;
            ClassInfo host = classesByName.get(hostName);
            if (host == null || host.nestHost != null || (!hostName.equals(selected.name) && !host.nestMembers.contains(selected.name)))
                throw new IOException("Selected method has an unresolved or inconsistent nest: " + selection.owner);
            for (String memberName : host.nestMembers) {
                ClassInfo member = classesByName.get(memberName);
                if (member == null || !hostName.equals(member.nestHost))
                    throw new IOException("Selected method has an external or inconsistent nest member: " + memberName);
            }
        }
        // A symbolic reference can resolve through a different owner. Without complete
        // resolution evidence, conservatively reject matching names on other owners.
        for (ClassInfo c : classes) {
            var aliases = new ArrayList<String>();
            new ClassReader(c.bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                            checkReference(owner, name, descriptor, selections, aliases);
                        }
                        @Override public void visitLdcInsn(Object value) { checkConstant(value, selections, aliases); }
                        @Override public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap, Object... args) {
                            checkConstant(bootstrap, selections, aliases);
                            for (Object argument : args) checkConstant(argument, selections, aliases);
                        }
                    };
                }
            }, 0);
            if (!aliases.isEmpty()) throw new IOException("Unresolved symbolic method owner in " + c.name + ": " + aliases.get(0));
        }
        var renames = new HashMap<MethodSelector, String>();
        var ordered = selections.stream().sorted(Comparator.comparing(MethodSelector::owner)
                .thenComparing(MethodSelector::name).thenComparing(MethodSelector::descriptor)).toList();
        int next = 0;
        for (MethodSelector method : ordered) {
            String candidate;
            do { candidate = "$z" + next++; } while (usedMethodNames.contains(candidate));
            usedMethodNames.add(candidate);
            renames.put(method, candidate);
        }
        var mapping = NameMapping.builder();
        classes.stream().map(ClassInfo::name).sorted().forEach(name -> mapping.mapClass(name, name));
        for (ClassInfo c : classes) for (MethodInfo m : c.methods)
            mapping.mapMethod(c.name, m.selector.name, m.selector.descriptor, renames.getOrDefault(m.selector, m.selector.name));
        NameMapping resultMapping = mapping.build();
        Remapper remapper = new Remapper() {
            @Override public String mapMethodName(String owner, String name, String descriptor) {
                return renames.getOrDefault(new MethodSelector(owner, name, descriptor), name);
            }
        };
        JarArchive output = archive;
        for (ClassInfo c : classes) {
            if (renames.isEmpty()) break;
            ClassReader reader = new ClassReader(c.bytes);
            ClassWriter writer = new ClassWriter(reader, 0);
            reader.accept(new ClassRemapper(writer, remapper), 0);
            byte[] transformed = writer.toByteArray();
            AsmClassValidator.validate(transformed);
            if (!java.util.Arrays.equals(c.bytes, transformed)) output = output.withEntryContent(c.entryName, transformed);
        }
        return new Result(output, resultMapping, decisions);
    }
    private static void checkReference(String owner, String name, String descriptor, Set<MethodSelector> selections, List<String> aliases) {
        if (selections.contains(new MethodSelector(owner, name, descriptor))) return;
        for (MethodSelector selected : selections) {
            if (selected.name.equals(name) && selected.descriptor.equals(descriptor)) {
                aliases.add(owner + "." + name + descriptor);
                return;
            }
        }
    }
    private static void checkConstant(Object value, Set<MethodSelector> selections, List<String> aliases) {
        if (value instanceof Handle handle) {
            int tag = handle.getTag();
            if (tag >= Opcodes.H_INVOKEVIRTUAL && tag <= Opcodes.H_INVOKEINTERFACE)
                checkReference(handle.getOwner(), handle.getName(), handle.getDesc(), selections, aliases);
        } else if (value instanceof ConstantDynamic dynamic) {
            checkConstant(dynamic.getBootstrapMethod(), selections, aliases);
            for (int i = 0; i < dynamic.getBootstrapMethodArgumentCount(); i++)
                checkConstant(dynamic.getBootstrapMethodArgument(i), selections, aliases);
        }
    }

}
