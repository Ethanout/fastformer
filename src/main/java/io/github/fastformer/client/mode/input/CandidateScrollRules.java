package io.github.fastformer.client.mode.input;

/** Shared candidate wheel behavior for quick shape, geometry and unbound selection. */
public final class CandidateScrollRules {
   private CandidateScrollRules() {}

   public static ScrollDecision decide(ScrollInput input, ScrollView view) {
      if (input.direction() == 0 || !view.usesScrollContext()) return ScrollDecision.pass();
      if (view.modifierYieldsToVanilla()) return ScrollDecision.yieldToVanilla();
      if (view.vanillaOwnsPointer()) return ScrollDecision.pass();
      return ScrollDecision.candidate(input.direction());
   }
}
