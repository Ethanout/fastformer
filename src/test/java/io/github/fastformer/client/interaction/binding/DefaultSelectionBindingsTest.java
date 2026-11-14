package io.github.fastformer.client.interaction.binding;

import static io.github.fastformer.client.interaction.binding.context.SelectionBindingContext.Fact.*;
import static io.github.fastformer.testkit.BindingFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.fastformer.client.interaction.binding.action.BindingAction;
import io.github.fastformer.client.interaction.binding.action.BindingCommands;
import io.github.fastformer.client.interaction.binding.data.UserOverrides;
import io.github.fastformer.client.interaction.binding.action.BindingCommands.SelectionBindingCommand;
import io.github.fastformer.client.interaction.binding.context.SelectionBindingContext;
import io.github.fastformer.client.interaction.binding.context.SelectionBindingContext.Fact;
import io.github.fastformer.client.interaction.binding.tool.SelectionBindingInput;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class DefaultSelectionBindingsTest {
   private static final Fact[] TARGETS = {NO_TARGET, GIZMO_TARGET, FACE_TARGET, PART_TARGET, CREATE_TARGET};
   private static final Fact[] LOCAL = {LOCAL_ACTIVE, DRAFT_ACTIVE, SESSION_ACTIVE, CAN_CREATE, ALT, WORLD_HIT, UNDO_PREVIOUS_POINT};
   private static final Fact[] REMOTE = {VANILLA_POINTER, ALT, ADJUST, SELECTION_READY, CONFIRMED, ADJUSTMENT_STARTED,
      REMOTE_GIZMO_TARGET, POINT_TARGET, WORLD_TARGET, REMOTE_FACE_TARGET, CANDIDATE, EDGE_TARGET, PRISM_BASE_OPEN};

   @Test void localDraftPartFaceAndGizmoRulesPreserveThePreviousPriorityForEveryCombination() throws Exception {
      var system = system(defaults("selection"));
      var commands = new Commands();
      for (int flags = 0; flags < 1 << LOCAL.length; flags++) for (Fact target : TARGETS) for (int button = 0; button < 3; button++) {
         var facts = flags(LOCAL, flags, EnumSet.of(LEFT_CHANNEL, POINT_CHANNEL, CREATE_ALLOWED));
         // canStartSelectionDraft is derived from an existing local session, draft or part.
         if (facts.contains(CAN_CREATE) && !facts.contains(LOCAL_ACTIVE) && !facts.contains(DRAFT_ACTIVE) && !facts.contains(SESSION_ACTIVE)) continue;
         facts.add(target);
         verify(system, commands, facts, button, "local " + flags + "/" + target + "/" + button);
      }
   }

   @Test void samplingToolHitsAndMissesKeepTheirPrecedenceOverDraftAndPartRules() throws Exception {
      var system = system(defaults("selection"));
      var commands = new Commands();
      Fact[] flags = {ALT, SAMPLING_EDITING, WORLD_HIT, REMOVE_HIT, ADD_HIT, GIZMO_TARGET, CAN_CREATE};
      for (int bits = 0; bits < 1 << flags.length; bits++) for (int button = 0; button < 3; button++) {
         var facts = flags(flags, bits, EnumSet.of(WORLD_SAMPLING, LOCAL_ACTIVE, SESSION_ACTIVE, CREATE_ALLOWED, LEFT_CHANNEL));
         verify(system, commands, facts, button, "sampling " + bits + "/" + button);
      }
   }

   @Test void remotePointFaceUndoGizmoAndVanillaRulesPreserveEveryRelevantCombination() throws Exception {
      var system = system(defaults("selection"));
      var commands = new Commands();
      for (Fact mode : new Fact[]{REMOTE_CUBOID, REMOTE_PRISM})
         for (int bits = 0; bits < 1 << REMOTE.length; bits++) for (int button = 0; button < 3; button++) {
         var facts = flags(REMOTE, bits, EnumSet.of(mode, LEFT_CHANNEL, POINT_CHANNEL, POINT_DRAG_CHANNEL, EDGE_CHANNEL, PATH_CHANNEL, SELECT_CHANNEL));
         if (facts.contains(REMOTE_GIZMO_TARGET)) facts.add(REMOTE_GIZMO_READY);
         if (facts.contains(REMOTE_FACE_TARGET)) facts.add(FACE_DRAG_READY);
         if (facts.contains(POINT_TARGET)) facts.add(POINT_DRAG_READY);
         verify(system, commands, facts, button, "remote " + mode + "/" + bits + "/" + button);
      }
   }

   @Test void remoteContinuationAndMissingChannelsDoNotFallBackToAnotherPhysicalAction() throws Exception {
      var system = system(defaults("selection"));
      var commands = new Commands();
      Fact[] flags = {LEFT_CHANNEL, POINT_CHANNEL, POINT_DRAG_CHANNEL, EDGE_CHANNEL, PATH_CHANNEL, REMOTE_POINTING};
      for (int bits = 0; bits < 1 << flags.length; bits++) for (int button = 0; button < 3; button++) {
         var facts = flags(flags, bits, EnumSet.of(REMOTE_CUBOID, CREATE_TARGET, WORLD_TARGET, CANDIDATE));
         verify(system, commands, facts, button, "channel " + bits + "/" + button);
      }
   }

   @Test void adjustmentFallbacksUseCapturedGizmoFaceAndPointChannelCapabilities() throws Exception {
      var system = system(defaults("selection")); var commands = new Commands();
      Fact[] capabilities = {REMOTE_GIZMO_TARGET, REMOTE_GIZMO_READY, POINT_TARGET, POINT_DRAG_CHANNEL,
         SELECT_CHANNEL, FACE_DRAG_READY, REMOTE_FACE_TARGET, VANILLA_POINTER, ADJUST};
      for (Fact mode : new Fact[]{REMOTE_CUBOID, REMOTE_PRISM})
         for (int bits = 0; bits < 1 << capabilities.length; bits++) for (int button = 0; button < 2; button++) {
         var facts = flags(capabilities, bits, EnumSet.of(mode, SELECTION_READY, LEFT_CHANNEL));
         if (facts.contains(REMOTE_GIZMO_READY) && !facts.contains(REMOTE_GIZMO_TARGET)) continue;
         if (facts.contains(POINT_TARGET) && facts.contains(POINT_DRAG_CHANNEL)) facts.add(POINT_DRAG_READY);
         verify(system, commands, facts, button, "adjust capabilities " + mode + "/" + bits + "/" + button);
      }
   }

   @Test void disablingTheWholeSubtreeLeavesSelectionTerminalWithoutAnyCommand() throws Exception {
      var file = defaults("selection");
      var changes = new JsonArray();
      for (var binding : file.bindings()) {
         var change = new JsonObject(); change.addProperty("id", binding.id().toString()); change.addProperty("enabled", false); changes.add(change);
      }
      var override = new JsonObject(); override.add("bindings", changes);
      var system = system(UserOverrides.merge(file, override)); var commands = new Commands();
      var result = SelectionBindingInput.mouse(true, context(EnumSet.of(LOCAL_ACTIVE, FACE_TARGET, LEFT_CHANNEL)), 1, 0, 10,
         (button, gesture, facts, time) -> system.onSignalResult(button, gesture, facts, commands));
      assertTrue(result.owns()); assertFalse(result.consumesVanilla()); assertNull(commands.command);
   }

   @Test void extraMouseRebindingKeepsTheLogicalActionAndVanillaOverrideIsTerminal() throws Exception {
      var file = defaults("selection");
      var system = system(UserOverrides.merge(file, JsonParser.parseString("""
         {"bindings":[{"id":"fastformer:selection/local_left_face_target","input":{"button":"mouse.5","gesture":"press"}}]}
         """).getAsJsonObject()));
      var commands = new Commands(); var facts = context(EnumSet.of(LOCAL_ACTIVE, FACE_TARGET, LEFT_CHANNEL));
      assertTrue(system.onSignalResult("mouse.5", "press", facts.freeze(), commands).consumesVanilla());
      assertEquals(SelectionBindingCommand.POINTER_SHRINK, commands.command);
      system = system(UserOverrides.merge(file, JsonParser.parseString("""
         {"bindings":[{"id":"fastformer:selection/local_left_face_target","action":"fastformer:vanilla"}]}
         """).getAsJsonObject()));
      commands.command = null;
      assertEquals(BindingAction.Result.HANDLED_PASS_VANILLA, system.onSignalResult("left", "press", facts.freeze(), commands));
      assertNull(commands.command);
   }

   @Test void registeredToolsShareGeometryDefaultsAndCanOverrideByTheirOwnIdentity() throws Exception {
      var system = system(UserOverrides.merge(defaults("selection"), JsonParser.parseString("""
         {"bindings":[{"id":"test:custom_tool_press","priority":5000,
           "context":{"session":"fastformer:selection","input_phase":"selection_pointer","selection_tool":"test:custom"},
           "input":{"button":"mouse.4","gesture":"press"},"action":"fastformer:selection/draft_right"}]}
         """).getAsJsonObject()));
      var commands = new Commands();
      var custom = new SelectionBindingContext("test:custom", "fastformer:cuboid", EnumSet.of(LOCAL_ACTIVE, FACE_TARGET, LEFT_CHANNEL));
      assertTrue(system.onSignalResult("mouse.4", "press", custom.freeze(), commands).consumesVanilla());
      assertEquals(SelectionBindingCommand.DRAFT_RIGHT, commands.command);
      commands.command = null;
      assertTrue(system.onSignalResult("left", "press", custom.freeze(), commands).consumesVanilla());
      assertEquals(SelectionBindingCommand.POINTER_SHRINK, commands.command);
   }

   @Test void releaseMatchesCapturedExtraButtonsAndReadsTheUndoWindowFromResources() throws Exception {
      var system = system(UserOverrides.merge(defaults("selection"), JsonParser.parseString("""
         {"bindings":[{"id":"fastformer:selection/release_undo","action":{"id":"fastformer:selection/end_undo","duration_ms":75}}]}
         """).getAsJsonObject()));
      var commands = new Commands();
      assertTrue(system.onSignalResult("mouse.5", "release", context(EnumSet.of(UNDO_CAPTURED, POINTER_CAPTURED)).freeze(), commands).consumesVanilla());
      assertEquals(SelectionBindingCommand.END_UNDO, commands.command); assertEquals(75, commands.duration);
      commands.command = null;
      assertTrue(system.onSignalResult("mouse.4", "release", context(EnumSet.of(POINTER_CAPTURED)).freeze(), commands).consumesVanilla());
      assertEquals(SelectionBindingCommand.RELEASE, commands.command);
   }

   private static void verify(io.github.fastformer.client.interaction.binding.BindingSystem system, Commands commands,
      EnumSet<Fact> facts, int button, String label) {
      commands.command = null;
      var result = system.onSignalResult(button == 0 ? "left" : button == 1 ? "right" : "middle", "press", context(facts).freeze(), commands);
      var expected = previous(facts, button);
      assertEquals(expected.command(), commands.command, label); assertEquals(expected.result(), result, label);
   }

   private static SelectionBindingContext context(EnumSet<Fact> facts) { return new SelectionBindingContext("fastformer:cuboid", "fastformer:cuboid", facts); }
   private static EnumSet<Fact> flags(Fact[] choices, int bits, EnumSet<Fact> facts) {
      for (int i = 0; i < choices.length; i++) if ((bits & 1 << i) != 0) facts.add(choices[i]);
      return facts;
   }
   private record Expected(SelectionBindingCommand command, BindingAction.Result result) { }
   private static Expected action(SelectionBindingCommand command) { return new Expected(command, BindingAction.Result.HANDLED); }
   private static Expected pass() { return new Expected(null, BindingAction.Result.PASS); }
   private static Expected block() { return new Expected(null, BindingAction.Result.HANDLED); }
   private static Expected vanilla() { return new Expected(null, BindingAction.Result.HANDLED_PASS_VANILLA); }

   /** Independent oracle of the removed physical target and button decision tree. */
   private static Expected previous(EnumSet<Fact> f, int b) {
      boolean alt = f.contains(ALT), gizmo = f.contains(GIZMO_TARGET), local = f.contains(LOCAL_ACTIVE);
      boolean create = f.contains(CREATE_TARGET), canCreate = f.contains(CAN_CREATE);
      if (alt && gizmo && b != 2) return action(SelectionBindingCommand.GIZMO);
      if (f.contains(WORLD_SAMPLING) && !gizmo && (f.contains(SAMPLING_EDITING) || b == 1)) {
         boolean hit = f.contains(b == 0 ? REMOVE_HIT : b == 1 && alt ? ADD_HIT : WORLD_HIT);
         return hit ? action(draft(b)) : block();
      }
      if (b == 2 && local && !canCreate) return action(SelectionBindingCommand.POINT_MIDDLE);
      if (b == 2 && canCreate && !create) { if (!f.contains(WORLD_HIT)) return vanilla(); create = true; }
      if (b == 0 && f.contains(UNDO_PREVIOUS_POINT) && !gizmo && (f.contains(DRAFT_ACTIVE) || f.contains(NO_TARGET) || create)) return action(draft(b));
      if (!alt && f.contains(REMOTE_POINTING)) {
         if (f.contains(REMOTE_CUBOID) && create && b != 0) return action(b == 1 ? SelectionBindingCommand.REMOTE_NEXT : SelectionBindingCommand.REMOTE_EXTRA);
      } else if (alt && f.contains(CREATE_ALLOWED) || !alt && create && (local || f.contains(DRAFT_ACTIVE) || f.contains(SESSION_ACTIVE)) && (b != 2 || canCreate)) return action(draft(b));
      if (local) {
         if (alt) return block();
         if (b == 0 && !f.contains(LEFT_CHANNEL)) return pass();
         if (b == 2) return action(SelectionBindingCommand.POINT_MIDDLE);
         if (gizmo || f.contains(FACE_TARGET) || f.contains(PART_TARGET)) return action(b == 0 ? SelectionBindingCommand.POINTER_SHRINK : SelectionBindingCommand.POINTER_EXPAND);
         return action(b == 0 ? SelectionBindingCommand.POINT_LEFT : SelectionBindingCommand.POINT_RIGHT);
      }
      return remote(f, b);
   }

   private static Expected remote(EnumSet<Fact> f, int b) {
      boolean vanilla = f.contains(VANILLA_POINTER), alt = f.contains(ALT), adjust = f.contains(ADJUST);
      boolean point = f.contains(POINT_TARGET), gizmo = f.contains(REMOTE_GIZMO_TARGET), face = f.contains(REMOTE_FACE_TARGET);
      if (b == 0) {
         if (vanilla && !alt && !adjust && !gizmo && !f.contains(GIZMO_TARGET) && !f.contains(FACE_TARGET) && !f.contains(PART_TARGET)) return vanilla();
         if (!f.contains(LEFT_CHANNEL)) return pass();
         if (f.contains(SELECTION_READY) && gizmo) return action(SelectionBindingCommand.REMOTE_GIZMO);
         if (point && f.contains(POINT_DRAG_CHANNEL)) return action(SelectionBindingCommand.REMOTE_POINT_LEFT);
         if (f.contains(ADJUSTMENT_STARTED) || f.contains(REMOTE_PRISM)) return action(SelectionBindingCommand.BEGIN_UNDO);
         if (alt) return action(SelectionBindingCommand.REMOTE_GIZMO);
         if (adjust) return action(f.contains(REMOTE_GIZMO_READY) ? SelectionBindingCommand.REMOTE_GIZMO
            : f.contains(REMOTE_PRISM) && point && f.contains(SELECT_CHANNEL) ? SelectionBindingCommand.REMOTE_SELECT
            : f.contains(REMOTE_PRISM) && f.contains(FACE_DRAG_READY) ? SelectionBindingCommand.REMOTE_FACE_SHRINK : SelectionBindingCommand.CAPTURE);
         if (f.contains(REMOTE_CUBOID) && !vanilla && f.contains(WORLD_TARGET)) return action(SelectionBindingCommand.DRAFT_LEFT);
         if (f.contains(REMOTE_CUBOID) && !vanilla && face) return action(SelectionBindingCommand.REMOTE_FACE_SHRINK);
         return action(SelectionBindingCommand.CAPTURE);
      }
      if (b == 2) return f.contains(CANDIDATE) && f.contains(POINT_CHANNEL) ? action(SelectionBindingCommand.REMOTE_EXTRA) : pass();
      if (f.contains(CONFIRMED)) return vanilla ? vanilla() : action(SelectionBindingCommand.REMOTE_GIZMO);
      if (vanilla && !adjust && !alt && !f.contains(POINT_DRAG_READY)) return vanilla();
      if (alt) return action(SelectionBindingCommand.REMOTE_GIZMO);
      if (adjust) return action(f.contains(REMOTE_GIZMO_READY) ? SelectionBindingCommand.REMOTE_GIZMO
         : f.contains(REMOTE_PRISM) && point && f.contains(SELECT_CHANNEL) ? SelectionBindingCommand.REMOTE_SELECT
         : f.contains(FACE_DRAG_READY) ? SelectionBindingCommand.REMOTE_FACE_EXPAND : SelectionBindingCommand.CAPTURE);
      if (point && f.contains(POINT_DRAG_CHANNEL)) return action(SelectionBindingCommand.REMOTE_POINT_RIGHT);
      if (!vanilla && f.contains(EDGE_TARGET) && f.contains(EDGE_CHANNEL)) return action(SelectionBindingCommand.REMOTE_EDGE);
      if (!vanilla && f.contains(REMOTE_PRISM) && f.contains(CANDIDATE) && f.contains(POINT_CHANNEL)) return action(SelectionBindingCommand.REMOTE_NEXT);
      if (f.contains(REMOTE_CUBOID) && f.contains(WORLD_TARGET) && f.contains(POINT_CHANNEL)) return action(SelectionBindingCommand.REMOTE_NEXT);
      if (f.contains(REMOTE_CUBOID) && face) return f.contains(FACE_DRAG_READY) ? action(SelectionBindingCommand.REMOTE_FACE_EXPAND) : vanilla();
      if (!f.contains(REMOTE_PRISM) && f.contains(PRISM_BASE_OPEN) && f.contains(PATH_CHANNEL)) return action(SelectionBindingCommand.REMOTE_CLOSE);
      return pass();
   }

   private static SelectionBindingCommand draft(int button) {
      return switch (button) { case 0 -> SelectionBindingCommand.DRAFT_LEFT; case 1 -> SelectionBindingCommand.DRAFT_RIGHT; default -> SelectionBindingCommand.DRAFT_MIDDLE; };
   }
   private static final class Commands implements BindingCommands {
      SelectionBindingCommand command; int duration;
      public boolean selection(SelectionBindingCommand value, int durationMillis) { assertNull(command); command = value; duration = durationMillis; return true; }
      public void replayPress(long time) { fail("Selection rules must not replay vanilla input"); }
   }
}
