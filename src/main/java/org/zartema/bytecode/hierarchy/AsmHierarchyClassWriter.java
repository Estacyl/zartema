package org.zartema.bytecode.hierarchy;

import java.util.Objects;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

/** ASM writer whose frame merges use explicit class bytes and reject unresolved hierarchies. */
public final class AsmHierarchyClassWriter extends ClassWriter {
    private final AsmTypeHierarchy hierarchy;

    public AsmHierarchyClassWriter(int flags, AsmTypeHierarchy hierarchy) {
        super(flags);
        this.hierarchy = Objects.requireNonNull(hierarchy, "hierarchy");
    }

    public AsmHierarchyClassWriter(ClassReader reader, int flags, AsmTypeHierarchy hierarchy) {
        super(reader, flags);
        this.hierarchy = Objects.requireNonNull(hierarchy, "hierarchy");
    }

    @Override
    protected String getCommonSuperClass(String type1, String type2) {
        AsmTypeHierarchy.CommonSuperClass result = hierarchy.commonSuperClass(type1, type2);
        if (result.answer() != AsmTypeHierarchy.Answer.YES) {
            throw new IllegalStateException("ASM frame merge has unresolved hierarchy for " + type1 + " and " + type2
                    + ": " + result.reason());
        }
        return result.internalName();
    }
}
