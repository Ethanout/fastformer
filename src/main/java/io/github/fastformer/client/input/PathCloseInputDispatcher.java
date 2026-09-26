package io.github.fastformer.client.input;

import io.github.fastformer.client.input.gesture.PathClosePress;
import io.github.fastformer.client.render.FastPlaceClientPreview;
import io.github.fastformer.network.payload.geometry.ClosePathPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

final class PathCloseInputDispatcher {
   private PathCloseInputDispatcher() {
   }

   static boolean press(Minecraft minecraft, ClientInputSession owner, boolean geometry, long occurredAtNanos) {
      return dispatch(minecraft, owner, capture(geometry, occurredAtNanos));
   }

   static PathClosePress capture(boolean geometry, long occurredAtNanos) {
      return new PathClosePress(FastPlaceClientPreview.closePathAtHoveredStart(),
         FastPlaceClientPreview.canDoubleClickClosePath(), FastPlaceClientPreview.pathCandidatePoint(),
         geometry, occurredAtNanos, captureIdentity());
   }

   private static ClosePathPayload captureIdentity() {
      long request = GeometryInputController.nextRequestId();
      if (FastPlaceClientPreview.operationActive()) return new ClosePathPayload(request,
         io.github.fastformer.client.operation.controller.ClientOperationController.remoteSelectionRevision(),
         io.github.fastformer.client.operation.controller.ClientOperationController.remoteSelectionCallbackScope(), ClosePathPayload.Kind.OPERATION);
      var geometry = FastPlaceClientPreview.geometrySnapshot();
      if (geometry.active()) return new ClosePathPayload(request, geometry.revision(), geometry.callbackScope(), ClosePathPayload.Kind.GEOMETRY);
      return FastPlaceClientPreview.buildingSubmission().map(snapshot ->
         new ClosePathPayload(request, snapshot.revision(), snapshot.scope(), ClosePathPayload.Kind.BUILDING)).orElse(null);
   }

   static boolean dispatch(Minecraft minecraft, ClientInputSession owner, PathClosePress press) {
      if (!NetworkRegistry.hasChannel(minecraft.getConnection(), ClosePathPayload.TYPE.id())) {
         owner.pathClose.reset();
         return false;
      }
      if (!press.closes(owner.pathClose)) {
         return false;
      }
      if (press.payload() == null) return false;
      PacketDistributor.sendToServer(press.payload());
      return true;
   }
}
