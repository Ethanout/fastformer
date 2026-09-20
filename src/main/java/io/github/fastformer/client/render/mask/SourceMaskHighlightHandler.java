package io.github.fastformer.client.render.mask;

import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;

/**
 * Suppresses the selection outline of a masked source block.
 *
 * <p>The client level keeps the real block, so the crosshair can still point at it. The
 * outline would draw a box around an invisible block, so the mask also hides the outline.
 * The block stays real for collision and for interaction.
 */
@EventBusSubscriber(modid = "fastformer", value = {Dist.CLIENT})
public final class SourceMaskHighlightHandler {
   private SourceMaskHighlightHandler() {
   }

   @SubscribeEvent
   public static void onRenderHighlight(RenderHighlightEvent.Block event) {
      BlockHitResult target = event.getTarget();
      if (target != null && SourceMaskRenderFilter.instance().hides(target.getBlockPos())) {
         event.setCanceled(true);
      }
   }
}
