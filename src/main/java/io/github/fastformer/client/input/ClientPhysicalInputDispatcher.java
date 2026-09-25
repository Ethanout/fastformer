package io.github.fastformer.client.input;
import io.github.fastformer.client.input.mouse.MouseButtonInputSemantics;

import net.minecraft.client.Minecraft;
import io.github.fastformer.network.payload.placement.StartPlacementPayload;

/** Routes a captured batch without owning another copy of input state. */
final class ClientPhysicalInputDispatcher implements PhysicalInputSink {
   private final Minecraft minecraft;
   private final ClientInputSession session;
   ClientPhysicalInputDispatcher(Minecraft minecraft, ClientInputSession session) {
      this.minecraft = minecraft; this.session = session;
   }
   public void accept(KeyboardInputSnapshot event) { FastPlaceClientInput.handleKey(minecraft, event); }
   public void accept(ScrollInputSnapshot event) { ScrollInputDispatcher.dispatch(minecraft, session, event); }
   public void accept(SelectionPointerEvent event) { SelectionInputDispatcher.dispatch(minecraft, session, event); }
   public void accept(RemoteSelectionPointRequest event) { RemoteSelectionPointSender.sendIfAvailable(minecraft, session, event); }
   public void accept(PointerReleaseSnapshot event) {
      event.dispatch(session, MouseReleaseDispatcher.target(session, MouseButtonInputSemantics.RELEASE, event.button()),
         () -> MouseReleaseDispatcher.finish(minecraft, session, MouseButtonInputSemantics.RELEASE, event.button(), event.occurredAtNanos()));
   }
   public void accept(QuickShapeUndoGesture.Event event) { QuickShapeMouseInputController.dispatchUndo(minecraft, session, event); }
   public void accept(QuickShapePointerPress event) { QuickShapeMouseInputController.dispatch(minecraft, session, event); }
   public void accept(StartPlacementPayload.Target event) { QuickShapeStartController.dispatch(minecraft, session, event); }
   public void accept(GeometryGizmoCapture.Event event) { GeometryInputController.dispatchGizmo(minecraft, session, event); }
   public void accept(GeometryInputController.PointerPress event) { GeometryInputController.dispatchPointer(minecraft, session, event); }
   public void accept(OperationPointDragEvent event) { OperationPointInputController.dispatch(minecraft, session, event); }
   public void accept(OperationPointCommandEvent event) { OperationPointCommandDispatcher.dispatch(minecraft, session, event); }
}
