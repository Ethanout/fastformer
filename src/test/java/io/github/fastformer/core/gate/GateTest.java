package io.github.fastformer.core.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class GateTest {
   @Test
   void compositionRetainsTheFirstReasonAndSkipsLaterRules() {
      var called = new ArrayList<String>();
      Gate<Integer> positive = Gate.require("positive", value -> { called.add("positive"); return value > 0; });
      Gate<Integer> even = Gate.require("even", value -> { called.add("even"); return value % 2 == 0; });
      assertEquals(GateResult.deny("positive"), positive.and(even).test(-2));
      assertEquals(List.of("positive"), called);
      called.clear();
      assertEquals(GateResult.deny("even"), positive.and(even).test(3));
      assertEquals(List.of("positive", "even"), called);
      assertTrue(positive.and(even).test(2).allowed());
   }

   @Test
   void alternativesAndNegationShortCircuitWithExplicitReasons() {
      Gate<Integer> positive = Gate.require("positive", value -> value > 0);
      Gate<Integer> zero = Gate.require("zero", value -> value == 0);
      assertTrue(positive.or(zero).test(0).allowed());
      assertEquals(GateResult.deny("zero"), positive.or(zero).test(-1));
      assertTrue(Gate.not("not-positive", positive).test(-1).allowed());
      assertEquals(GateResult.deny("not-positive"), Gate.not("not-positive", positive).test(1));
      Gate<Integer> failIfCalled = context -> { throw new AssertionError("alternative called"); };
      assertTrue(positive.or(failIfCalled).test(1).allowed());
   }

   @Test
   void resultAndRuleValidationRejectAmbiguousReasons() {
      assertTrue(GateResult.allow().allowed());
      assertFalse(GateResult.deny("busy").allowed());
      assertThrows(IllegalArgumentException.class, () -> GateResult.deny(""));
      assertThrows(IllegalArgumentException.class, () -> new GateResult(true, "busy"));
      assertThrows(NullPointerException.class, () -> Gate.require("rule", null));
      assertThrows(NullPointerException.class, () -> Gate.not("rule", null));
   }
}
