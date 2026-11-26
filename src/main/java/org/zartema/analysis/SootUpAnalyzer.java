package org.zartema.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.jar.JarFile;
import sootup.core.graph.ControlFlowGraph;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.jimple.common.expr.AbstractInvokeExpr;
import sootup.core.jimple.common.constant.StringConstant;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.SootClass;
import sootup.core.model.SootMethod;
import sootup.core.model.SourceType;
import sootup.core.signatures.MethodSignature;
import sootup.core.types.ArrayType;
import sootup.core.types.ClassType;
import sootup.core.types.Type;
import sootup.core.views.View;
import sootup.java.bytecode.frontend.inputlocation.JavaClassPathAnalysisInputLocation;
import sootup.java.core.views.JavaView;

/** Performs read-only Jimple and control-flow analysis of application classes in a JAR. */
public final class SootUpAnalyzer {
    private SootUpAnalyzer() { }

    private static final List<SinkPattern> REFLECTION_SINKS = List.of(
            new SinkPattern("java/lang/Class", "getMethod",
                    "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", 0),
            new SinkPattern("java/lang/Class", "getDeclaredMethod",
                    "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", 0),
            new SinkPattern("java/lang/invoke/MethodHandles$Lookup", "findVirtual",
                    "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", 1),
            new SinkPattern("java/lang/invoke/MethodHandles$Lookup", "findStatic",
                    "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", 1),
            new SinkPattern("java/lang/invoke/MethodHandles$Lookup", "findSpecial",
                    "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;", 1),
            new SinkPattern("java/lang/invoke/MethodHandles$Lookup", "findGetter",
                    "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;", 1),
            new SinkPattern("java/lang/invoke/MethodHandles$Lookup", "findSetter",
                    "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;", 1),
            new SinkPattern("java/lang/invoke/MethodHandles$Lookup", "findStaticGetter",
                    "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;", 1),
            new SinkPattern("java/lang/invoke/MethodHandles$Lookup", "findStaticSetter",
                    "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;", 1),
            new SinkPattern("java/lang/invoke/MethodHandles$Lookup", "findVarHandle",
                    "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;", 1),
            new SinkPattern("java/lang/invoke/MethodHandles$Lookup", "findStaticVarHandle",
                    "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;", 1));

    private record SinkPattern(String owner, String name, String descriptor, int memberNameArgumentIndex) { }

