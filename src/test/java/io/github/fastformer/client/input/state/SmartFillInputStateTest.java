package io.github.fastformer.client.input.state;

import io.github.fastformer.client.mode.ClientModes;
import io.github.fastformer.client.mode.ModeObservation;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.github.fastformer.client.interaction.binding.BindingSystem;
import io.github.fastformer.client.interaction.binding.action.BindingCommands;
import io.github.fastformer.client.interaction.binding.action.BuiltinBindingActions;
import io.github.fastformer.client.interaction.binding.context.BindingContext;
import io.github.fastformer.client.interaction.binding.context.ContextFields;
import io.github.fastformer.client.interaction.binding.context.ContextMatcher;
import io.github.fastformer.client.interaction.binding.data.BindingFile;
import io.github.fastformer.client.interaction.binding.data.InteractionBinding;
import io.github.fastformer.client.interaction.binding.tool.SmartFillBindingInput;
import io.github.fastformer.client.interaction.binding.tool.ToolBindingCommand;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SmartFillInputStateTest {
   @Test void previewOwnsInputAndCancelReturnsThroughIdle() throws Exception {
      var machine = new ClientInputStateMachine();
      machine.observe(ClientModes.registry().observe(new ModeObservation(
         false, false, false, false, false, false, false, false, true)));
      assertEquals(ClientInputStateMachine.State.FILL_PREVIEW, machine.state());
      var bindings = defaultBindings();
      ToolBindingCommand dispatch = (name, gesture, context, now) -> bindings.onSignalResult(name, gesture, context, NO_COMMANDS);
      for (var kind : List.of(ClientInputStateMachine.InputKind.POINTER, ClientInputStateMachine.InputKind.INTERACTION,
         ClientInputStateMachine.InputKind.SCROLL)) {
         assertEquals(ClientInputStateMachine.Dispatch.VANILLA, machine.dispatch(kind));
         assertTrue(delegates(machine, kind), "The mode must retain explicit ownership of " + kind);
      }
      for (int button : new int[]{0, 1, 2, 4}) for (int action : new int[]{0, 1}) {
         var result = SmartFillBindingInput.mouse(delegates(machine, ClientInputStateMachine.InputKind.POINTER),
            context(button <= 2), action, button, 1, dispatch);
         assertTrue(result.owns());
         assertTrue(result.consumesVanilla(), "Default fill mouse must block " + button + "/" + action);
      }
      for (boolean use : new boolean[]{false, true}) {
         var result = SmartFillBindingInput.interaction(delegates(machine, ClientInputStateMachine.InputKind.INTERACTION),
            context(false), use, 1, dispatch);
         assertTrue(result.owns());
         assertTrue(result.consumesVanilla());
      }
      for (double delta : new double[]{-1, 1}) {
         var result = SmartFillBindingInput.scroll(delegates(machine, ClientInputStateMachine.InputKind.SCROLL),
            context(false), delta, 1, dispatch);
         assertTrue(result.owns());
         assertTrue(result.consumesVanilla());
      }
      assertEquals(ClientInputStateMachine.Dispatch.BLOCKED, machine.dispatch(ClientInputStateMachine.InputKind.KEY));
      assertEquals(ClientInputStateMachine.Dispatch.BLOCKED, machine.dispatch(ClientInputStateMachine.InputKind.SUBMIT));
      assertTrue(machine.cancel());
      machine.observe(ClientInputStateMachine.State.FILL_PREVIEW);
      assertEquals(ClientInputStateMachine.State.CANCELLING, machine.state());
      assertFalse(delegates(machine, ClientInputStateMachine.InputKind.POINTER));
      assertFalse(SmartFillBindingInput.mouse(delegates(machine, ClientInputStateMachine.InputKind.POINTER),
         context(true), 1, 1, 2, (name, gesture, context, now) -> { fail("Cancelling mode must not dispatch fill bindings"); return null; }).owns());
      machine.observe(ClientInputStateMachine.State.IDLE);
      assertTrue(machine.routesToVanilla(ClientInputStateMachine.InputKind.INTERACTION));
   }
   @Test void worldTasksOwnInputAfterFillConfirmation() {
      assertEquals(ClientInputStateMachine.State.PLACING, ClientModes.registry().observe(new ModeObservation(
         false, true, false, false, false, false, false, false, true)));
      assertEquals(ClientInputStateMachine.State.RESTORING, ClientModes.registry().observe(new ModeObservation(
         true, true, false, false, false, false, false, false, true)));
   }

   private static boolean delegates(ClientInputStateMachine machine, ClientInputStateMachine.InputKind kind) {
      return ClientModes.registry().policy(machine.state()).dataDrivenInputs().contains(kind);
   }

   private static BindingContext context(boolean standardButton) {
      return new BindingContext(Map.of("session", new JsonPrimitive("fastformer:smart_fill"),
         "standard_mouse_button", new JsonPrimitive(standardButton)));
   }

   private static BindingSystem defaultBindings() throws Exception {
      try (var stream = SmartFillInputStateTest.class.getResourceAsStream("/assets/fastformer/fastformer/bindings/default/tools.json")) {
         assertNotNull(stream);
         var file = BindingFile.parse(JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)));
         assertEquals(List.of(), file.diagnostics());
         var actions = BuiltinBindingActions.create();
         var rules = file.bindings().stream().sorted(InteractionBinding.ORDER)
            .map(binding -> new BindingSystem.Rule(binding, actions.resolve(binding.action()).getOrThrow())).toList();
         return new BindingSystem(new ContextMatcher(ContextFields.defaults().freeze()), () -> rules, id -> true);
      }
   }

   private static final BindingCommands NO_COMMANDS = new BindingCommands() {
      public boolean start(ResourceLocation profile) { fail("Pointer blocking must not start a session"); return false; }
      public boolean point() { fail("Pointer blocking must not edit points"); return false; }
      public boolean middleConfirm() { fail("Pointer blocking must not confirm"); return false; }
      public boolean undoPoint() { fail("Pointer blocking must not undo"); return false; }
      public boolean cancel() { fail("Pointer blocking must not cancel"); return false; }
      public boolean cycleMode() { fail("Pointer blocking must not change modes"); return false; }
      public void replayPress(long time) { fail("Pointer blocking must not replay"); }
   };
}
