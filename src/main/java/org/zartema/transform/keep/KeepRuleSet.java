package org.zartema.transform.keep;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Immutable, exact-name rules that prevent selected classes or methods from being renamed. */
public final class KeepRuleSet {
    /** Scope of an exact keep rule. A class rule also keeps all methods declared by that class. */
    public enum Scope { CLASS, METHOD }

    /** One exact keep rule with a non-empty operator-supplied rationale. */
    public record Rule(Scope scope, String owner, String methodName, String descriptor, String reason) {
        public Rule {
            Objects.requireNonNull(scope, "scope");
            validateInternalName(owner);
            reason = validateReason(reason);
            if (scope == Scope.CLASS) {
                if (methodName != null || descriptor != null) {
                    throw new IllegalArgumentException("Class-wide rules cannot specify a method or descriptor");
                }
            } else {
                validateMethodName(methodName);
                validateMethodDescriptor(descriptor);
                if (methodName.equals("<init>") && !descriptor.endsWith(")V")) {
                    throw new IllegalArgumentException("Constructor keep rule must have a void return descriptor");
                }
                if (methodName.equals("<clinit>") && !descriptor.equals("()V")) {
                    throw new IllegalArgumentException("Class initializer keep rule must use ()V");
                }
            }
        }

        public static Rule forClass(String owner, String reason) {
            return new Rule(Scope.CLASS, owner, null, null, reason);
        }

        public static Rule forMethod(String owner, String methodName, String descriptor, String reason) {
            return new Rule(Scope.METHOD, owner, methodName, descriptor, reason);
        }
    }

    private static final Comparator<Rule> RULE_ORDER = Comparator
            .comparing(Rule::owner)
            .thenComparing(Rule::scope)
            .thenComparing(rule -> rule.methodName() == null ? "" : rule.methodName())
            .thenComparing(rule -> rule.descriptor() == null ? "" : rule.descriptor())
            .thenComparing(Rule::reason);

    private final List<Rule> rules;

    private KeepRuleSet(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    public static Builder builder() { return new Builder(); }

    /** Rules sorted by owner, scope, exact method key, and rationale. */
    public List<Rule> rules() { return rules; }

    /** Returns whether a class-wide rule keeps this exact internal class name. */
    public boolean keepsClass(String owner) {
        validateInternalName(owner);
        return rules.stream().anyMatch(rule -> rule.scope() == Scope.CLASS && rule.owner().equals(owner));
    }

    /** Returns whether a class-wide or exact owner/name/descriptor rule keeps this method. */
    public boolean keepsMethod(String owner, String methodName, String descriptor) {
        validateInternalName(owner);
        validateMethodName(methodName);
        validateMethodDescriptor(descriptor);
        return rules.stream().anyMatch(rule -> rule.owner().equals(owner)
                && (rule.scope() == Scope.CLASS || rule.methodName().equals(methodName)
                && rule.descriptor().equals(descriptor)));
    }

    /**
     * Applies keep rules after selection: a selected method is renamed only when no keep rule
     * matches it. An unselected method remains unchanged regardless of keep rules.
     */
    public boolean shouldRenameMethod(String owner, String methodName, String descriptor, boolean selected) {
        return selected && !keepsMethod(owner, methodName, descriptor);
    }

    /** Matching rules, including the owning class rule, in deterministic order. */
    public List<Rule> matchingRules(String owner, String methodName, String descriptor) {
        validateInternalName(owner);
        validateMethodName(methodName);
        validateMethodDescriptor(descriptor);
        return rules.stream().filter(rule -> rule.owner().equals(owner)
                        && (rule.scope() == Scope.CLASS || rule.methodName().equals(methodName)
                        && rule.descriptor().equals(descriptor)))
                .toList();
    }

    /** Builder for a deterministic immutable set. Repeated identical rules are coalesced. */
    public static final class Builder {
        private final TreeSet<Rule> rules = new TreeSet<>(RULE_ORDER);

        public Builder keepClass(String owner, String reason) {
            rules.add(Rule.forClass(owner, reason));
            return this;
        }

        public Builder keepMethod(String owner, String methodName, String descriptor, String reason) {
            rules.add(Rule.forMethod(owner, methodName, descriptor, reason));
            return this;
        }

        public KeepRuleSet build() {
            return new KeepRuleSet(new ArrayList<>(rules));
        }
    }

    private static String validateReason(String reason) {
        Objects.requireNonNull(reason, "reason");
        String normalized = reason.strip();
        if (normalized.isEmpty() || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Keep-rule reason must be non-empty and contain no control characters");
        }
        return normalized;
    }

    private static void validateInternalName(String owner) {
        Objects.requireNonNull(owner, "owner");
        if (owner.isEmpty() || owner.startsWith("/") || owner.endsWith("/") || owner.contains("//")) {
            throw new IllegalArgumentException("Invalid JVM internal class name: " + owner);
        }
        for (int i = 0; i < owner.length(); i++) {
            char value = owner.charAt(i);
            if (value == '.' || value == ';' || value == '[') {
                throw new IllegalArgumentException("Invalid JVM internal class name: " + owner);
            }
        }
    }

    private static void validateMethodName(String name) {
        Objects.requireNonNull(name, "methodName");
        if (name.isEmpty()) throw new IllegalArgumentException("JVM method name cannot be empty");
        if (name.equals("<init>") || name.equals("<clinit>")) return;
        for (int i = 0; i < name.length(); i++) {
            char value = name.charAt(i);
            if (value == '.' || value == ';' || value == '[' || value == '/' || value == '<' || value == '>') {
                throw new IllegalArgumentException("Invalid JVM method name: " + name);
            }
        }
    }

    private static void validateMethodDescriptor(String descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        int length = descriptor.length();
        if (length < 3 || descriptor.charAt(0) != '(') throw invalidDescriptor(descriptor);
        int[] cursor = {1};
        int parameterSlots = 0;
        while (cursor[0] < length && descriptor.charAt(cursor[0]) != ')') {
            int start = cursor[0];
            parseFieldType(descriptor, cursor);
            char type = descriptor.charAt(start);
            parameterSlots += type == 'J' || type == 'D' ? 2 : 1;
            if (parameterSlots > 255) throw invalidDescriptor(descriptor);
        }
        if (cursor[0] >= length || descriptor.charAt(cursor[0]++) != ')') throw invalidDescriptor(descriptor);
        if (cursor[0] >= length) throw invalidDescriptor(descriptor);
        if (descriptor.charAt(cursor[0]) == 'V') cursor[0]++;
        else parseFieldType(descriptor, cursor);
        if (cursor[0] != length) throw invalidDescriptor(descriptor);
    }

    private static void parseFieldType(String descriptor, int[] cursor) {
        int length = descriptor.length();
        int dimensions = 0;
        while (cursor[0] < length && descriptor.charAt(cursor[0]) == '[') {
            dimensions++;
            cursor[0]++;
            if (dimensions > 255) throw invalidDescriptor(descriptor);
        }
        if (cursor[0] >= length) throw invalidDescriptor(descriptor);
        char kind = descriptor.charAt(cursor[0]++);
        if ("BCDFIJSZ".indexOf(kind) >= 0) return;
        if (kind != 'L') throw invalidDescriptor(descriptor);
        int start = cursor[0];
        int end = descriptor.indexOf(';', start);
        if (end < 0 || end == start) throw invalidDescriptor(descriptor);
        validateInternalName(descriptor.substring(start, end));
        cursor[0] = end + 1;
    }

    private static IllegalArgumentException invalidDescriptor(String descriptor) {
        return new IllegalArgumentException("Invalid JVM method descriptor: " + descriptor);
    }
}
