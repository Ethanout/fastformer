package io.github.fastformer.server.input;

import io.github.fastformer.fastplace.session.OperationSession;
import io.github.fastformer.fastplace.geometry.GeometryPointerSequence;
import io.github.fastformer.fastplace.quickshape.QuickShapePointerSequence;
import io.github.fastformer.protocol.callback.OperationCallbackScope;
import io.github.fastformer.core.lifecycle.Scoped;
import io.github.fastformer.core.lifecycle.ScopedKey;
import io.github.fastformer.server.player.PlayerStateRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

final class ServerInputState implements Scoped {
   private static final ScopedKey<ServerInputState> KEY = PlayerStateRegistry.key(
      ResourceLocation.parse("fastformer:input"), ServerInputState::new);

   static ServerInputState of(ServerPlayer player) { return PlayerStateRegistry.of(player).get(KEY); }

   static void reset(ServerPlayer player) {
      PlayerStateRegistry.find(player).flatMap(scope -> scope.find(KEY)).ifPresent(ServerInputState::clear);
   }

   @Override public void onDetach() { clear(); }
   @Override public void onDimensionChanged() { clear(); }
   @Override public void onDiscard() { clear(); }
   final InputRequestSequence placementActions = new InputRequestSequence();
   final InputRequestSequence geometryActions = new InputRequestSequence();
   final InputRequestSequence geometryDragActions = new InputRequestSequence();
   final InputRequestSequence pointCommands = new InputRequestSequence();
   OperationExtendIdentity extendGesture;
   final InputRequestSequence extendActions = new InputRequestSequence();
   final InputRequestSequence transformActions = new InputRequestSequence();
   OperationTransformIdentity transformGesture;
   record OperationTransformIdentity(OperationSession session, long gestureId, long revision, int operation, int axis, int direction, boolean finished) { }

   record OperationExtendIdentity(OperationSession session, long requestId, long revision,
      OperationCallbackScope scope, int axis, boolean positive) { }

   GeometryPointerSequence geometryPointer;
   OperationPointGesture pointGesture;

   record OperationPointGesture(
      OperationSession session, long gestureId, long revision,
      OperationCallbackScope callbackScope,
      int pointIndex, boolean finished, long completedRevision, boolean consumed
   ) {
      OperationPointGesture finished(long value) {
         return new OperationPointGesture(session, gestureId, revision, callbackScope, pointIndex, true, value, false);
      }
      OperationPointGesture markConsumed() {
         return new OperationPointGesture(session, gestureId, revision, callbackScope, pointIndex, true, completedRevision, true);
      }
   }

   QuickShapePointerSequence quickShapePointer;
   void clear() {
      placementActions.clear();
      quickShapePointer = null;
      geometryActions.clear();
      geometryDragActions.clear();
      pointCommands.clear();
      geometryPointer = null;
      pointGesture = null;
      extendGesture = null;
      extendActions.clear();
      transformActions.clear();
      transformGesture = null;
   }
}
