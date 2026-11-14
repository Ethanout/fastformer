package io.github.fastformer.client.input;

import io.github.fastformer.client.session.ClientSessionSystem;

import io.github.fastformer.protocol.c2s.geometry.CycleStageModePayload;
import io.github.fastformer.protocol.c2s.settings.ModifierStatePayload;
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
      if (ClientSessionSystem.instance().operations().selectionEdits().cycleDraftMode()) return;
      if (minecraft.player != null && !io.github.fastformer.fastplace.placement.context.PlaceableItems.isPlaceable(minecraft.player.getMainHandItem())
         && !ClientSessionSystem.instance().previews().building().active() && !ClientSessionSystem.instance().previews().geometry().active()
         && !io.github.fastformer.client.placement.QuickReplaceMode.active()) {
         ClientSessionSystem.instance().operations().selectionEdits().cycleIdleSelectionTool();
         return;
      }
      if (!NetworkRegistry.hasChannel(minecraft.getConnection(), CycleStageModePayload.TYPE.id())) return;
      BlockPos candidate = ClientSessionSystem.instance().quickShapePointer().lineCandidate();
      PacketDistributor.sendToServer(
         candidate != null ? new CycleStageModePayload(true, candidate) : CycleStageModePayload.INSTANCE,
         new CustomPacketPayload[0]
      );
   }
}
