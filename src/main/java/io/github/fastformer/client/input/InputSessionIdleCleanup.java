package io.github.fastformer.client.input;


import net.minecraft.client.Minecraft;

/** Releases transient gestures after the routed session becomes idle. */
final class InputSessionIdleCleanup {
   private InputSessionIdleCleanup() { }

   static void clear(Minecraft minecraft, ClientInputSession session) {
      session.selectionGestures().cancel();
      OperationDragController.cancel(session);
      OperationPointInputController.cancel(session);
      session.undoPress.cancel();
      session.undoPressCaptured = false;
      session.releaseGeometryPointerButton(0);
      session.releaseGeometryPointerButton(1);
      session.operationClick.clear();
      FastPlaceClientInput.finishPointerGesture();
   }
}
