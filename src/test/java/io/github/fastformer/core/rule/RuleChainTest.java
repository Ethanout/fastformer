package io.github.fastformer.core.rule;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RuleChainTest {
   @Test void terminalFalseDoesNotFallThrough() {
      var chain = RuleChain.<String, Boolean>builder("terminal")
         .register("decline", 20, value -> Optional.of(false))
         .register("fallback", 10, value -> { fail("terminal result must stop evaluation"); return Optional.of(true); })
         .build();
      assertEquals(Optional.of(false), chain.apply("input"));
   }

   @Test void priorityIsStableAndUnmatchedRulesContinue() {
      List<String> calls = new ArrayList<>();
      var chain = RuleChain.<String, String>builder("order")
         .register("last", 0, value -> Optional.of("fallback"))
         .register("first", 10, value -> { calls.add("first"); return Optional.empty(); })
         .register("second", 10, value -> { calls.add("second"); return Optional.of(value); })
         .build();
      assertEquals(Optional.of("result"), chain.apply("result"));
      assertEquals(List.of("first", "second"), calls);
   }

   @Test void registrationIsUniqueAndFrozenAfterBuild() {
      var builder = RuleChain.<String, String>builder("registration");
      builder.register("one", 0, Optional::of);
      assertThrows(IllegalArgumentException.class, () -> builder.register("one", 1, Optional::of));
      builder.build();
      assertThrows(IllegalStateException.class, () -> builder.register("two", 0, Optional::of));
   }

   @Test void invalidRuleResultsFailAtTheBoundary() {
      var chain = RuleChain.<String, String>builder("invalid").register("null", 0, value -> null).build();
      assertThrows(NullPointerException.class, () -> chain.apply("input"));
      assertThrows(NullPointerException.class, () -> chain.apply(null));
      assertEquals(Optional.empty(), RuleChain.<String, String>builder("empty").build().apply("input"));
   }
}
