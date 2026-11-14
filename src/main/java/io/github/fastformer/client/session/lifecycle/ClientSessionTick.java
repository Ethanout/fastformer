package io.github.fastformer.client.session.lifecycle;

import io.github.fastformer.client.input.FastPlaceClientInput;
import io.github.fastformer.client.interaction.binding.BindingConfiguration;
import io.github.fastformer.client.network.inbound.ClientInboundSystem;
import io.github.fastformer.client.preview.ClientPreviewSystem;
import io.github.fastformer.client.session.ClientSessionSystem;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Orders inbox application, input commands, operation settlement, and preview result publication. */
@EventBusSubscriber(modid = "fastformer", value = Dist.CLIENT)
public final class ClientSessionTick {
   private ClientSessionTick() {}

   @SubscribeEvent
   public static void onTick(ClientTickEvent.Post event) {
      Minecraft minecraft = Minecraft.getInstance();
      var sessions = ClientSessionSystem.instance();
      BindingConfiguration.instance().tick(System.nanoTime());
      FastPlaceClientInput.prepareTick(minecraft);
      sessions.observePlayer(minecraft);
      if (minecraft.player == null || minecraft.getConnection() == null) {
         FastPlaceClientInput.clearWorldInput(minecraft);
         return;
      }
      ClientInboundSystem.onClientTick();
      FastPlaceClientInput.dispatchTick(minecraft);
      sessions.operations().onClientTick();
      sessions.operations().sourceMask().reapply();
      sessions.previews().tickReconnectBoundary();
      if (minecraft.level == null || minecraft.screen != null) sessions.inputSession().feedback().clear();
      if (minecraft.level != null && minecraft.screen == null && minecraft.isWindowActive()) ClientPreviewSystem.instance().tick();
      FastPlaceClientInput.advanceTick(minecraft);
   }
}
