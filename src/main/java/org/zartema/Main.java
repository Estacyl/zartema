package org.zartema;

import java.io.IOException;
import java.nio.file.Path;

/** Command-line entry point. */
public final class Main {
    private Main() { }
    public static void main(String[] args) {
        if (args.length != 2 || !args[0].equals("inspect")) {
            System.err.println("Usage: zartema inspect <input.jar>");
            System.exit(2);
        }
        try {
            var report = JarInspector.inspect(Path.of(args[1]));
            report.classes().forEach(c -> System.out.printf("%s  %s  major=%d%n", c.entryName(), c.internalName(), c.majorVersion()));
            System.out.printf("Classes: %d; resources: %d%n", report.classes().size(), report.resourceCount());
        } catch (IOException exception) {
            System.err.println("Inspection failed: " + exception.getMessage());
            System.exit(1);
        }
    }
}
