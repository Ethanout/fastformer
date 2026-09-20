package io.github.fastformer.client.input;

import io.github.fastformer.network.payload.world.WorldRedoPayload;
import io.github.fastformer.network.payload.world.WorldUndoPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Sends world undo and redo commands after the keyboard policy accepts them. */
final class WorldHistoryCommandDispatcher {
   private WorldHistoryCommandDispatcher() { }

   static boolean send(Minecraft minecraft, int key) {
      if (minecraft.getConnection() == null) return false;
      if (key == 90 && NetworkRegistry.hasChannel(minecraft.getConnection(), WorldUndoPayload.TYPE.id())) {
         PacketDistributor.sendToServer(WorldUndoPayload.INSTANCE, new CustomPacketPayload[0]);
         return true;
      }
      if (key == 89 && NetworkRegistry.hasChannel(minecraft.getConnection(), WorldRedoPayload.TYPE.id())) {
         PacketDistributor.sendToServer(WorldRedoPayload.INSTANCE, new CustomPacketPayload[0]);
         return true;
      }
      return false;
   }
}
