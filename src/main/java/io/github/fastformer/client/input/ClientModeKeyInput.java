package io.github.fastformer.client.input;

import io.github.fastformer.client.input.state.ClientInputStateMachine.Dispatch;
import io.github.fastformer.client.input.state.ClientInputStateMachine.InputKind;
import io.github.fastformer.client.input.state.ClientInputStateMachine.State;
import io.github.fastformer.client.mode.ClientModes;
import io.github.fastformer.client.mode.input.KeyInput;
import io.github.fastformer.client.mode.input.KeyView;
import io.github.fastformer.client.placement.QuickReplaceMode;
import net.minecraft.client.Minecraft;

/** Captures dedicated mode key facts and applies the accepted command once. */
final class ClientModeKeyInput {
   private ClientModeKeyInput() {}

   static boolean handle(Minecraft minecraft, ClientInputSession session, KeyboardInputSnapshot input) {
      boolean replacing = QuickReplaceMode.active();
      var fill = session.smartFill();
      // Existing modal tools own their dedicated keys even while a task snapshot arrives.
      State mode = replacing ? State.QUICK_REPLACE : fill.active() ? State.FILL_PREVIEW : session.routing.state();
      boolean idle = !replacing && !fill.active() && session.routing.dispatch(InputKind.KEY) == Dispatch.VANILLA;
      var view = new KeyView(minecraft.screen == null, minecraft.player != null, idle,
         !input.controlDown() && minecraft.options.keySwapOffhand.matches(input.key(), input.scanCode()),
         fill.ready(), idle && fill.canStart(minecraft));
      var decision = ClientModes.registry().onKey(mode, new KeyInput(input.key(), input.action(), input.controlDown()), view);
      return switch (decision) {
         case PASS -> false;
         case CONSUME -> true;
         case TOGGLE_REPLACEMENT -> { QuickReplaceMode.toggle(minecraft); yield true; }
         case REPLACEMENT_UNDO, REPLACEMENT_REDO -> {
            QuickReplaceMode.finishStroke(minecraft);
            minecraft.options.keyUse.setDown(false);
            WorldHistoryCommandDispatcher.send(minecraft, input.key());
            yield true;
         }
         case START_FILL -> { fill.start(minecraft); yield true; }
         case CONFIRM_FILL -> { fill.confirm(minecraft); yield true; }
      };
   }
}
