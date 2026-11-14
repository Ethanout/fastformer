package io.github.fastformer.client.session.lifecycle;

import io.github.fastformer.client.session.ClientSessionSystem;

import io.github.fastformer.client.input.FastPlaceClientInput;
import io.github.fastformer.client.render.core.FastPlaceClientPreviewCore;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/** Owns client world-leave events and assembles the participating capabilities. */
@EventBusSubscriber(modid = "fastformer", value = Dist.CLIENT)
public final class WorldSessionEvents {
   private static final WorldSessionLifecycle LIFECYCLE = new WorldSessionLifecycle(new WorldSessionLifecycle.Participants() {
      @Override public void endInputSession() { FastPlaceClientInput.endWorldSession(); }
      @Override public void clearSourceMask() { ClientSessionSystem.instance().operations().sourceMask().clear(); }
      @Override public void resetPreviewSession() { FastPlaceClientPreviewCore.resetConnectionState(); }
      @Override public void disconnectOperation() { ClientSessionSystem.instance().operations().onDisconnected(); }
      @Override public void clearInteractionCache() { ClientSessionSystem.instance().operations().pointer().clear(); }
      @Override public void clearFeedback() { FastPlaceClientPreviewCore.clearTransientFeedback(); }
      @Override public void clearWorldRenderState() { FastPlaceClientPreviewCore.clearWorldRenderState(); }
   });

   private WorldSessionEvents() {}

   @SubscribeEvent
   public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) { onLeave(); }

   @SubscribeEvent
   public static void onLevelUnload(LevelEvent.Unload event) {
      if (event.getLevel().isClientSide()) onLeave();
   }

   public static void onLeave() { LIFECYCLE.onLeave(); }
}
