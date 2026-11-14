package io.github.fastformer.client.interaction.binding;

import static io.github.fastformer.testkit.BindingFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import io.github.fastformer.client.input.state.ClientInputStateMachine.Dispatch;
import io.github.fastformer.client.input.state.ClientInputStateMachine.InputKind;
import io.github.fastformer.client.input.state.ClientInputStateMachine.State;
import io.github.fastformer.client.interaction.binding.action.BindingAction;
import io.github.fastformer.client.interaction.binding.action.ScrollCommand;
import io.github.fastformer.client.interaction.binding.context.ScrollBindingContext;
import io.github.fastformer.client.interaction.binding.data.UserOverrides;
import io.github.fastformer.client.mode.ClientModes;
import io.github.fastformer.testkit.RecordingBindingCommands;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class DefaultScrollBindingsTest {
   @Test void actualResourcesPreserveEveryPhaseAndWheelFact() throws Exception {
      var bindings = system(defaults("scroll", "quick_shape", "tools", "workspace"));
      var commands = new RecordingBindingCommands();
      for (State state : State.values()) for (int flags = 0; flags < 16; flags++)
         for (double delta : new double[]{-2.5, -0.25, 0, 0.25, 2.5}) {
            commands.clear();
            Dispatch route = ClientModes.registry().policy(state).dispatch(InputKind.SCROLL);
            var facts = new ScrollBindingContext(ClientModes.registry().id(state).value().toString(),
               route.name().toLowerCase(Locale.ROOT), bit(flags, 0), bit(flags, 1), bit(flags, 2), bit(flags, 3), delta != 0);
            var actual = bindings.onSignalResult("scroll", delta < 0 ? "scroll_down" : "scroll_up", facts.freeze(), commands);
            ScrollCommand expected = previous(route, flags, delta);
            String label = state + "/" + flags + "/" + delta;
            assertEquals(expected, commands.scroll(), label);
            // Tool scroll bindings run before the mode route; fill preview owns even a zero wheel delta.
            boolean toolBlocked = state == State.FILL_PREVIEW;
            assertEquals(toolBlocked || route == Dispatch.BLOCKED || expected != null && expected != ScrollCommand.YIELD_TO_VANILLA,
               actual.consumesVanilla(), label);
            assertEquals(toolBlocked || route == Dispatch.BLOCKED || expected != null, actual.handled(), label);
            assertNull(commands.workspace(), label);
         }
   }

   @Test void aRejectedWorkspaceTransformDoesNotFallThroughToCandidateScrolling() throws Exception {
      var bindings = system(defaults("scroll"));
      var facts = new ScrollBindingContext("fastformer:selection", "operation", true, true, false, false, true);
      // The production command can reject a locked workspace; candidate rules must not receive that wheel.
      var rejecting = new io.github.fastformer.client.interaction.binding.action.BindingCommands() {
         public boolean scroll(ScrollCommand command) {
            assertEquals(ScrollCommand.WORKSPACE_UP, command);
            return false;
         }
         public void replayPress(long time) { fail("Wheel must not replay"); }
      };
      assertEquals(BindingAction.Result.PASS, bindings.onSignalResult("scroll", "scroll_up", facts.freeze(), rejecting));
   }

   @Test void resourceOverridesDisableOrReverseTheCandidateWithoutJavaFallbacks() throws Exception {
      var facts = new ScrollBindingContext("fastformer:geometry", "geometry", false, true, false, false, true).freeze();
      var commands = new RecordingBindingCommands();
      var disabled = system(UserOverrides.merge(defaults("scroll"), JsonParser.parseString("""
         {"bindings":[{"id":"fastformer:scroll/candidate_geometry_up","enabled":false}]}
         """).getAsJsonObject()));
      assertEquals(BindingAction.Result.PASS, disabled.onSignalResult("scroll", "scroll_up", facts, commands));
      assertNull(commands.scroll());
      var reversed = system(UserOverrides.merge(defaults("scroll"), JsonParser.parseString("""
         {"bindings":[{"id":"fastformer:scroll/candidate_geometry_up","action":"fastformer:scroll/candidate_down"}]}
         """).getAsJsonObject()));
      assertTrue(reversed.onSignalResult("scroll", "scroll_up", facts, commands).consumesVanilla());
      assertEquals(ScrollCommand.CANDIDATE_DOWN, commands.scroll());
   }

   @Test void vanillaOverrideReleasesBusyWheelOwnership() throws Exception {
      var bindings = system(UserOverrides.merge(defaults("scroll"), JsonParser.parseString("""
         {"bindings":[{"id":"fastformer:scroll/busy_up","action":"fastformer:vanilla"}]}
         """).getAsJsonObject()));
      var facts = new ScrollBindingContext("fastformer:busy", "blocked", true, true, false, false, true).freeze();
      assertEquals(BindingAction.Result.HANDLED_PASS_VANILLA,
         bindings.onSignalResult("scroll", "scroll_up", facts, new RecordingBindingCommands()));
   }

   private static ScrollCommand previous(Dispatch route, int flags, double delta) {
      if (route == Dispatch.BLOCKED || route == Dispatch.VANILLA || delta == 0) return null;
      if (route == Dispatch.OPERATION && bit(flags, 0))
         return delta > 0 ? ScrollCommand.WORKSPACE_UP : ScrollCommand.WORKSPACE_DOWN;
      if (!bit(flags, 1)) return null;
      if (bit(flags, 2)) return ScrollCommand.YIELD_TO_VANILLA;
      return bit(flags, 3) ? null : delta > 0 ? ScrollCommand.CANDIDATE_UP : ScrollCommand.CANDIDATE_DOWN;
   }

   private static boolean bit(int flags, int index) { return (flags & (1 << index)) != 0; }
}
