package org.zartema.verify;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.net.URL;
import java.net.URLClassLoader;
import java.lang.management.ManagementFactory;

/** Child-process entrypoint. Application classes are defined by a loader parented only by the platform loader. */
public final class ForkedJarVerifier {
    private ForkedJarVerifier() { }

    public static void main(String[] arguments) {
        if (arguments.length < 1) {
            System.err.println("Missing application JAR argument");
            System.exit(2);
        }
        Path jar = Path.of(arguments[0]);
        List<String> binaryNames = new ArrayList<>();
        try (JarFile archive = new JarFile(jar.toFile(), false)) {
            Enumeration<JarEntry> entries = archive.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".class") || name.equals("module-info.class")) continue;
                if (name.startsWith("META-INF/versions/")) {
                    throw new IllegalArgumentException("Versioned JAR entry is not supported: " + name);
                }
                binaryNames.add(name.substring(0, name.length() - 6).replace('/', '.'));
            }
            binaryNames.sort(String::compareTo);
            URL[] urls = new URL[arguments.length];
            urls[0] = jar.toUri().toURL();
            for (int index = 1; index < arguments.length; index++) {
                urls[index] = Path.of(arguments[index]).toUri().toURL();
            }
            int loaded = 0;
            try (URLClassLoader applicationLoader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
                for (String binaryName : binaryNames) {
                    try {
                        // initialize=false is essential: verification/loading must never run user <clinit> code.
                        Class<?> loadedClass = Class.forName(binaryName, false, applicationLoader);
                        if (loadedClass.getClassLoader() != applicationLoader) {
                            throw new LinkageError("Application class was shadowed by parent loader: " + binaryName
                                    + " (defined by " + loadedClass.getClassLoader() + ")");
                        }
                        // Resolve member descriptors as well; reflection metadata lookup does not initialize the class.
                        loadedClass.getDeclaredFields();
                        loadedClass.getDeclaredConstructors();
                        loadedClass.getDeclaredMethods();
                        loaded++;
                    } catch (LinkageError | ClassNotFoundException | RuntimeException failure) {
                        emitFailure(binaryName, failure, loaded);
                        System.exit(1);
                        return;
                    }
                }
            }
            long maximumMetaspace = ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                    .filter(argument -> argument.startsWith("-XX:MaxMetaspaceSize="))
                    .map(argument -> argument.substring("-XX:MaxMetaspaceSize=".length()))
                    .mapToLong(ForkedJarVerifier::parseMemoryArgument).findFirst().orElse(-1);
            System.out.println("ZARTEMA_RESULT\tPASS\t" + loaded + "\t" + encode(Runtime.version().toString())
                    + "\t" + Runtime.getRuntime().maxMemory() + "\t" + maximumMetaspace);
        } catch (Throwable failure) {
            emitFailure("<archive>", failure, 0);
            System.exit(1);
        }
    }

    private static void emitFailure(String binaryName, Throwable failure, int loaded) {
        System.out.println("ZARTEMA_RESULT\tFAIL\t" + encode(binaryName) + "\t"
                + encode(failure.getClass().getName()) + "\t" + encode(String.valueOf(failure.getMessage()))
                + "\t" + loaded + "\t" + encode(Runtime.version().toString()));
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static long parseMemoryArgument(String value) {
        try { return Long.parseLong(value); } catch (NumberFormatException ignored) { return -1; }
    }
}
