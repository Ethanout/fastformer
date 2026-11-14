package io.github.fastformer.client.interaction.binding;

import static io.github.fastformer.client.interaction.binding.context.GeometryBindingContext.Fact.*;
import static io.github.fastformer.testkit.BindingFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.fastformer.client.interaction.binding.action.BindingAction;
import io.github.fastformer.client.interaction.binding.action.BindingCommands;
import io.github.fastformer.client.interaction.binding.action.GeometryBindingCommand;
import io.github.fastformer.client.interaction.binding.context.GeometryBindingContext;
import io.github.fastformer.client.interaction.binding.context.GeometryBindingContext.Fact;
import io.github.fastformer.client.interaction.binding.data.UserOverrides;
import io.github.fastformer.client.interaction.binding.tool.GeometryBindingInput;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class DefaultGeometryBindingsTest {
   private static final Fact[] FLAGS = {VANILLA_POINTER, POINTER_CAPTURED, GIZMO_PHYSICAL, GIZMO_OWNED, GIZMO_CAPTURED,
      GESTURE_BUSY, GIZMO_READY, INTERACTION_CHANNEL, UNDO_CHANNEL, LEFT_TARGET, RIGHT_TARGET, POINT_SELECTED};

   @Test void defaultsPreserveGizmoPriorityTargetsUndoAndSelectionForEveryFactCombination() throws Exception {
      var system = system(defaults("geometry", "quick_shape", "tools", "scroll"));
      var commands = new Commands();
      for (int flags = 0; flags < 1 << FLAGS.length; flags++) for (int extra = 0; extra < 8; extra++)
         for (int button = 0; button < 2; button++) {
         var facts = EnumSet.of(POINT_CHANNEL);
         if ((extra & 1) != 0) facts.add(DRAFT_PRESENT);
         if ((extra & 2) != 0) facts.add(PATH_AVAILABLE);
         if ((extra & 4) != 0) facts.add(FALLBACK_UNDO_CHANNEL);
         for (int i = 0; i < FLAGS.length; i++) if ((flags & (1 << i)) != 0) facts.add(FLAGS[i]);
         var context = new GeometryBindingContext("fastformer:wall", facts).freeze();
         commands.command = null;
         var actual = system.onSignalResult(button == 0 ? "left" : "right", "press", context, commands);
         var expected = previous(facts, button);
         String label = flags + "/" + extra + "/" + button;
         assertEquals(expected.command(), commands.command, label);
         assertEquals(expected.result(), actual, label);
      }
   }

   @Test void releaseRulesUseTheCapturedButtonAndDoNotRequireAWorldTarget() throws Exception {
      var system = system(defaults("geometry"));
      for (Fact owned : new Fact[]{GIZMO_OWNED, POINTER_CAPTURED, FALLBACK_UNDO_CAPTURED}) {
         var commands = new Commands();
         var facts = new GeometryBindingContext("fastformer:cone_prism", EnumSet.of(owned, VANILLA_POINTER, GESTURE_BUSY)).freeze();
         assertTrue(system.onSignalResult("mouse.5", "release", facts, commands).consumesVanilla());
         assertEquals(switch (owned) {
            case GIZMO_OWNED -> GeometryBindingCommand.RELEASE_GIZMO;
            case POINTER_CAPTURED -> GeometryBindingCommand.RELEASE_POINTER;
            default -> GeometryBindingCommand.END_UNDO;
         }, commands.command);
      }
   }

   @Test void disabledSubtreeOwnsTheGeometryPhaseWithoutRecreatingTheOldPhysicalActions() throws Exception {
      var file = defaults("geometry");
      var changes = new JsonArray();
      for (var binding : file.bindings()) {
         var change = new JsonObject();
         change.addProperty("id", binding.id().toString());
         change.addProperty("enabled", false);
         changes.add(change);
      }
      var override = new JsonObject();
      override.add("bindings", changes);
      var system = system(UserOverrides.merge(file, override));
      var commands = new Commands();
      var facts = new GeometryBindingContext("fastformer:wall", EnumSet.of(INTERACTION_CHANNEL, LEFT_TARGET));
      var result = GeometryBindingInput.mouse(true, facts, 1, 0, 5,
         (name, gesture, context, time) -> system.onSignalResult(name, gesture, context, commands));
      assertTrue(result.owns());
      assertFalse(result.consumesVanilla());
      assertNull(commands.command);
   }

   @Test void anAtomicTargetActionCanBeReboundToAnExtraMouseButton() throws Exception {
      var file = UserOverrides.merge(defaults("geometry"), JsonParser.parseString("""
         {"bindings":[{"id":"fastformer:geometry/left_target_interaction_channel",
           "input":{"button":"mouse.4","gesture":"press"}}]}
         """).getAsJsonObject());
      var system = system(file);
      var commands = new Commands();
      var facts = new GeometryBindingContext("fastformer:polyhedron", EnumSet.of(INTERACTION_CHANNEL, LEFT_TARGET)).freeze();
      assertTrue(system.onSignalResult("mouse.4", "press", facts, commands).consumesVanilla());
      assertEquals(GeometryBindingCommand.TARGET_LEFT, commands.command);
   }

   @Test void vanillaOverrideIsTerminalAndDoesNotInvokeTheTargetAction() throws Exception {
      var system = system(UserOverrides.merge(defaults("geometry"), JsonParser.parseString("""
         {"bindings":[{"id":"fastformer:geometry/left_target_interaction_channel","action":"fastformer:vanilla"}]}
         """).getAsJsonObject()));
      var commands = new Commands();
      var facts = new GeometryBindingContext("fastformer:convex_polyhedron", EnumSet.of(INTERACTION_CHANNEL, LEFT_TARGET)).freeze();
      assertEquals(BindingAction.Result.HANDLED_PASS_VANILLA, system.onSignalResult("left", "press", facts, commands));
      assertNull(commands.command);
   }

   @Test void theShortUndoWindowComesFromTheReleaseActionParameters() throws Exception {
      var system = system(UserOverrides.merge(defaults("geometry"), JsonParser.parseString("""
         {"bindings":[{"id":"fastformer:geometry/release_undo",
           "action":{"id":"fastformer:geometry/end_undo","duration_ms":75}}]}
         """).getAsJsonObject()));
      var commands = new Commands();
      var facts = new GeometryBindingContext("fastformer:wall", EnumSet.of(FALLBACK_UNDO_CAPTURED)).freeze();
      assertTrue(system.onSignalResult("left", "release", facts, commands).consumesVanilla());
      assertEquals(GeometryBindingCommand.END_UNDO, commands.command);
      assertEquals(75, commands.duration);
   }

   @Test void aRepeatedPressDoesNotRestartTheCapturedUndoClock() throws Exception {
      var system = system(defaults("geometry"));
      var commands = new Commands();
      var facts = new GeometryBindingContext("fastformer:wall", EnumSet.of(FALLBACK_UNDO_CAPTURED, FALLBACK_UNDO_CHANNEL)).freeze();
      assertEquals(BindingAction.Result.HANDLED, system.onSignalResult("left", "press", facts, commands));
      assertNull(commands.command);
   }

   private record Expected(GeometryBindingCommand command, BindingAction.Result result) {}
   private static Expected command(GeometryBindingCommand command) { return new Expected(command, BindingAction.Result.HANDLED); }
   private static Expected previous(EnumSet<Fact> facts, int button) {
      if (facts.contains(GIZMO_PHYSICAL) && facts.contains(GIZMO_OWNED)) return new Expected(null, BindingAction.Result.HANDLED);
      boolean vanilla = facts.contains(VANILLA_POINTER);
      if (!facts.contains(GIZMO_PHYSICAL) && !vanilla && facts.contains(GIZMO_READY)
         && (!facts.contains(GESTURE_BUSY) || facts.contains(GIZMO_CAPTURED))) return command(GeometryBindingCommand.GIZMO);
      if (facts.contains(POINTER_CAPTURED)) return new Expected(null, BindingAction.Result.HANDLED);
      if (facts.contains(GIZMO_PHYSICAL)) return new Expected(null, BindingAction.Result.HANDLED_PASS_VANILLA);
      if (!vanilla && !facts.contains(GIZMO_CAPTURED) && !facts.contains(GESTURE_BUSY)) {
         if (facts.contains(INTERACTION_CHANNEL) || facts.contains(UNDO_CHANNEL)) {
            if (facts.contains(button == 0 ? LEFT_TARGET : RIGHT_TARGET))
               return command(button == 0 ? GeometryBindingCommand.TARGET_LEFT : GeometryBindingCommand.TARGET_RIGHT);
            if (button == 0 && !facts.contains(POINT_SELECTED) && facts.contains(UNDO_CHANNEL) && facts.contains(DRAFT_PRESENT))
               return command(GeometryBindingCommand.UNDO_POINT);
            if (facts.contains(POINT_SELECTED)) return command(button == 0 ? GeometryBindingCommand.CLEAR_LEFT : GeometryBindingCommand.CLEAR_RIGHT);
            return command(GeometryBindingCommand.CAPTURE);
         }
         if (button == 1 && facts.contains(PATH_AVAILABLE)) return command(GeometryBindingCommand.PATH);
      }
      if (button == 0 && !vanilla && !facts.contains(GIZMO_CAPTURED) && facts.contains(FALLBACK_UNDO_CHANNEL))
         return command(GeometryBindingCommand.BEGIN_UNDO);
      return new Expected(null, BindingAction.Result.PASS);
   }

   private static final class Commands implements BindingCommands {
      GeometryBindingCommand command;
      int duration;
      public boolean geometry(GeometryBindingCommand value, int durationMillis) {
         assertNull(command);
         command = value;
         duration = durationMillis;
         return true;
      }
      public void replayPress(long time) { fail("Geometry binding must not replay a vanilla press"); }
   }
}
