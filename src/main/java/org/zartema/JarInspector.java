package org.zartema;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;

/** Reads physical JAR entries without changing the input. */
public final class JarInspector {
    private JarInspector() { }

    public record ClassInfo(String entryName, String internalName, int majorVersion) { }
    public record Report(List<ClassInfo> classes, int resourceCount) {
        public Report { classes = List.copyOf(classes); }
    }

    /** Inspects class headers; this is not full class-file or JVM verification. */
    public static Report inspect(Path path) throws IOException {
        var classes = new ArrayList<ClassInfo>();
        int resources = 0;
        try (var jar = new JarFile(path.toFile(), false)) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                if (!entry.getName().endsWith(".class")) { resources++; continue; }
                try (var stream = jar.getInputStream(entry)) {
                    byte[] bytes = stream.readAllBytes();
                    var reader = new ClassReader(bytes);
                    classes.add(new ClassInfo(entry.getName(), reader.getClassName(), reader.readUnsignedShort(6)));
                } catch (RuntimeException exception) {
                    throw new IOException("Cannot parse class entry: " + entry.getName(), exception);
                }
            }
        }
        classes.sort(Comparator.comparing(ClassInfo::entryName));
        return new Report(classes, resources);
    }
}
