package io.github.fastformer.client.input;

import io.github.fastformer.client.network.outbound.ServerGateway;

import io.github.fastformer.client.session.ClientSessionSystem;

import io.github.fastformer.protocol.c2s.world.WorldRedoPayload;
import io.github.fastformer.protocol.c2s.world.WorldUndoPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Sends world undo and redo commands after the keyboard policy accepts them. */
final class WorldHistoryCommandDispatcher {
   private WorldHistoryCommandDispatcher() { }

   static boolean send(Minecraft minecraft, int key) {
      if (minecraft.getConnection() == null) return false;
      if (key == 90 && ServerGateway.supports(minecraft, WorldUndoPayload.TYPE)) {
         ServerGateway.send(WorldUndoPayload.INSTANCE);
         return true;
      }
      if (key == 89 && ServerGateway.supports(minecraft, WorldRedoPayload.TYPE)) {
         if (!ClientSessionSystem.instance().operations().canRedoWorldHistory()) return false;
         ServerGateway.send(WorldRedoPayload.INSTANCE);
         return true;
      }
      return false;
   }
}
