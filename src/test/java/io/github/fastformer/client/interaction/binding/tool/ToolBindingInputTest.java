package io.github.fastformer.client.interaction.binding.tool;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.interaction.binding.action.BindingAction;
import io.github.fastformer.client.interaction.binding.context.BindingContext;
import io.github.fastformer.client.interaction.binding.context.ReplacementContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolBindingInputTest {
   private static final BindingContext FILL = new BindingContext(Map.of());
   private static final ReplacementContext REPLACE = new ReplacementContext(true, true, true, true, true, true, true, true);

   @Test void fillUnmatchedAndVanillaBindingsRetainOwnershipWithoutCancellingVanilla() {
      var noRule = (ToolBindingCommand) (name, gesture, context, now) -> BindingAction.Result.PASS;
      var vanilla = (ToolBindingCommand) (name, gesture, context, now) -> BindingAction.Result.HANDLED_PASS_VANILLA;
      assertEquals(new SmartFillBindingInput.Result(true, false), SmartFillBindingInput.mouse(true, FILL, 1, 0, 10, noRule));
      assertEquals(new SmartFillBindingInput.Result(true, false), SmartFillBindingInput.scroll(true, FILL, 1, 10, vanilla));
      var blocked = (ToolBindingCommand) (name, gesture, context, now) -> BindingAction.Result.HANDLED;
      assertEquals(new SmartFillBindingInput.Result(true, true), SmartFillBindingInput.interaction(true, FILL, true, 10, blocked));
   }

   @Test void fillCannotDispatchWhenTheCurrentModeDoesNotDelegateTheInput() {
      var unexpected = (ToolBindingCommand) (name, gesture, context, now) -> { fail("Task admission must run before binding dispatch"); return null; };
      assertFalse(SmartFillBindingInput.mouse(false, FILL, 1, 0, 10, unexpected).owns());
      assertFalse(SmartFillBindingInput.scroll(false, FILL, 1, 10, unexpected).owns());
      assertFalse(SmartFillBindingInput.interaction(false, FILL, true, 10, unexpected).owns());
   }

   @Test void replacementReleaseFinishesAcrossInvalidContextButPressDoesNotDispatch() {
      var calls = new ArrayList<String>();
      var dispatcher = (ToolBindingCommand) (name, gesture, context, now) -> {
         calls.add(name + "/" + gesture + ":" + now);
         return BindingAction.Result.HANDLED_PASS_VANILLA;
      };
      assertEquals(new QuickReplaceBindingInput.Result(true, false),
         QuickReplaceBindingInput.mouse(REPLACE, false, true, 1, 1, 10, dispatcher));
      assertTrue(calls.isEmpty());
      assertEquals(new QuickReplaceBindingInput.Result(true, false),
         QuickReplaceBindingInput.mouse(REPLACE, false, true, 0, 1, 20, dispatcher));
      assertEquals(List.of("@use/release:20"), calls);
   }

   @Test void replacementCustomBlockConsumesVanillaAndInactiveToolNeverDispatches() {
      var block = (ToolBindingCommand) (name, gesture, context, now) -> BindingAction.Result.HANDLED;
      assertTrue(QuickReplaceBindingInput.mouse(REPLACE, true, true, 1, 1, 10, block).consumesVanilla());
      assertTrue(QuickReplaceBindingInput.scroll(REPLACE, 1, 10, block).consumesVanilla());
      var inactive = new ReplacementContext(false, true, true, true, true, true, true, true);
      var unexpected = (ToolBindingCommand) (name, gesture, context, now) -> { fail("Inactive tool dispatched"); return null; };
      assertFalse(QuickReplaceBindingInput.mouse(inactive, true, true, 1, 1, 10, unexpected).owns());
      assertFalse(QuickReplaceBindingInput.tick(inactive, 10, unexpected));
   }
}
