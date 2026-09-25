package io.github.fastformer.client.input;

import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.client.render.FastPlaceClientPreview;
import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import io.github.fastformer.client.session.OperationDraftIdentity;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.world.phys.Vec3;

record OperationPointCommandPress(
   UUID owner, OperationCallbackScope callbackScope, long revision,
   OperationDraftIdentity selection, int pointIndex, boolean insertEdge, Vec3 eye, Vec3 view, long occurredAtNanos
) {
   OperationPointCommandPress {
      Objects.requireNonNull(owner);
      Objects.requireNonNull(callbackScope);
      Objects.requireNonNull(selection);
   }

   static OperationPointCommandPress select(long occurredAtNanos) {
      int index = FastPlaceClientPreview.operationPointUnderCrosshairIndex();
      return new OperationPointCommandPress(
         ClientOperationController.interactionScene().owner(),
         ClientOperationController.remoteSelectionCallbackScope(),
         ClientOperationController.remoteSelectionRevision(),
         ClientOperationController.remoteSelectionIdentity(), index, false, minecraftEye(), minecraftView(), occurredAtNanos
      );
   }

   static OperationPointCommandPress insert(long occurredAtNanos) {
      return new OperationPointCommandPress(
         ClientOperationController.interactionScene().owner(),
         ClientOperationController.remoteSelectionCallbackScope(),
         ClientOperationController.remoteSelectionRevision(),
         ClientOperationController.remoteSelectionIdentity(), -1, true, minecraftEye(), minecraftView(), occurredAtNanos
      );
   }

   private static Vec3 minecraftEye() { return net.minecraft.client.Minecraft.getInstance().player.getEyePosition(); }
   private static Vec3 minecraftView() { return net.minecraft.client.Minecraft.getInstance().player.getViewVector(1.0F); }
}
