package io.github.fastformer.client.preview.frame;

import io.github.fastformer.client.interaction.intent.InteractionContext;
import io.github.fastformer.client.session.ClientSessionSystem;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/** Captures visual pointer facts once before the HUD and world draw the frame. */
@EventBusSubscriber(modid = "fastformer", value = Dist.CLIENT)
public final class ClientPointerFrameSystem {
   private ClientPointerFrameSystem() {}

   @SubscribeEvent
   public static void beginFrame(RenderFrameEvent.Pre event) {
      Minecraft minecraft = Minecraft.getInstance();
      var sessions = ClientSessionSystem.instance();
      var operations = sessions.operations();
      sessions.inputSession().pointer().invalidate();
      boolean visible = minecraft.player != null && minecraft.level != null
         && minecraft.screen == null && minecraft.isWindowActive();
      var pointer = operations.pointer().captureFrame(visible ? InteractionContext.capture(minecraft) : null);
      operations.updateVisualHover(operations.active() ? pointer.target().orElse(null) : null);
   }
}
