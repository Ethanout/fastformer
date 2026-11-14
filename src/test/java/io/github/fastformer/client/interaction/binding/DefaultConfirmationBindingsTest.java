package io.github.fastformer.client.interaction.binding;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.fastformer.testkit.BindingFixtures.*;

import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.input.state.ClientInputStateMachine.InputKind;
import io.github.fastformer.client.input.state.ClientInputStateMachine.State;
import io.github.fastformer.client.interaction.binding.action.BindingAction;
import io.github.fastformer.client.interaction.binding.action.ConfirmationCommand;
import io.github.fastformer.client.interaction.binding.context.BindingContext;
import io.github.fastformer.client.interaction.binding.context.key.ConfirmationKeyContext;
import io.github.fastformer.client.interaction.binding.data.UserOverrides;
import io.github.fastformer.testkit.RecordingBindingCommands;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DefaultConfirmationBindingsTest {
   private final RecordingBindingCommands commands = new RecordingBindingCommands();

   @Test void defaultsPreserveAdmissionForAllConfirmationFacts() throws Exception {
      var system = system(defaults("confirmation"));
      for (String route : List.of("operation", "building", "geometry", "vanilla", "cancel", "blocked"))
         for (int flags = 0; flags < 128; flags++) for (int action = 0; action < 3; action++)
            for (String key : List.of("enter", "keypad.enter", "x"))
               for (String phase : List.of("restore", "confirmation")) {
                  commands.clear();
                  var result = system.onKeyResult(Set.of("key.keyboard." + key), context(route, flags, action, phase), commands);
                  ConfirmationCommand expected = previous(key, action, route, flags, phase);
                  String label = route + "/" + flags + "/" + action + "/" + key + "/" + phase;
                  assertEquals(expected != null, result.consumesVanilla(), label);
                  assertEquals(expected, commands.confirmation(), label);
               }
   }

   @Test void realRoutesKeepSelectionAndPlacementConfirmationSeparate() throws Exception {
      var system = system(defaults("confirmation"));
      for (State state : State.values()) {
         var machine = machine(state);
         String route = machine.dispatch(InputKind.KEY).name().toLowerCase(Locale.ROOT);
         boolean submissionAllowed = machine.dispatch(InputKind.SUBMIT) != ClientInputStateMachine.Dispatch.BLOCKED;
         commands.clear();
         var result = system.onKeyResult(Set.of("key.keyboard.enter"), new ConfirmationKeyContext("fastformer:idle", route,
            submissionAllowed, true, true, true, false, true, false, 1, false, false, false).freeze("confirmation"), commands);
         ConfirmationCommand expected = route.equals("operation") ? ConfirmationCommand.CONFIRM_SELECTION
            : !submissionAllowed ? null : route.equals("building") ? ConfirmationCommand.SUBMIT_QUICK_SHAPE : ConfirmationCommand.CONFIRM_PLACEMENT;
         assertEquals(expected != null, result.consumesVanilla(), state.name());
         assertEquals(expected, commands.confirmation(), state.name());
      }
   }

   @Test void disabledActionsHaveNoPhysicalConfirmationFallback() throws Exception {
      var system = system(UserOverrides.merge(defaults("confirmation"), JsonParser.parseString("""
         {"bindings":[
          {"id":"fastformer:confirmation/selection","enabled":false},
          {"id":"fastformer:confirmation/quick_shape","enabled":false},
          {"id":"fastformer:confirmation/restore","enabled":false}
         ]}
         """).getAsJsonObject()));
      for (String route : List.of("operation", "building"))
         assertEquals(BindingAction.Result.PASS, system.onKeyResult(Set.of("key.keyboard.enter"),
            context(route, 1 | 2 | 4 | 8 | 32, 1, "confirmation"), commands));
      assertEquals(BindingAction.Result.PASS, system.onKeyResult(Set.of("key.keyboard.enter"),
         context("blocked", 2 | 4 | 16, 1, "restore"), commands));
      assertTrue(system.onKeyResult(Set.of("key.keyboard.keypad.enter"),
         context("building", 1 | 2 | 4 | 8 | 32, 1, "confirmation"), commands).consumesVanilla());
      assertEquals(ConfirmationCommand.SUBMIT_QUICK_SHAPE, commands.confirmation());
   }

   @Test void reboundConfirmationUsesItsNewKeyAndVanillaOverrideReleasesIt() throws Exception {
      var rebound = UserOverrides.merge(defaults("confirmation"), JsonParser.parseString("""
         {"bindings":[{"id":"fastformer:confirmation/quick_shape","input":{"button":"key.keyboard.w","gesture":"key"}}]}
         """).getAsJsonObject());
      var context = context("building", 1 | 2 | 4 | 8 | 32, 1, "confirmation");
      var system = system(rebound);
      assertEquals(BindingAction.Result.PASS, system.onKeyResult(Set.of("key.keyboard.enter"), context, commands));
      assertTrue(system.onKeyResult(Set.of("key.keyboard.w"), context, commands).consumesVanilla());
      assertEquals(ConfirmationCommand.SUBMIT_QUICK_SHAPE, commands.confirmation());
      commands.clear();
      var vanilla = system(UserOverrides.merge(rebound, JsonParser.parseString("""
         {"bindings":[{"id":"fastformer:confirmation/quick_shape","action":"fastformer:vanilla"}]}
         """).getAsJsonObject()));
      assertEquals(BindingAction.Result.HANDLED_PASS_VANILLA, vanilla.onKeyResult(Set.of("key.keyboard.w"), context, commands));
      assertNull(commands.confirmation());
   }

   @Test void lazyKeyContextSkipsUnboundAndDisabledKeysAndFreezesOnceForFallbackRules() throws Exception {
      var system = system(defaults("confirmation"));
      var captures = new AtomicInteger();
      java.util.function.Supplier<BindingContext> supplier = () -> {
         captures.incrementAndGet();
         return context("geometry", 1 | 2 | 4 | 8 | 32, 1, "confirmation");
      };
      assertEquals(BindingAction.Result.PASS, system.onKeyResult(Set.of("key.keyboard.x"), supplier, commands));
      assertEquals(0, captures.get());
      assertTrue(system.onKeyResult(Set.of("key.keyboard.enter"), supplier, commands).consumesVanilla());
      assertEquals(1, captures.get());
      assertEquals(ConfirmationCommand.CONFIRM_PLACEMENT, commands.confirmation());
      var disabled = UserOverrides.merge(defaults("confirmation"), JsonParser.parseString("""
         {"bindings":[
          {"id":"fastformer:confirmation/selection","enabled":false},
          {"id":"fastformer:confirmation/quick_shape","enabled":false},
          {"id":"fastformer:confirmation/restore","enabled":false},
          {"id":"fastformer:confirmation/placement_geometry","enabled":false},
          {"id":"fastformer:confirmation/placement_vanilla","enabled":false},
          {"id":"fastformer:confirmation/placement_cancel","enabled":false}
         ]}
         """).getAsJsonObject());
      assertEquals(BindingAction.Result.PASS, system(disabled).onKeyResult(Set.of("key.keyboard.enter"), supplier, commands));
      assertEquals(1, captures.get());
   }

   @Test void unrelatedToolWildcardRulesNeverCaptureConfirmationPointerFacts() throws Exception {
      var system = system(defaults("confirmation", "tools", "workspace", "workspace-history"));
      var phase = new BindingContext(Map.of("input_phase", new JsonPrimitive("confirmation")));
      for (String key : List.of("x", "a", "z", "q"))
         assertEquals(BindingAction.Result.PASS, system.onKeyResult(Set.of("key.keyboard." + key), phase,
            () -> { fail("Unrelated phase must not capture confirmation pointer facts"); return null; }, commands));
      var captures = new AtomicInteger();
      assertTrue(system.onKeyResult(Set.of("key.keyboard.enter"), phase, () -> {
         captures.incrementAndGet();
         return context("geometry", 1 | 2 | 4 | 8 | 32, 1, "confirmation");
      }, commands).consumesVanilla());
      assertEquals(1, captures.get());
      assertEquals(ConfirmationCommand.CONFIRM_PLACEMENT, commands.confirmation());
   }

   private static ConfirmationCommand previous(String key, int action, String route, int flags, String phase) {
      if (action != 1 || key.equals("x") || !bit(flags, 1) || !bit(flags, 2)) return null;
      if (phase.equals("restore")) return key.equals("enter") && bit(flags, 4) ? ConfirmationCommand.RESTORE_SESSIONS : null;
      if (route.equals("blocked")) return null;
      if (route.equals("operation")) return ConfirmationCommand.CONFIRM_SELECTION;
      if (!bit(flags, 0) || !bit(flags, 3) || !bit(flags, 5) || bit(flags, 6)) return null;
      return route.equals("building") ? ConfirmationCommand.SUBMIT_QUICK_SHAPE : ConfirmationCommand.CONFIRM_PLACEMENT;
   }

   private static BindingContext context(String route, int flags, int action, String phase) {
      return new ConfirmationKeyContext("fastformer:idle", route, bit(flags, 0), bit(flags, 1), bit(flags, 2), bit(flags, 3),
         bit(flags, 4), bit(flags, 5), bit(flags, 6), action, true, true, true).freeze(phase);
   }

   private static ClientInputStateMachine machine(State state) {
      var machine = new ClientInputStateMachine();
      switch (state) {
         case IDLE -> { }
         case SUBMITTING -> { machine.observe(State.BUILDING); assertTrue(machine.submit(UUID.randomUUID())); }
         case CANCELLING -> { machine.observe(State.BUILDING); assertTrue(machine.cancel()); }
         default -> machine.observe(state);
      }
      return machine;
   }

   private static boolean bit(int flags, int index) { return (flags & (1 << index)) != 0; }
}
