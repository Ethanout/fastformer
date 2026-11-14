package io.github.fastformer.client.input;

import io.github.fastformer.client.network.outbound.ServerGateway;

import io.github.fastformer.client.session.ClientSessionSystem;

import io.github.fastformer.protocol.c2s.geometry.CycleStageModePayload;
import io.github.fastformer.protocol.c2s.settings.ModifierStatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Sends modifier state and stage-cycle commands after local policy accepts them. */
final class ModifierCommandDispatcher {
   private ModifierCommandDispatcher() { }

   static void press(Minecraft minecraft) {
      if (ServerGateway.supports(minecraft, ModifierStatePayload.TYPE)) {
         ServerGateway.send(new ModifierStatePayload(true));
      }
   }

   static void release(Minecraft minecraft) {
      if (ServerGateway.supports(minecraft, ModifierStatePayload.TYPE)) {
         ServerGateway.send(new ModifierStatePayload(false));
      }
   }

   static void cycle(Minecraft minecraft) {
      if (ClientSessionSystem.instance().operations().selectionEdits().cycleDraftMode()) return;
      if (minecraft.player != null && !io.github.fastformer.common.placement.context.PlaceableItems.isPlaceable(minecraft.player.getMainHandItem())
         && !ClientSessionSystem.instance().previews().building().active() && !ClientSessionSystem.instance().previews().geometry().active()
         && !io.github.fastformer.client.placement.QuickReplaceMode.active()) {
         ClientSessionSystem.instance().operations().selectionEdits().cycleIdleSelectionTool();
         return;
      }
      if (!ServerGateway.supports(minecraft, CycleStageModePayload.TYPE)) return;
      BlockPos candidate = ClientSessionSystem.instance().quickShapePointer().lineCandidate();
      ServerGateway.send(
         candidate != null ? new CycleStageModePayload(true, candidate) : CycleStageModePayload.INSTANCE
      );
   }
}
