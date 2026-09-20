package io.github.fastformer.client.input;

import io.github.fastformer.client.render.FastPlaceClientPreview;
import io.github.fastformer.network.payload.geometry.CycleStageModePayload;
import io.github.fastformer.network.payload.settings.ModifierStatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Sends modifier state and stage-cycle commands after local policy accepts them. */
final class ModifierCommandDispatcher {
   private ModifierCommandDispatcher() { }

   static void press(Minecraft minecraft) {
      if (NetworkRegistry.hasChannel(minecraft.getConnection(), ModifierStatePayload.TYPE.id())) {
         PacketDistributor.sendToServer(new ModifierStatePayload(true), new CustomPacketPayload[0]);
      }
   }

   static void release(Minecraft minecraft) {
      if (NetworkRegistry.hasChannel(minecraft.getConnection(), ModifierStatePayload.TYPE.id())) {
         PacketDistributor.sendToServer(new ModifierStatePayload(false), new CustomPacketPayload[0]);
      }
   }

   static void cycle(Minecraft minecraft) {
      if (!NetworkRegistry.hasChannel(minecraft.getConnection(), CycleStageModePayload.TYPE.id())) return;
      BlockPos candidate = FastPlaceClientPreview.lineModeCandidate();
      PacketDistributor.sendToServer(
         candidate != null ? new CycleStageModePayload(true, candidate) : CycleStageModePayload.INSTANCE,
         new CustomPacketPayload[0]
      );
   }
}
