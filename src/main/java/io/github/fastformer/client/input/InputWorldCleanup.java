package io.github.fastformer.client.input;

import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.client.placement.QuickReplaceMode;
import io.github.fastformer.client.render.FastPlaceClientPreview;
import io.github.fastformer.client.session.ClientSessionManager;
import io.github.fastformer.network.client.ClientPayloadDispatcher;
import net.minecraft.client.Minecraft;

/** Clears transient input state when the client loses its world context. */
final class InputWorldCleanup {
   private InputWorldCleanup() { }

   static void clear(Minecraft minecraft, ClientInputSession session) {
      ClientPayloadDispatcher.endWorldSession();
      QuickReplaceMode.clear();
      SelectionGestureController.cancelActive(session);
      ClientOperationController.selectionGestures().clear();
      if (session.operationSessionWasActive) minecraft.options.keyAttack.setDown(false);
      session.reset();
      ClientOperationController.setAltMode(false);
      InteractionContext.reset();
      FastPlaceClientPreview.clearTransientFeedback();
   }
}
