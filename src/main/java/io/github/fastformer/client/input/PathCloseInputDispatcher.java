package io.github.fastformer.client.input;

import io.github.fastformer.client.render.FastPlaceClientPreview;
import io.github.fastformer.network.payload.geometry.ClosePathPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

final class PathCloseInputDispatcher {
   private PathCloseInputDispatcher() {
   }

   static boolean press(Minecraft minecraft, ClientInputSession owner, boolean geometry, long occurredAtNanos) {
      if (!NetworkRegistry.hasChannel(minecraft.getConnection(), ClosePathPayload.TYPE.id())) {
         owner.pathClose.reset();
         return false;
      }
      if (FastPlaceClientPreview.closePathAtHoveredStart()) {
         PacketDistributor.sendToServer(ClosePathPayload.INSTANCE);
         owner.pathClose.reset();
         return true;
      }
      if (!FastPlaceClientPreview.canDoubleClickClosePath()) {
         owner.pathClose.reset();
         return false;
      }
      if (!owner.pathClose.press(FastPlaceClientPreview.pathCandidatePoint(), geometry, occurredAtNanos)) {
         return false;
      }
      PacketDistributor.sendToServer(ClosePathPayload.INSTANCE);
      return true;
   }
}
