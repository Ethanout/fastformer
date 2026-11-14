package io.github.fastformer.client.interaction.binding;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.fastformer.testkit.BindingFixtures.*;

import com.google.gson.JsonParser;
import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.input.state.ClientInputStateMachine.InputKind;
import io.github.fastformer.client.input.state.ClientInputStateMachine.State;
import io.github.fastformer.client.interaction.binding.action.BindingAction;
import io.github.fastformer.client.interaction.binding.action.CancellationCommand;
import io.github.fastformer.client.interaction.binding.context.key.CancellationKeyContext;
import io.github.fastformer.client.interaction.binding.data.UserOverrides;
import io.github.fastformer.client.interaction.binding.input.CancellationCapture;
import io.github.fastformer.testkit.RecordingBindingCommands;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefaultCancellationBindingsTest {
   @Test void defaultsPreserveEveryCaptureFactAndRestoreScope() throws Exception {
      var system = system(defaults("cancellation"));
      for (int flags = 0; flags < 512; flags++) for (int action = 0; action < 3; action++)
         for (String key : List.of("q", "w", "escape")) {
            var result = capture(system, Set.of("key.keyboard." + key), facts(flags, action, "building", false));
            CancellationCommand expected = previous(key, action, flags);
            String label = flags + "/" + action + "/" + key;
            assertEquals(expected, result.command(), label);
            boolean consumed = expected != null && expected != CancellationCommand.CLEAR_ENTRY;
            assertEquals(consumed, result.result().consumesVanilla(), label);
            assertEquals(consumed, result.capturesVanillaDrop(true), label);
            assertFalse(result.capturesVanillaDrop(false), label);
            assertEquals(consumed, result.intent().isPresent(), label);
            if (consumed) {
               var intent = result.intent().orElseThrow();
               assertEquals(expected, intent.command(), label);
               assertEquals(expected == CancellationCommand.REQUEST_CANCEL && bit(flags, 4), intent.dismissOperationRestore(), label);
               assertEquals(expected == CancellationCommand.REQUEST_CANCEL && bit(flags, 5), intent.dismissPreviewRestore(), label);
            }
         }
   }

   @Test void dispatchUsesCurrentAdmissionAndTheRealKeyRouteWithoutPhysicalKeyFallbacks() throws Exception {
      var system = system(defaults("cancellation", "history-conflict", "confirmation", "tools"));
      var commands = new RecordingBindingCommands();
      for (State state : State.values()) for (boolean pending : new boolean[]{false, true})
         for (boolean captured : new boolean[]{false, true}) for (int action = 0; action < 3; action++) {
            String route = machine(state).dispatch(InputKind.KEY).name().toLowerCase(Locale.ROOT);
            commands.clear();
            var context = facts(pending ? 8 : 0, action, route, captured).freeze("cancel_dispatch");
            var result = system.onKeyResult(Set.of("key.keyboard.w"), context, commands);
            CancellationCommand expected = !captured || action != 1 ? null : pending ? CancellationCommand.REPORT_SUBMISSION_PENDING
               : route.equals("operation") ? CancellationCommand.CANCEL_SELECTION : CancellationCommand.REQUEST_CANCEL;
            assertEquals(expected != null, result.consumesVanilla(), state + "/" + pending + "/" + captured + "/" + action);
            assertEquals(expected, commands.cancellation());
            assertNull(commands.historyConflict());
            assertNull(commands.confirmation());
         }
   }

   @Test void rebindingAllCaptureBranchesReleasesTheOriginalKey() throws Exception {
      var file = UserOverrides.merge(defaults("cancellation"), JsonParser.parseString("""
         {"bindings":[
          {"id":"fastformer:cancellation/binding","input":{"button":"key.keyboard.w","gesture":"key"}},
          {"id":"fastformer:cancellation/pending","input":{"button":"key.keyboard.w","gesture":"key"}},
          {"id":"fastformer:cancellation/request","input":{"button":"key.keyboard.w","gesture":"key"}},
          {"id":"fastformer:cancellation/clear_entry","input":{"button":"key.keyboard.w","gesture":"key"}}
         ]}
         """).getAsJsonObject());
      var system = system(file);
      var ready = facts(1 | 2 | 64 | 128 | 256, 1, "building", false);
      assertEquals(BindingAction.Result.PASS, capture(system, Set.of("key.keyboard.q"), ready).result());
      assertEquals(CancellationCommand.REQUEST_CANCEL, capture(system, Set.of("key.keyboard.w"), ready).command());
      var disabled = system(UserOverrides.merge(file, JsonParser.parseString("""
         {"bindings":[
          {"id":"fastformer:cancellation/binding","enabled":false},
          {"id":"fastformer:cancellation/pending","enabled":false},
          {"id":"fastformer:cancellation/request","enabled":false},
          {"id":"fastformer:cancellation/clear_entry","enabled":false}
         ]}
         """).getAsJsonObject()));
      assertEquals(BindingAction.Result.PASS, capture(disabled, Set.of("key.keyboard.w"), ready).result());
   }

   @Test void disablingTheRequestDoesNotRecreateItInTheEntryCleanupBranch() throws Exception {
      var system = system(UserOverrides.merge(defaults("cancellation"), JsonParser.parseString("""
         {"bindings":[{"id":"fastformer:cancellation/request","enabled":false}]}
         """).getAsJsonObject()));
      var result = capture(system, Set.of("key.keyboard.q"), facts(1 | 2 | 64 | 128 | 256, 1, "building", false));
      assertEquals(CancellationCommand.CLEAR_ENTRY, result.command());
      assertEquals(BindingAction.Result.HANDLED_PASS_VANILLA, result.result());
      assertTrue(result.intent().isEmpty());
      assertFalse(result.capturesVanillaDrop(true));
   }

   @Test void vanillaOverrideDoesNotCaptureAnIntentOrTheVanillaDrop() throws Exception {
      var system = system(UserOverrides.merge(defaults("cancellation"), JsonParser.parseString("""
         {"bindings":[{"id":"fastformer:cancellation/request","action":"fastformer:vanilla"}]}
         """).getAsJsonObject()));
      var result = capture(system, Set.of("key.keyboard.q"), facts(1 | 2 | 16 | 32 | 64 | 128 | 256, 1, "building", false));
      assertEquals(BindingAction.Result.HANDLED_PASS_VANILLA, result.result());
      assertNull(result.command());
      assertTrue(result.intent().isEmpty());
      assertFalse(result.capturesVanillaDrop(true));
      assertFalse(result.dismissOperationRestore());
      assertFalse(result.dismissPreviewRestore());
   }

   @Test void anActiveBindingOutranksPendingSubmissionAndNeedsNoWorldChannel() throws Exception {
      var result = capture(system(defaults("cancellation")), Set.of("key.keyboard.q"), facts(4 | 8 | 16 | 32, 1, "blocked", false));
      assertEquals(CancellationCommand.CANCEL_BINDING, result.command());
      assertTrue(result.result().consumesVanilla());
      assertFalse(result.intent().orElseThrow().dismissOperationRestore());
      assertFalse(result.intent().orElseThrow().dismissPreviewRestore());
   }

   private static CancellationCapture capture(BindingSystem system, Set<String> keys, CancellationKeyContext facts) {
      return CancellationCapture.resolve((context, commands) -> system.onKeyResult(keys, context, commands), facts);
   }

   private static CancellationCommand previous(String key, int action, int flags) {
      if (!key.equals("q") || action != 1) return null;
      if (bit(flags, 2)) return CancellationCommand.CANCEL_BINDING;
      if (bit(flags, 0) && bit(flags, 1) && bit(flags, 6) && bit(flags, 7) && bit(flags, 8))
         return bit(flags, 3) ? CancellationCommand.REPORT_SUBMISSION_PENDING : CancellationCommand.REQUEST_CANCEL;
      return CancellationCommand.CLEAR_ENTRY;
   }

   private static CancellationKeyContext facts(int flags, int action, String route, boolean captured) {
      return new CancellationKeyContext("fastformer:idle", route, bit(flags, 0), bit(flags, 1), bit(flags, 2), bit(flags, 3),
         bit(flags, 4), bit(flags, 5), captured, bit(flags, 6), bit(flags, 7), bit(flags, 8), action, true, true, true);
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
