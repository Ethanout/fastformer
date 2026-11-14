package io.github.fastformer.client.input;

import io.github.fastformer.client.session.ClientSessionSystem;

import io.github.fastformer.client.interaction.intent.InteractionContext;
import io.github.fastformer.client.placement.QuickReplaceMode;
import io.github.fastformer.client.network.inbound.ClientInboundSystem;
import net.minecraft.client.Minecraft;

/** Clears transient input state when the client loses its world context. */
final class InputWorldCleanup {
   private InputWorldCleanup() { }

   static void clear(Minecraft minecraft, ClientInputSession session) {
      ClientInboundSystem.endWorldSession();
      QuickReplaceMode.clear();
      SelectionGestureController.cancelActive(session);
      session.selectionGestures().clear();
      if (session.operationSessionWasActive) minecraft.options.keyAttack.setDown(false);
      session.reset();
      ClientSessionSystem.instance().operations().setAltMode(false);
   }
}
