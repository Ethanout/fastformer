package io.github.fastformer.client.input;

import io.github.fastformer.fastplace.session.IdleSessionState;
import io.github.fastformer.network.payload.settings.IdleSessionPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Publishes idle ownership only after an accepted state-machine transition. */
final class IdleSessionSync {
   private IdleSessionSync() { }

   static void publish(IdleSessionState state) {
      var minecraft = Minecraft.getInstance();
      if (minecraft != null && minecraft.level != null && minecraft.getConnection() != null
         && NetworkRegistry.hasChannel(minecraft.getConnection(), IdleSessionPayload.TYPE.id())) {
         PacketDistributor.sendToServer(new IdleSessionPayload(state, minecraft.level.dimension().location()));
      }
   }
}
