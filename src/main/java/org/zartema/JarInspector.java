package org.zartema;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.zartema.archive.JarArchive;
import org.zartema.archive.MultiReleaseJarView;

/** Reads physical JAR entries without changing the input. */
public final class JarInspector {
    private JarInspector() { }

    public record ClassInfo(String entryName, String internalName, int majorVersion) { }
    public record Report(List<ClassInfo> classes, int resourceCount) {
        public Report { classes = List.copyOf(classes); }
    }

    public record EffectiveClassInfo(String logicalName, String physicalName, String internalName, int majorVersion) { }
    public record EffectiveReport(int targetRelease, boolean multiRelease, List<EffectiveClassInfo> classes,
                                  int resourceCount) {
        public EffectiveReport { classes = List.copyOf(classes); }
    }

    /** Selects a logical classpath view; this does not validate multi-release JAR conformance. */
    public static EffectiveReport inspectEffective(Path path, int targetRelease) throws IOException {
        var view = MultiReleaseJarView.select(JarArchive.read(path), targetRelease);
        var classes = new ArrayList<EffectiveClassInfo>();
        int resources = 0;
        for (var entry : view.entries()) {
            if (!entry.logicalName().endsWith(".class") || entry.logicalName().startsWith("META-INF/")) {
                resources++;
                continue;
            }
            try {
                var reader = new ClassReader(entry.entry().content());
                classes.add(new EffectiveClassInfo(entry.logicalName(), entry.physicalName(),
                        reader.getClassName(), reader.readUnsignedShort(6)));
            } catch (RuntimeException exception) {
                throw new IOException("Cannot parse class entry: " + entry.physicalName(), exception);
            }
        }
        return new EffectiveReport(targetRelease, view.multiRelease(), classes, resources);
    }

    /** Inspects class headers; this is not full class-file or JVM verification. */
    public static Report inspect(Path path) throws IOException {
        var classes = new ArrayList<ClassInfo>();
        int resources = 0;
        for (var entry : JarArchive.read(path).entries()) {
            if (entry.directory()) continue;
            if (!entry.name().endsWith(".class")) { resources++; continue; }
            try {
                var reader = new ClassReader(entry.content());
                classes.add(new ClassInfo(entry.name(), reader.getClassName(), reader.readUnsignedShort(6)));
            } catch (RuntimeException exception) {
                throw new IOException("Cannot parse class entry: " + entry.name(), exception);
            }
        }
        classes.sort(Comparator.comparing(ClassInfo::entryName));
        return new Report(classes, resources);
    }
}
