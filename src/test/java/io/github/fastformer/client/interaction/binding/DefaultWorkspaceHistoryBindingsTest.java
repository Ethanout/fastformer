package io.github.fastformer.client.interaction.binding;

import io.github.fastformer.client.interaction.binding.context.key.WorkspaceKeyContext;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.input.state.ClientInputStateMachine.InputKind;
import io.github.fastformer.client.input.state.ClientInputStateMachine.State;
import io.github.fastformer.client.interaction.binding.action.*;
import io.github.fastformer.client.interaction.binding.context.*;
import io.github.fastformer.client.interaction.binding.data.*;
import io.github.fastformer.client.mode.ClientModes;
import io.github.fastformer.client.mode.ModeObservation;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/** Exercises resource rules instead of retaining a second production keyboard policy. */
class DefaultWorkspaceHistoryBindingsTest {
   private final Commands commands = new Commands();

   @Test void actualDefaultsPreserveDeleteAndHistoryAdmission() throws Exception {
      var system = system(defaults());
      for (String route : List.of("operation", "vanilla", "building", "geometry", "cancel", "blocked"))
         for (int flags = 0; flags < 128; flags++) for (int action = 0; action <= 2; action++)
            for (String key : List.of("delete", "backspace", "z", "y", "x")) {
               var context = context(route, flags, action, true, true);
               commands.last = null;
               var result = system.onKeyResult(Set.of("key.keyboard." + key), context, commands);
               String expected = previous(key, action, route, flags);
               String label = route + "/" + flags + "/" + action + "/" + key;
               assertEquals(!expected.equals("pass"), result.consumesVanilla(), label);
               assertEquals(expected.equals("pass") || expected.equals("block") ? null : WorkspaceCommand.valueOf(expected),
                  commands.last, label);
            }
   }

   @Test void emptyWorkspaceStillUndoesItsLocalHistoryOnTheRealIdleRoute() throws Exception {
      State state = ClientModes.registry().observe(ModeObservation.idle());
      assertEquals(State.IDLE, state);
      String route = route(state);
      assertEquals("vanilla", route);
      var system = system(defaults());
      assertCommand(system, "z", context(route, 2 | 16 | 32 | 64, 1, false, false), WorkspaceCommand.UNDO);
      assertCommand(system, "z", context(route, 16 | 32 | 64, 1, false, false), WorkspaceCommand.WORLD_UNDO);
   }

   @Test void historyOwnershipMatchesEveryRealInputRoute() throws Exception {
      var system = system(defaults());
      for (State state : State.values()) {
         String route = route(state);
         commands.last = null;
         var result = system.onKeyResult(Set.of("key.keyboard.z"), context(route, 2 | 16 | 32 | 64, 1, false, false), commands);
         assertTrue(result.consumesVanilla(), state.name());
         assertEquals(switch (route) {
            case "operation", "vanilla" -> WorkspaceCommand.UNDO;
            case "blocked" -> null;
            default -> WorkspaceCommand.WORLD_UNDO;
         }, commands.last, state.name());
      }
   }

   @Test void pendingOutranksEditingAndHistoryButRedoKeepsItsOriginalAdmission() throws Exception {
      var system = system(defaults());
      var context = context("operation", 255, 1, false, false);
      assertCommand(system, "z", context, WorkspaceCommand.REPORT_SUBMISSION_PENDING);
      assertCommand(system, "y", context, WorkspaceCommand.WORLD_REDO);
      commands.last = null;
      assertTrue(system.onKeyResult(Set.of("key.keyboard.z"), context("blocked", 255, 1, false, false), commands).consumesVanilla());
      assertNull(commands.last);
   }

   @Test void disablingAndRebindingRemoveOriginalHistoryAndDeleteKeys() throws Exception {
      var system = system(UserOverrides.merge(defaults(), JsonParser.parseString("""
         {"bindings":[
          {"id":"fastformer:workspace/undo","enabled":false},
          {"id":"fastformer:workspace/world_undo","enabled":false},
          {"id":"fastformer:workspace/world_redo","input":{"button":"key.keyboard.w","gesture":"key"}},
          {"id":"fastformer:workspace/remove_selected","enabled":false},
          {"id":"fastformer:workspace/delete_selection","enabled":false}
         ]}
         """).getAsJsonObject()));
      var active = context("operation", 1 | 2 | 16 | 32 | 64, 1, false, false);
      for (String key : List.of("z", "y", "delete"))
         assertEquals(BindingAction.Result.PASS, system.onKeyResult(Set.of("key.keyboard." + key), active, commands));
      assertEquals(BindingAction.Result.PASS, system.onKeyResult(Set.of("key.keyboard.delete"),
         context("operation", 1 | 32 | 64, 1, false, false), commands));
      assertCommand(system, "w", active, WorkspaceCommand.WORLD_REDO);
      assertCommand(system, "backspace", active, WorkspaceCommand.REMOVE_SELECTED);
   }

   @Test void disablingOnlyLocalUndoKeepsTheIndependentlyConfiguredWorldRule() throws Exception {
      var system = system(UserOverrides.merge(defaults(), JsonParser.parseString("""
         {"bindings":[{"id":"fastformer:workspace/undo","enabled":false}]}
         """).getAsJsonObject()));
      assertCommand(system, "z", context("operation", 2 | 16 | 32 | 64, 1, false, false), WorkspaceCommand.WORLD_UNDO);
   }

