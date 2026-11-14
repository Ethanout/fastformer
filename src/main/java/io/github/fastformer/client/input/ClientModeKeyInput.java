package io.github.fastformer.client.input;

import io.github.fastformer.client.input.state.ClientInputStateMachine.Dispatch;
import io.github.fastformer.client.input.state.ClientInputStateMachine.InputKind;
import io.github.fastformer.client.input.state.ClientInputStateMachine.State;
import io.github.fastformer.client.mode.ClientModes;
import com.mojang.blaze3d.platform.InputConstants;
import io.github.fastformer.client.interaction.binding.context.ToolKeyContext;
import io.github.fastformer.client.placement.QuickReplaceMode;
import java.util.HashSet;
import net.minecraft.client.Minecraft;

/** Freezes tool-key facts and delegates every key decision to resource bindings. */
final class ClientModeKeyInput {
   private ClientModeKeyInput() {}

   /** Captures only data rules that explicitly run before vanilla key clicks are processed. */
   static boolean capture(Minecraft minecraft, ClientInputSession session, KeyboardInputSnapshot input) {
      return dispatch(minecraft, session, input, "capture");
   }

   static boolean handle(Minecraft minecraft, ClientInputSession session, KeyboardInputSnapshot input) {
      return dispatch(minecraft, session, input, "dispatch");
   }

   private static boolean dispatch(Minecraft minecraft, ClientInputSession session, KeyboardInputSnapshot input, String phase) {
      boolean replacing = QuickReplaceMode.active();
      var fill = session.smartFill();
      // Existing modal tools own their dedicated keys even while a task snapshot arrives.
      State mode = replacing ? State.QUICK_REPLACE : fill.active() ? State.FILL_PREVIEW : session.routing.state();
      boolean idle = !replacing && !fill.active() && session.routing.dispatch(InputKind.KEY) == Dispatch.VANILLA;
      var context = new ToolKeyContext(ClientModes.registry().id(mode), minecraft.screen == null,
         minecraft.player != null, idle, fill.ready(), idle && fill.canStart(minecraft), input.action(),
         input.controlDown(), input.altDown(), input.shiftDown()).freeze(phase);
      var names = new HashSet<String>();
      names.add(InputConstants.getKey(input.key(), input.scanCode()).getName());
      if (minecraft.options.keySwapOffhand.matches(input.key(), input.scanCode())) names.add("@swap_offhand");
      return session.bindings.onToolKey(minecraft, session, names, context, input.occurredAtNanos());
   }
}
