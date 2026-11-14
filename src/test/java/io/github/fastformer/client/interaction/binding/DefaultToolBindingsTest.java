package io.github.fastformer.client.interaction.binding;

import io.github.fastformer.client.interaction.binding.context.key.ToolKeyContext;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import io.github.fastformer.client.input.state.ClientInputStateMachine.State;
import io.github.fastformer.client.interaction.binding.action.*;
import io.github.fastformer.client.interaction.binding.context.*;
import io.github.fastformer.client.interaction.binding.data.*;
import io.github.fastformer.client.mode.ClientModes;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class DefaultToolBindingsTest {
   private enum Decision { PASS, CONSUME, TOGGLE_REPLACEMENT, BEGIN_REPLACEMENT, APPLY_REPLACEMENT, END_REPLACEMENT,
      HISTORY_UNDO, HISTORY_REDO, START_FILL, CONFIRM_FILL }
   private final Commands commands = new Commands();

   @Test void resourceDefaultsReproduceThePreviousTreeForEveryContext() throws Exception {
      var system = system(defaults());
      for (State state : State.values()) for (int flags = 0; flags < 128; flags++) {
         var view = new View(bit(flags, 0), bit(flags, 1), bit(flags, 2),
            bit(flags, 3), bit(flags, 4), bit(flags, 5));
         boolean control = bit(flags, 6);
         for (int key : new int[] {65, 70, 81, 82, 89, 90, 257, 335}) for (int action = 0; action <= 2; action++) {
            assertEquals(previousDecision(state, key, action, control, view),
               decide(system, state, key, action, control, view),
               state + " flags=" + flags + " key=" + key + " action=" + action);
         }
      }
   }

   @Test void remappedSwapKeySharesOneOrderedDispatchWithItsPhysicalKey() throws Exception {
      var system = system(defaults());
      var idle = new View(true, true, true, true, false, true);
      assertEquals(Decision.START_FILL, decide(system, State.IDLE, 82, 1, false, idle));
      assertEquals(Decision.CONSUME, decide(system, State.IDLE, 82, 0, false, idle));
      assertEquals(Decision.TOGGLE_REPLACEMENT, decide(system, State.QUICK_REPLACE, 82, 1, false, idle));
   }

   @Test void disablingOrRebindingDefaultsDoesNotFallBackToHardcodedKeys() throws Exception {
      var file = UserOverrides.merge(defaults(), JsonParser.parseString("""
         {"bindings":[
           {"id":"fastformer:idle/toggle_replacement","input":{"button":"key.keyboard.y","gesture":"key"}},
           {"id":"fastformer:quick_replace/undo","enabled":false}
         ]}
         """).getAsJsonObject());
      var system = system(file);
      var idle = new View(true, true, true, false, false, false);
      assertEquals(Decision.PASS, decide(system, State.IDLE, 82, 1, false, idle));
      assertEquals(Decision.TOGGLE_REPLACEMENT, decide(system, State.IDLE, 89, 1, false, idle));
      assertEquals(Decision.CONSUME, decide(system, State.QUICK_REPLACE, 90, 1, true, idle));
   }

   @Test void modifiersOtherThanControlKeepTheOriginalToolKeyBehavior() throws Exception {
      var system = system(defaults());
      var context = new ToolKeyContext("fastformer:idle", true, true, true, false, false, 1, false, true, true).freeze();
      assertTrue(system.onKey(Set.of("key.keyboard.r"), context, commands));
      assertEquals(Decision.TOGGLE_REPLACEMENT, commands.last);
   }

   @Test void capturePhaseOnlyStartsFillAndNeverDuplicatesQueuedConfirmation() throws Exception {
      var system = system(defaults());
      commands.last = Decision.PASS;
      var idle = new ToolKeyContext("fastformer:idle", true, true, true, false, true, 1, false, false, false);
      assertTrue(system.onKey(Set.of("key.keyboard.f", "@swap_offhand"), idle.freeze("capture"), commands));
      assertEquals(Decision.START_FILL, commands.last);
      assertEquals(1, commands.swapCaptures);
      commands.last = Decision.PASS;
      var ready = new ToolKeyContext("fastformer:smart_fill", true, true, false, true, false, 1, false, false, false);
      assertFalse(system.onKey(Set.of("key.keyboard.f", "@swap_offhand"), ready.freeze("capture"), commands));
      assertEquals(Decision.PASS, commands.last);
      assertTrue(system.onKey(Set.of("key.keyboard.f", "@swap_offhand"), ready.freeze(), commands));
      assertEquals(Decision.CONFIRM_FILL, commands.last);
   }

   @Test void fillEntryRebindingAppliesAtCaptureAndQueuedDispatch() throws Exception {
      var file = UserOverrides.merge(defaults(), JsonParser.parseString("""
         {"bindings":[{"id":"fastformer:idle/start_fill",
          "input":{"button":"key.keyboard.g","gesture":"key"}}]}
         """).getAsJsonObject());
      var system = system(file);
      var idle = new ToolKeyContext("fastformer:idle", true, true, true, false, true, 1, false, false, false);
      for (String phase : List.of("capture", "dispatch")) {
         commands.last = Decision.PASS;
         assertFalse(system.onKey(Set.of("key.keyboard.f", "@swap_offhand"), idle.freeze(phase), commands)
            && commands.last == Decision.START_FILL);
         assertTrue(system.onKey(Set.of("key.keyboard.g"), idle.freeze(phase), commands));
         assertEquals(Decision.START_FILL, commands.last);
      }
   }

   @Test void replacementTickDefaultsPreservePriorityAndStrokeEndForEveryAvailabilityCombination() throws Exception {
      var system = system(defaults());
      for (int flags = 0; flags < 128; flags++) {
         boolean canReplace = bit(flags, 0), creative = bit(flags, 1), enabled = bit(flags, 2);
         boolean screen = bit(flags, 3), focus = bit(flags, 4), allowed = bit(flags, 5), held = bit(flags, 6);
         var context = new ReplacementContext(true, canReplace, creative, enabled, screen, focus, allowed, held).freeze();
         commands.last = Decision.PASS;
         assertTrue(system.onSignal("client", "tick", context, commands));
         var expected = !creative || !enabled ? Decision.TOGGLE_REPLACEMENT
            : allowed && canReplace && screen && focus && held ? Decision.APPLY_REPLACEMENT : Decision.END_REPLACEMENT;
         assertEquals(expected, commands.last, "flags=" + flags);
      }
   }

   @Test void replacementStrokeAndUseEventsKeepVanillaCancellationAndScrollPassThrough() throws Exception {
      var system = system(defaults());
      var active = new ReplacementContext(true, true, true, true, true, true, true, true).freeze();
      assertTrue(system.onSignal("@use", "press", active, commands));
      assertEquals(Decision.BEGIN_REPLACEMENT, commands.last);
      assertTrue(system.onSignal("@use", "release", active, commands));
      assertEquals(Decision.END_REPLACEMENT, commands.last);
      for (boolean replace : List.of(false, true)) for (boolean screen : List.of(false, true)) {
         var context = new ReplacementContext(true, replace, true, true, screen, true, true, true).freeze();
         commands.last = Decision.PASS;
         assertEquals(replace && screen, system.onSignal("use", "interaction", context, commands));
         assertEquals(replace && screen ? Decision.APPLY_REPLACEMENT : Decision.PASS, commands.last);
         assertFalse(system.onSignal("attack", "interaction", context, commands));
      }
      assertFalse(system.onScroll(true, active, commands));
      assertFalse(system.onScroll(false, active, commands));
   }

   @Test void defaultStrokePreservesVanillaButUserBlockCancelsMouseAndScroll() throws Exception {
      var active = new ReplacementContext(true, true, true, true, true, true, true, true).freeze();
      var defaults = system(defaults());
      assertEquals(BindingAction.Result.HANDLED_PASS_VANILLA, defaults.onSignalResult("@use", "press", active, commands));
      assertEquals(BindingAction.Result.HANDLED_PASS_VANILLA, defaults.onSignalResult("@use", "release", active, commands));
      assertEquals(BindingAction.Result.PASS, defaults.onSignalResult("scroll", "scroll_up", active, commands));
      assertEquals(BindingAction.Result.HANDLED, defaults.onSignalResult("use", "interaction", active, commands));
      var blocked = UserOverrides.merge(defaults(), JsonParser.parseString("""
         {"bindings":[
           {"id":"fastformer:quick_replace/begin_stroke","action":"fastformer:block"},
           {"id":"fastformer:quick_replace/end_stroke","action":"fastformer:block"},
           {"id":"fastformer:quick_replace/pass_scroll_up","action":"fastformer:block"}
         ]}
         """).getAsJsonObject());
      var custom = system(blocked);
      assertTrue(custom.onSignalResult("@use", "press", active, commands).consumesVanilla());
      assertTrue(custom.onSignalResult("@use", "release", active, commands).consumesVanilla());
      assertTrue(custom.onSignalResult("scroll", "scroll_up", active, commands).consumesVanilla());
   }

   @Test void fillPointerUseAndWheelDefaultToBlockAndUserOverridesCanYieldVanilla() throws Exception {
      var context = new BindingContext(java.util.Map.of("session", new com.google.gson.JsonPrimitive("fastformer:smart_fill"),
         "standard_mouse_button", new com.google.gson.JsonPrimitive(true)));
      var defaults = defaults();
      var system = system(defaults);
      for (String button : List.of("left", "right", "middle")) for (String gesture : List.of("press", "release"))
         assertTrue(system.onSignalResult(button, gesture, context, commands).consumesVanilla());
      assertTrue(system.onSignalResult("use", "interaction", context, commands).consumesVanilla());
      assertTrue(system.onSignalResult("attack", "interaction", context, commands).consumesVanilla());
      assertTrue(system.onSignalResult("scroll", "scroll_up", context, commands).consumesVanilla());
      assertTrue(system.onSignalResult("scroll", "scroll_down", context, commands).consumesVanilla());
      var custom = system(UserOverrides.merge(defaults, JsonParser.parseString("""
         {"bindings":[
          {"id":"fastformer:smart_fill/block_right_press","enabled":false},
          {"id":"fastformer:smart_fill/block_scroll_up","action":"fastformer:vanilla"},
          {"id":"fastformer:smart_fill/block_use","action":"fastformer:pass"}
         ]}
         """).getAsJsonObject()));
      assertEquals(BindingAction.Result.PASS, custom.onSignalResult("right", "press", context, commands));
      assertEquals(BindingAction.Result.HANDLED_PASS_VANILLA, custom.onSignalResult("scroll", "scroll_up", context, commands));
      assertEquals(BindingAction.Result.PASS, custom.onSignalResult("use", "interaction", context, commands));
      assertEquals(BindingAction.Result.PASS, system(new BindingFile(List.of(), List.of(), List.of()))
         .onSignalResult("left", "press", context, commands));
      var extraButton = new BindingContext(java.util.Map.of("session", new com.google.gson.JsonPrimitive("fastformer:smart_fill"),
         "standard_mouse_button", new com.google.gson.JsonPrimitive(false)));
      assertTrue(system.onSignalResult("mouse.4", "press", extraButton, commands).consumesVanilla());
   }

   private Decision decide(BindingSystem system, State state, int key, int action, boolean control, View view) {
      commands.last = Decision.PASS;
      var names = new HashSet<String>();
      names.add(keyName(key));
      if (view.swap()) names.add("@swap_offhand");
      var context = new ToolKeyContext(ClientModes.registry().id(state).value().toString(), view.screen(), view.player(),
         view.idle(), view.ready(), view.canStart(), action, control, false, false).freeze();
      boolean handled = system.onKey(names, context, commands);
      return handled && commands.last == Decision.PASS ? Decision.CONSUME : commands.last;
   }

   private static BindingFile defaults() throws Exception {
      try (var stream = DefaultToolBindingsTest.class.getResourceAsStream("/assets/fastformer/fastformer/bindings/default/tools.json")) {
         assertNotNull(stream);
         var file = BindingFile.parse(JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)));
         assertEquals(List.of(), file.diagnostics());
         return file;
      }
   }

   private static BindingSystem system(BindingFile file) {
      assertTrue(file.diagnostics().isEmpty(), file.diagnostics().toString());
      var actions = BuiltinBindingActions.create();
      var rules = file.bindings().stream().sorted(InteractionBinding.ORDER)
         .map(binding -> new BindingSystem.Rule(binding, actions.resolve(binding.action()).getOrThrow())).toList();
      return new BindingSystem(new ContextMatcher(ContextFields.defaults().freeze()), () -> rules, id -> true);
   }

   private static Decision previousDecision(State state, int key, int action, boolean control, View view) {
      boolean press = action == 1;
      boolean fillKey = view.swap() && !control;
      if (state == State.QUICK_REPLACE) {
         if (view.screen() && press) {
            if (key == 82 && !control) return Decision.TOGGLE_REPLACEMENT;
            if (control && key == 90) return Decision.HISTORY_UNDO;
            if (control && key == 89) return Decision.HISTORY_REDO;
         }
         return Decision.CONSUME;
      }
      if (view.screen() && view.player() && state == State.FILL_PREVIEW && (fillKey || key == 257 || key == 335))
         return press && view.ready() ? Decision.CONFIRM_FILL : Decision.CONSUME;
      if (view.screen() && view.player() && view.idle()) {
         if (fillKey && view.canStart()) return press ? Decision.START_FILL : Decision.CONSUME;
         if (press && key == 82 && !control) return Decision.TOGGLE_REPLACEMENT;
      }
      return Decision.PASS;
   }

   private static String keyName(int key) {
      return switch (key) {
         case 257 -> "key.keyboard.enter";
         case 335 -> "key.keyboard.keypad.enter";
         default -> "key.keyboard." + Character.toLowerCase((char) key);
      };
   }

   private static boolean bit(int flags, int offset) { return (flags & (1 << offset)) != 0; }
   private record View(boolean screen, boolean player, boolean idle, boolean swap, boolean ready, boolean canStart) {}

   private static final class Commands implements BindingCommands {
      private Decision last = Decision.PASS;
      private int swapCaptures;
      public boolean start(ResourceLocation profile) { return false; }
      public boolean point() { return false; }
      public boolean middleConfirm() { return false; }
      public boolean undoPoint() { return false; }
      public boolean cancel() { return false; }
      public boolean cycleMode() { return false; }
      public void replayPress(long time) { fail("Tool keys must never replay mouse input"); }
      public boolean tool(ToolCommand command) {
         if (command == ToolCommand.CAPTURE_SWAP_KEY) { swapCaptures++; return false; }
         last = Decision.valueOf(command.name());
         return true;
      }
   }
}
