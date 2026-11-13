package io.github.fastformer.core.gate;

import java.util.Objects;
import java.util.function.Predicate;

/** A composable admission rule, evaluated by the caller on its owning thread. */
@FunctionalInterface
public interface Gate<C> {
   GateResult test(C context);

   /** Tests the second rule only when the first allows; retains the first denial. */
   default Gate<C> and(Gate<? super C> next) {
      Objects.requireNonNull(next, "next");
      return context -> {
         GateResult result = test(context);
         return result.allowed() ? next.test(context) : result;
      };
   }

   /** Tests the alternative only when this rule refuses. */
   default Gate<C> or(Gate<? super C> alternative) {
      Objects.requireNonNull(alternative, "alternative");
      return context -> {
         GateResult result = test(context);
         return result.allowed() ? result : alternative.test(context);
      };
   }

   static <C> Gate<C> require(String reason, Predicate<? super C> condition) {
      Objects.requireNonNull(condition, "condition");
      GateResult denied = GateResult.deny(reason);
      return context -> condition.test(context) ? GateResult.allow() : denied;
   }

   /** The caller names the refusal produced by a successful original rule. */
   static <C> Gate<C> not(String reason, Gate<? super C> original) {
      Objects.requireNonNull(original, "original");
      GateResult denied = GateResult.deny(reason);
      return context -> original.test(context).allowed() ? denied : GateResult.allow();
   }
}