   @Test void vanillaOverrideStopsTheWorldFallbackWithoutClaimingMovement() throws Exception {
      var system = system(UserOverrides.merge(defaults(), JsonParser.parseString("""
         {"bindings":[{"id":"fastformer:workspace/undo","action":"fastformer:vanilla"}]}
         """).getAsJsonObject()));
      assertEquals(BindingAction.Result.HANDLED_PASS_VANILLA, system.onKeyResult(Set.of("key.keyboard.z"),
         context("operation", 2 | 16 | 32 | 64, 1, false, false), commands));
      assertNull(commands.last);
   }

   @Test void negativeUndoDepthIsRejected() {
      assertThrows(IllegalArgumentException.class, () -> new WorkspaceKeyContext("fastformer:idle", "vanilla", true,
         false, false, false, true, true, 1, true, false, false, -1));
   }

   @Test void observationKeepsTheExistingSessionPriority() {
      assertEquals(State.RESTORING, ClientModes.registry().observe(
         new ModeObservation(true, true, true, true, true, true, true, false, false)));
      assertEquals(State.PLACING, ClientModes.registry().observe(
         new ModeObservation(false, true, true, true, true, true, true, false, false)));
      assertEquals(State.ADJUSTING, ClientModes.registry().observe(
         new ModeObservation(false, false, true, true, false, true, true, false, false)));
      assertEquals(State.SELECTING, ClientModes.registry().observe(
         new ModeObservation(false, false, true, false, false, true, true, false, false)));
      assertEquals(State.GEOMETRY, ClientModes.registry().observe(
         new ModeObservation(false, false, false, false, false, true, true, false, false)));
      assertEquals(State.BUILDING, ClientModes.registry().observe(
         new ModeObservation(false, false, false, false, false, false, true, false, false)));
      assertEquals(State.IDLE, ClientModes.registry().observe(ModeObservation.idle()));
   }

   private static String previous(String key, int action, String route, int flags) {
      if (action != 1 || !bit(flags, 5) || !bit(flags, 6)) return "pass";
      if (key.equals("delete") || key.equals("backspace"))
         return route.equals("operation") && bit(flags, 0) ? bit(flags, 4) ? "REMOVE_SELECTED" : "DELETE_SELECTION" : "pass";
      if (!bit(flags, 4)) return "pass";
      if (key.equals("y")) return route.equals("blocked") ? "pass" : "WORLD_REDO";
      if (!key.equals("z")) return "pass";
      if (route.equals("blocked")) return "block";
      if (bit(flags, 3)) return "REPORT_SUBMISSION_PENDING";
      if (bit(flags, 2)) return "block";
      if (bit(flags, 1) && (route.equals("operation") || route.equals("vanilla"))) return "UNDO";
      return "WORLD_UNDO";
   }

   private void assertCommand(BindingSystem system, String key, BindingContext context, WorkspaceCommand expected) {
      commands.last = null;
      assertTrue(system.onKeyResult(Set.of("key.keyboard." + key), context, commands).consumesVanilla());
      assertEquals(expected, commands.last);
   }

   private static BindingContext context(String route, int flags, int action, boolean alt, boolean shift) {
      return new WorkspaceKeyContext("fastformer:selection", route, true, bit(flags, 0), bit(flags, 2), bit(flags, 3),
         bit(flags, 5), bit(flags, 6), action, bit(flags, 4), alt, shift, bit(flags, 1) ? 6 : 0).freeze();
   }

   private static String route(State state) {
      var machine = new ClientInputStateMachine();
      switch (state) {
         case IDLE -> { }
         case SUBMITTING -> { machine.observe(State.BUILDING); assertTrue(machine.submit(UUID.randomUUID())); }
         case CANCELLING -> { machine.observe(State.BUILDING); assertTrue(machine.cancel()); }
         default -> machine.observe(state);
      }
      assertEquals(state, machine.state());
      return machine.dispatch(InputKind.KEY).name().toLowerCase(Locale.ROOT);
   }

   private static BindingFile defaults() throws Exception {
      try (var stream = DefaultWorkspaceHistoryBindingsTest.class.getResourceAsStream("/assets/fastformer/fastformer/bindings/default/workspace-history.json")) {
         assertNotNull(stream);
         return BindingFile.parse(JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)));
      }
   }

   private static BindingSystem system(BindingFile file) {
      assertEquals(List.of(), file.diagnostics());
      var actions = BuiltinBindingActions.create();
      var rules = file.bindings().stream().sorted(InteractionBinding.ORDER)
         .map(binding -> new BindingSystem.Rule(binding, actions.resolve(binding.action()).getOrThrow())).toList();
      return new BindingSystem(new ContextMatcher(ContextFields.defaults().freeze()), () -> rules, id -> true);
   }

   private static boolean bit(int flags, int index) { return (flags & (1 << index)) != 0; }

   private static final class Commands implements BindingCommands {
      private WorkspaceCommand last;
      public boolean start(ResourceLocation profile) { return false; }
      public boolean point() { return false; }
      public boolean middleConfirm() { return false; }
      public boolean undoPoint() { return false; }
      public boolean cancel() { return false; }
      public boolean cycleMode() { return false; }
      public boolean workspace(WorkspaceCommand command) { last = command; return true; }
      public void replayPress(long time) { fail("Workspace history must not replay pointer input"); }
   }
}
