package io.github.fastformer.client.preview.frame;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.joml.Quaternionf;

/** Explicit graphics services and viewport values, without session or input access. */
public record PreviewDrawContext(BlockAndTintGetter level, BlockRenderDispatcher blockRenderer,
   Font font, RenderTarget mainTarget, Quaternionf cameraRotation, CollisionContext collision,
   int guiWidth, int guiHeight, double guiScale, boolean mac) {
   public PreviewDrawContext { cameraRotation = new Quaternionf(cameraRotation); }

   public static PreviewDrawContext capture(Minecraft minecraft) {
      var window = minecraft.getWindow();
      return new PreviewDrawContext(minecraft.level, minecraft.getBlockRenderer(), minecraft.font,
         minecraft.getMainRenderTarget(), minecraft.gameRenderer.getMainCamera().rotation(),
         minecraft.player == null ? CollisionContext.empty() : CollisionContext.of(minecraft.player),
         window.getGuiScaledWidth(), window.getGuiScaledHeight(), window.getGuiScale(), Minecraft.ON_OSX);
   }
}
