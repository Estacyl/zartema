package org.zartema.transform.keep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class KeepRuleSetTest {
    @Test
    void exactClassAndMethodRulesAreDeterministicAndKeepOverridesSelection() {
        KeepRuleSet first = KeepRuleSet.builder()
                .keepMethod("example/Worker", "run", "(Ljava/lang/String;[I)J", "external API contract")
                .keepClass("example/Stable", "framework loads this class by name")
                .keepMethod("example/Worker", "run", "(I)J", "native integration contract")
                .build();
        KeepRuleSet second = KeepRuleSet.builder()
                .keepMethod("example/Worker", "run", "(I)J", "native integration contract")
                .keepClass("example/Stable", "framework loads this class by name")
                .keepMethod("example/Worker", "run", "(Ljava/lang/String;[I)J", "external API contract")
                .build();

        assertEquals(first.rules(), second.rules());
        assertEquals(List.of(
                KeepRuleSet.Rule.forClass("example/Stable", "framework loads this class by name"),
                KeepRuleSet.Rule.forMethod("example/Worker", "run", "(I)J", "native integration contract"),
                KeepRuleSet.Rule.forMethod("example/Worker", "run", "(Ljava/lang/String;[I)J", "external API contract")),
                first.rules());
        assertTrue(first.keepsClass("example/Stable"));
        assertTrue(first.keepsMethod("example/Stable", "anything", "()V"));
        assertTrue(first.keepsMethod("example/Worker", "run", "(I)J"));
        assertFalse(first.keepsMethod("example/Worker", "run", "()J"));
        assertTrue(first.shouldRenameMethod("example/Worker", "run", "()J", true));
        assertFalse(first.shouldRenameMethod("example/Worker", "run", "(I)J", true));
        assertFalse(first.shouldRenameMethod("example/Worker", "run", "()J", false));
        assertEquals("native integration contract",
                first.matchingRules("example/Worker", "run", "(I)J").getFirst().reason());
    }

    @Test
    void repeatedIdenticalRulesAreCoalescedAndMultipleReasonsRemainDeterministic() {
        KeepRuleSet rules = KeepRuleSet.builder()
                .keepClass("example/Api", "z reason")
                .keepClass("example/Api", "a reason")
                .keepClass("example/Api", "z reason")
                .build();

        assertEquals(2, rules.rules().size());
        assertEquals(List.of("a reason", "z reason"),
                rules.rules().stream().map(KeepRuleSet.Rule::reason).toList());
        assertThrows(UnsupportedOperationException.class, () -> rules.rules().clear());
    }

    @Test
    void matchingRulesRetainClassAndExactMethodReasonsTogether() {
        KeepRuleSet rules = KeepRuleSet.builder()
                .keepMethod("example/Worker", "run", "()V", "method contract")
                .keepClass("example/Worker", "class-wide reflective lookup")
                .build();

        assertFalse(rules.shouldRenameMethod("example/Worker", "run", "()V", true));
        assertEquals(List.of("class-wide reflective lookup", "method contract"),
                rules.matchingRules("example/Worker", "run", "()V").stream()
                        .map(KeepRuleSet.Rule::reason).toList());
    }

    @Test
    void validatesClassNamesMethodNamesDescriptorsAndReasons() {
        assertThrows(IllegalArgumentException.class, () -> KeepRuleSet.builder().keepClass("/example/Api", "reason"));
        assertThrows(IllegalArgumentException.class, () -> KeepRuleSet.builder().keepClass("example//Api", "reason"));
        assertThrows(IllegalArgumentException.class, () -> KeepRuleSet.builder().keepMethod("example.Api", "run", "()V", "reason"));
        assertThrows(IllegalArgumentException.class, () -> KeepRuleSet.builder().keepMethod("example/Api", "bad/name", "()V", "reason"));
        assertThrows(IllegalArgumentException.class, () -> KeepRuleSet.builder().keepMethod("example/Api", "run", "(V)V", "reason"));
        assertThrows(IllegalArgumentException.class, () -> KeepRuleSet.builder().keepMethod("example/Api", "run", "(I", "reason"));
        assertThrows(IllegalArgumentException.class, () -> KeepRuleSet.builder().keepMethod("example/Api", "run", "()Vgarbage", "reason"));
        assertThrows(IllegalArgumentException.class, () -> KeepRuleSet.builder().keepMethod("example/Api", "<init>", "()I", "reason"));
        assertThrows(IllegalArgumentException.class, () -> KeepRuleSet.builder().keepMethod("example/Api", "<clinit>", "(I)V", "reason"));
        assertThrows(IllegalArgumentException.class, () -> KeepRuleSet.builder().keepClass("example/Api", "  \n  "));
        assertThrows(IllegalArgumentException.class, () -> KeepRuleSet.builder().keepClass("example/Api", "reason\nforged"));
    }

    @Test
    void exactRulesCanRepresentValidSpecialMethodsAndComplexDescriptors() {
        KeepRuleSet rules = KeepRuleSet.builder()
                .keepMethod("example/Api", "<init>", "(J[Ljava/lang/String;)V", "constructor contract")
                .keepMethod("example/Api", "<clinit>", "()V", "initialization contract")
                .keepMethod("example/Api", "compute", "([[I[[Ljava/lang/Object;)D", "binary API contract")
                .build();

        assertTrue(rules.keepsMethod("example/Api", "<init>", "(J[Ljava/lang/String;)V"));
        assertTrue(rules.keepsMethod("example/Api", "<clinit>", "()V"));
        assertTrue(rules.keepsMethod("example/Api", "compute", "([[I[[Ljava/lang/Object;)D"));
    }
}