    /**
     * Engine-owned evidence for a recognized method-name lookup in the analyzed Jimple view.
     * An empty {@code constantMemberName} means the corresponding argument was not a direct
     * string literal. No copy/field/parameter propagation is attempted, and this inventory does
     * not prove that other reflective or name-sensitive uses are absent. The sink descriptor
     * and argument index identify the exact recognized JVM API shape. Results are sorted by
     * caller and sink identity; this record is not a bytecode relocation identifier.
     */
    public record ReflectionSinkEvidence(String callerClassInternalName, String callerMethodSignature,
                                         String sinkOwnerInternalName, String sinkMethodName,
                                         String sinkMethodDescriptor, int memberNameArgumentIndex,
                                         Optional<String> constantMemberName) {
        public ReflectionSinkEvidence {
            Objects.requireNonNull(callerClassInternalName, "callerClassInternalName");
            Objects.requireNonNull(callerMethodSignature, "callerMethodSignature");
            Objects.requireNonNull(sinkOwnerInternalName, "sinkOwnerInternalName");
            Objects.requireNonNull(sinkMethodName, "sinkMethodName");
            Objects.requireNonNull(sinkMethodDescriptor, "sinkMethodDescriptor");
            Objects.requireNonNull(constantMemberName, "constantMemberName");
            if (memberNameArgumentIndex < 0) throw new IllegalArgumentException("Member-name argument index must be nonnegative");
        }
    }

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
                                 long statementCount, long invocationCount, long controlFlowEdgeCount,
                                 List<ReflectionSinkEvidence> reflectionSinks) {
        public AnalysisReport {
            classes = List.copyOf(classes);
            reflectionSinks = reflectionSinks.stream().sorted(Comparator
                    .comparing(ReflectionSinkEvidence::callerClassInternalName)
                    .thenComparing(ReflectionSinkEvidence::callerMethodSignature)
                    .thenComparing(ReflectionSinkEvidence::sinkOwnerInternalName)
                    .thenComparing(ReflectionSinkEvidence::sinkMethodName)
                    .thenComparing(ReflectionSinkEvidence::sinkMethodDescriptor)
                    .thenComparing(evidence -> evidence.constantMemberName().isPresent() ? "1" : "0")
                    .thenComparing(evidence -> evidence.constantMemberName().orElse(""))).toList();
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
        List<ReflectionSinkEvidence> reflectionSinks = new ArrayList<>();

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
                var body = method.getBody();
                ControlFlowGraph<?> cfg = body.getControlFlowGraph();
                collectReflectionSinks(sootClass, method, body.getStmts(), reflectionSinks);
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
                invocationCount, controlFlowEdgeCount, reflectionSinks);
    }

    private static void collectReflectionSinks(SootClass callerClass, SootMethod callerMethod,
                                               List<Stmt> statements,
                                               List<ReflectionSinkEvidence> destination) {
        for (Stmt statement : statements) {
            if (!statement.isInvokableStmt()) continue;
            Optional<AbstractInvokeExpr> invocation = statement.asInvokableStmt().getInvokeExpr();
            if (invocation.isEmpty()) continue;
            AbstractInvokeExpr expression = invocation.get();
            MethodSignature signature = expression.getMethodSignature();
            String owner = toInternalName(signature.getDeclClassType().getFullyQualifiedName());
            String descriptor = methodDescriptor(signature);
            if (descriptor == null) continue;
            SinkPattern pattern = REFLECTION_SINKS.stream()
                    .filter(candidate -> candidate.owner().equals(owner)
                            && candidate.name().equals(signature.getName())
                            && candidate.descriptor().equals(descriptor))
                    .findFirst().orElse(null);
            if (pattern == null) continue;

            Optional<String> constantMemberName = Optional.empty();
            if (pattern.memberNameArgumentIndex() < expression.getArgCount()
                    && expression.getArg(pattern.memberNameArgumentIndex()) instanceof StringConstant literal) {
                constantMemberName = Optional.of(literal.getValue());
            }
            destination.add(new ReflectionSinkEvidence(
                    toInternalName(callerClass.getType().getFullyQualifiedName()),
                    callerMethod.getSignature().toString(), pattern.owner(), pattern.name(),
                    pattern.descriptor(), pattern.memberNameArgumentIndex(), constantMemberName));
        }
    }

    private static String methodDescriptor(MethodSignature signature) {
        StringBuilder descriptor = new StringBuilder("(");
        for (Type parameter : signature.getParameterTypes()) {
            String typeDescriptor = typeDescriptor(parameter);
            if (typeDescriptor == null) return null;
            descriptor.append(typeDescriptor);
        }
        String returnDescriptor = typeDescriptor(signature.getType());
        return returnDescriptor == null ? null : descriptor.append(')').append(returnDescriptor).toString();
    }

    private static String typeDescriptor(Type type) {
        if (type instanceof ArrayType array) {
            String component = typeDescriptor(array.getBaseType());
            return component == null ? null : "[".repeat(array.getDimension()) + component;
        }
        if (type instanceof ClassType classType) {
            return "L" + toInternalName(classType.getFullyQualifiedName()) + ";";
        }
        return switch (type.toString()) {
            case "boolean" -> "Z";
            case "byte" -> "B";
            case "char" -> "C";
            case "short" -> "S";
            case "int" -> "I";
            case "long" -> "J";
            case "float" -> "F";
            case "double" -> "D";
            case "void" -> "V";
            default -> null;
        };
    }

    private static String toInternalName(String fullyQualifiedName) {
        return fullyQualifiedName.replace('.', '/');
    }
}
