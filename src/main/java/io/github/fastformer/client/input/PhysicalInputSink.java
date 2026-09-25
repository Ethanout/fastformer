package io.github.fastformer.client.input;

import io.github.fastformer.network.payload.placement.StartPlacementPayload;

/** Typed dispatch boundary. The mailbox owns ordering and the sink owns actions. */
interface PhysicalInputSink {
   void accept(KeyboardInputSnapshot event);
   void accept(ScrollInputSnapshot event);
   void accept(SelectionPointerEvent event);
   void accept(RemoteSelectionPointRequest event);
   void accept(PointerReleaseSnapshot event);
   void accept(QuickShapeUndoGesture.Event event);
   void accept(QuickShapePointerPress event);
   void accept(StartPlacementPayload.Target event);
   void accept(GeometryGizmoCapture.Event event);
   void accept(GeometryInputController.PointerPress event);
   void accept(OperationPointDragEvent event);
   void accept(OperationPointCommandEvent event);
}
