package org.zartema.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarFile;
import sootup.core.graph.ControlFlowGraph;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.SootClass;
import sootup.core.model.SootMethod;
import sootup.core.model.SourceType;
import sootup.core.views.View;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.core.views.JavaView;

/** Performs read-only Jimple and control-flow analysis of application classes in a JAR. */
public final class SootUpAnalyzer {
    private SootUpAnalyzer() { }

    /** Immutable class-level analysis facts. Names use JVM internal-name form. */
    public record ClassSummary(String internalName, String superclassInternalName,
                               List<String> interfaceInternalNames, List<MethodSummary> methods) {
        public ClassSummary {
            interfaceInternalNames = List.copyOf(interfaceInternalNames);
            methods = List.copyOf(methods);
        }
    }

    /** Immutable method facts lifted from SootUp's Jimple body and CFG. */
    public record MethodSummary(String signature, boolean hasBody, long statementCount,
                                long invocationCount, long controlFlowEdgeCount) { }

    /** Aggregated SootUp analysis; this is analysis evidence and never an emission model. */
    public record AnalysisReport(List<ClassSummary> classes, int methodCount, int concreteMethodCount,
                                 long statementCount, long invocationCount, long controlFlowEdgeCount) {
        public AnalysisReport {
            classes = List.copyOf(classes);
        }
        public int classCount() { return classes.size(); }
    }

    /**
     * Loads the supplied JAR as application input and computes Jimple/CFG metrics.
     * Multi-release JARs and any archive with physical {@code META-INF/versions/} entries
     * are rejected until target-release selection is implemented.
     *
     * @param jarPath input JAR path
     * @return deterministic class and method summaries
     * @throws IOException if the input is absent, not a regular file, or cannot be analyzed
     */
    public static AnalysisReport analyze(Path jarPath) throws IOException {
        if (jarPath == null || !Files.isRegularFile(jarPath)) {
            throw new IOException("SootUp input must be an existing regular JAR file: " + jarPath);
        }

        try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
            if (jar.isMultiRelease() || jar.stream()
                    .anyMatch(entry -> entry.getName().startsWith("META-INF/versions/"))) {
                throw new IOException("SootUp analysis does not yet support versioned JAR entries");
            }
        }

        AnalysisInputLocation input = new JavaClassPathAnalysisInputLocation(
                jarPath.toAbsolutePath().normalize().toString(), SourceType.Application);
        try (input) {
            return analyzeView(new JavaView(input));
        } catch (Exception exception) {
            throw new IOException("SootUp could not analyze JAR: " + jarPath, exception);
        }
    }

    private static AnalysisReport analyzeView(View view) {
        List<ClassSummary> classes = new ArrayList<>();
        int methodCount = 0;
        int concreteMethodCount = 0;
        long statementCount = 0;
        long invocationCount = 0;
        long controlFlowEdgeCount = 0;

        List<? extends SootClass> sootClasses = view.getClasses().sorted(
                Comparator.comparing(type -> toInternalName(type.getType().getFullyQualifiedName()))).toList();
        for (SootClass sootClass : sootClasses) {
            List<String> interfaces = sootClass.getInterfaces().stream()
                    .map(type -> toInternalName(type.getFullyQualifiedName())).sorted().toList();
            String superclass = sootClass.getSuperclass()
                    .map(type -> toInternalName(type.getFullyQualifiedName())).orElse(null);
            List<? extends SootMethod> methods = sootClass.getMethods().stream()
                    .sorted(Comparator.comparing(method -> method.getSignature().toString())).toList();
            List<MethodSummary> methodSummaries = new ArrayList<>();

            for (SootMethod method : methods) {
                methodCount++;
                if (!method.hasBody()) {
                    methodSummaries.add(new MethodSummary(method.getSignature().toString(), false, 0, 0, 0));
                    continue;
                }

                concreteMethodCount++;
                ControlFlowGraph<?> cfg = method.getBody().getControlFlowGraph();
                long methodStatements = cfg.getNodes().size();
                long methodInvocations = 0;
                long methodEdges = 0;
                for (Stmt statement : cfg.getNodes()) {
                    if (statement.isInvokableStmt()
                            && statement.asInvokableStmt().getInvokeExpr().isPresent()) {
                        methodInvocations++;
                    }
                    methodEdges += cfg.successors(statement).size();
                    methodEdges += cfg.exceptionalSuccessors(statement).size();
                }
                statementCount += methodStatements;
                invocationCount += methodInvocations;
                controlFlowEdgeCount += methodEdges;
                methodSummaries.add(new MethodSummary(method.getSignature().toString(), true,
                        methodStatements, methodInvocations, methodEdges));
            }

            classes.add(new ClassSummary(toInternalName(sootClass.getType().getFullyQualifiedName()),
                    superclass, interfaces, methodSummaries));
        }
        return new AnalysisReport(classes, methodCount, concreteMethodCount, statementCount,
                invocationCount, controlFlowEdgeCount);
    }

    private static String toInternalName(String fullyQualifiedName) {
        return fullyQualifiedName.replace('.', '/');
    }
}
