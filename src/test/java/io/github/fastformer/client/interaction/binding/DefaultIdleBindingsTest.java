package io.github.fastformer.client.interaction.binding;

import com.google.gson.JsonParser;
import io.github.fastformer.client.interaction.binding.action.BindingAction;
import io.github.fastformer.client.interaction.binding.action.BindingCommands;
import io.github.fastformer.client.interaction.binding.action.entry.IdleCommand;
import io.github.fastformer.client.interaction.binding.action.entry.IdleEntryParameters;
import io.github.fastformer.client.interaction.binding.context.IdleBindingContext;
import io.github.fastformer.client.interaction.binding.context.IdleBindingContext.Fact;
import io.github.fastformer.client.interaction.binding.data.UserOverrides;
import io.github.fastformer.client.interaction.binding.input.MouseButtons;
import io.github.fastformer.testkit.BindingFixtures;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DefaultIdleBindingsTest {
   @Test void actualResourcePreservesTheCompleteIdlePriorityChain() throws Exception {
      var system = BindingFixtures.system(BindingFixtures.defaults("idle"));
      for (int bits = 0; bits < 1 << Fact.values().length; bits++) {
         EnumSet<Fact> facts = EnumSet.noneOf(Fact.class);
         for (Fact fact : Fact.values()) if ((bits & (1 << fact.ordinal())) != 0) facts.add(fact);
         for (int button : new int[]{0, 1, 2, 4}) for (boolean hold : new boolean[]{false, true}) {
            var commands = new Commands(hold);
            var result = system.onSignalsResult(MouseButtons.name(button), facts.contains(Fact.ANCHOR)
               ? Set.of("press", "double") : Set.of("press"), new IdleBindingContext(facts).freeze(), commands);
            IdleCommand expected = previous(button, facts, hold);
            String label = button + "/" + facts + "/" + hold;
            assertEquals(expected, commands.command, label);
            assertEquals(expected != null, result.handled(), label);
            assertEquals(expected != null && expected != IdleCommand.REMEMBER && expected != IdleCommand.CLEAR, result.consumesVanilla(), label);
            if (expected == IdleCommand.ENTER_SURFACE || expected == IdleCommand.ENTER_EMBEDDED && button == 1) {
               assertEquals(200, commands.parameters.guardMillis(), label);
            }
            if (expected == IdleCommand.ENTER_EMBEDDED) assertEquals(button == 2, commands.parameters.middleEntry(), label);
         }
      }
   }

   @Test void disabledEntryHasNoHardcodedFallbackAndVanillaIsTerminal() throws Exception {
      var defaults = BindingFixtures.defaults("idle");
      var facts = new IdleBindingContext(EnumSet.of(Fact.ALT, Fact.OUTLINE, Fact.DIRECT)).freeze();
      var disabled = BindingFixtures.system(UserOverrides.merge(defaults, JsonParser.parseString("""
         {"bindings":[{"id":"fastformer:idle/embedded","enabled":false}]}
         """).getAsJsonObject()));
      var commands = new Commands(true);
      assertFalse(disabled.onSignalsResult("right", Set.of("press"), facts, commands).consumesVanilla());
      assertEquals(IdleCommand.REMEMBER, commands.command);

      var vanilla = BindingFixtures.system(UserOverrides.merge(defaults, JsonParser.parseString("""
         {"bindings":[{"id":"fastformer:idle/embedded","action":"fastformer:vanilla"}]}
         """).getAsJsonObject()));
      commands = new Commands(true);
      assertEquals(BindingAction.Result.HANDLED_PASS_VANILLA, vanilla.onSignalsResult("right", Set.of("press"), facts, commands));
      assertNull(commands.command);
   }

   @Test void extraButtonAndConfiguredGuardAreTakenFromTheResource() throws Exception {
      var rebound = BindingFixtures.system(UserOverrides.merge(BindingFixtures.defaults("idle"), JsonParser.parseString("""
         {"bindings":[{"id":"fastformer:idle/embedded","input":{"button":"mouse.4","gesture":"press"},
          "action":{"id":"fastformer:idle/enter_embedded","guard_ms":375}}]}
         """).getAsJsonObject()));
      var commands = new Commands(false);
      assertTrue(rebound.onSignalsResult("mouse.4", Set.of("press"), new IdleBindingContext(EnumSet.of(Fact.ALT)).freeze(), commands).consumesVanilla());
      assertEquals(IdleCommand.ENTER_EMBEDDED, commands.command);
      assertEquals(375, commands.parameters.guardMillis());
   }

   @Test void actualDoubleRuleCarriesTheOriginalDistanceAndSpatialTolerances() throws Exception {
      var system = BindingFixtures.system(BindingFixtures.defaults("idle"));
      var pattern = system.pattern("right", "double", new IdleBindingContext(EnumSet.of(Fact.ANCHOR)).freeze()).orElseThrow();
      assertEquals(io.github.fastformer.common.interaction.binding.DoubleClickPolicy.DEFAULT, pattern.doubleClick());
   }

   private static IdleCommand previous(int button, EnumSet<Fact> facts, boolean hold) {
      if (button == 2) return facts.contains(Fact.ALT) && facts.contains(Fact.PLACEABLE) ? IdleCommand.ENTER_EMBEDDED : null;
      if (button != 1) return null;
      if (facts.contains(Fact.ENTITY) && !facts.contains(Fact.ALT) && !facts.contains(Fact.REPLAY)) return IdleCommand.CLEAR;
      if (facts.contains(Fact.ALT)) return IdleCommand.ENTER_EMBEDDED;
      if (facts.contains(Fact.DISTANT)) return IdleCommand.ENTER_SURFACE;
      if (facts.contains(Fact.ANCHOR)) return IdleCommand.ENTER_ANCHOR;
      if (facts.contains(Fact.TINKER)) return IdleCommand.TINKER;
      if (hold && facts.contains(Fact.PLACEABLE) && facts.contains(Fact.OUTLINE)
         && !facts.contains(Fact.REPLAY) && !facts.contains(Fact.PASSTHROUGH)) return IdleCommand.HOLD;
      return facts.contains(Fact.OUTLINE) ? IdleCommand.REMEMBER : IdleCommand.CLEAR;
   }

   private static final class Commands implements BindingCommands {
      private final boolean hold;
      private IdleCommand command;
      private IdleEntryParameters parameters;
      private Commands(boolean hold) { this.hold = hold; }
      public BindingAction.Result idle(IdleCommand command, IdleEntryParameters parameters) {
         if (command == IdleCommand.HOLD && !hold) return BindingAction.Result.PASS;
         this.command = command;
         this.parameters = parameters;
         return command == IdleCommand.REMEMBER || command == IdleCommand.CLEAR
            ? BindingAction.Result.HANDLED_PASS_VANILLA : BindingAction.Result.HANDLED;
      }
      public void replayPress(long time) { fail("Idle dispatch cannot replay a physical press"); }
   }
}
