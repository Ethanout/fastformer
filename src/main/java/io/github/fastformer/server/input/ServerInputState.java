package io.github.fastformer.server.input;

import io.github.fastformer.fastplace.session.OperationSession;
import io.github.fastformer.fastplace.geometry.GeometryPointerSequence;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;

final class ServerInputState {
   final InputRequestSequence LAST_PLACEMENT_ACTION = new InputRequestSequence();
   final InputRequestSequence LAST_GEOMETRY_ACTION = new InputRequestSequence();
   final InputRequestSequence LAST_GEOMETRY_DRAG_ACTION = new InputRequestSequence();
   final InputRequestSequence LAST_OPERATION_POINT_COMMAND = new InputRequestSequence();
   final Map<UUID, OperationExtendIdentity> OPERATION_EXTEND_IDENTITIES = new HashMap<>();
   final InputRequestSequence LAST_OPERATION_EXTEND = new InputRequestSequence();
   final InputRequestSequence LAST_OPERATION_TRANSFORM = new InputRequestSequence();
   final Map<UUID, OperationTransformIdentity> OPERATION_TRANSFORMS = new HashMap<>();
   record OperationTransformIdentity(OperationSession session, long gestureId, long revision, int operation, int axis, int direction, boolean finished) { }

   record OperationExtendIdentity(OperationSession session, long requestId, long revision,
      io.github.fastformer.network.payload.operation.OperationCallbackScope scope, int axis, boolean positive) { }

   final Map<UUID, GeometryPointerSequence> GEOMETRY_POINTER_SEQUENCES = new HashMap<>();
   final Map<UUID, OperationPointGesture> OPERATION_POINT_GESTURES = new HashMap<>();

   record OperationPointGesture(
      OperationSession session, long gestureId, long revision,
      io.github.fastformer.network.payload.operation.OperationCallbackScope callbackScope,
      int pointIndex, boolean finished, long completedRevision, boolean consumed
   ) {
      OperationPointGesture finished(long value) {
         return new OperationPointGesture(session, gestureId, revision, callbackScope, pointIndex, true, value, false);
      }
      OperationPointGesture markConsumed() {
         return new OperationPointGesture(session, gestureId, revision, callbackScope, pointIndex, true, completedRevision, true);
      }
   }

   final java.util.Map<UUID, io.github.fastformer.fastplace.quickshape.QuickShapePointerSequence> POINTER_SEQUENCES =
      new java.util.HashMap<>();
   void clear(UUID owner) {
      LAST_PLACEMENT_ACTION.clear(owner);
      POINTER_SEQUENCES.remove(owner);
      LAST_GEOMETRY_ACTION.clear(owner);
      LAST_GEOMETRY_DRAG_ACTION.clear(owner);
      LAST_OPERATION_POINT_COMMAND.clear(owner);
      GEOMETRY_POINTER_SEQUENCES.remove(owner);
      OPERATION_POINT_GESTURES.remove(owner);
      OPERATION_EXTEND_IDENTITIES.remove(owner);
      LAST_OPERATION_EXTEND.clear(owner);
      LAST_OPERATION_TRANSFORM.clear(owner);
      OPERATION_TRANSFORMS.remove(owner);
   }
   void clear() {
      LAST_PLACEMENT_ACTION.clear();
      POINTER_SEQUENCES.clear();
      LAST_GEOMETRY_ACTION.clear();
      LAST_GEOMETRY_DRAG_ACTION.clear();
      LAST_OPERATION_POINT_COMMAND.clear();
      GEOMETRY_POINTER_SEQUENCES.clear();
      OPERATION_POINT_GESTURES.clear();
      OPERATION_EXTEND_IDENTITIES.clear();
      LAST_OPERATION_EXTEND.clear();
      LAST_OPERATION_TRANSFORM.clear();
      OPERATION_TRANSFORMS.clear();
   }
}
