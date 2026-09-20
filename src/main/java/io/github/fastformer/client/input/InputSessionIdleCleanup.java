package io.github.fastformer.client.input;

import io.github.fastformer.client.operation.controller.ClientOperationController;
import net.minecraft.client.Minecraft;

/** Releases transient gestures after the routed session becomes idle. */
final class InputSessionIdleCleanup {
   private InputSessionIdleCleanup() { }

   static void clear(Minecraft minecraft, ClientInputSession session) {
      SelectionGestureController.cancelActive(session);
      OperationDragController.cancel(session);
      OperationPointInputController.cancel(session);
      session.undoPress.cancel();
      session.undoPressCaptured = false;
      session.geometryClickCapturedButton = -1;
      session.operationClickCapturedButton = -1;
      ClientOperationController.selectionGestures().clear();
      FastPlaceClientInput.finishPointerGesture();
   }
}
