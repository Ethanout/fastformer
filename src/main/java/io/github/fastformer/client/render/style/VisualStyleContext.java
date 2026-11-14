package io.github.fastformer.client.render.style;

import io.github.fastformer.client.preview.style.GeometryPalette;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/** Read-only draw context. Positions and matrices remain in the caller's coordinate space. */
public record VisualStyleContext(ResourceLocation theme, Pass pass, boolean dynamic, double timeSeconds,
   float guiScale, int framebufferWidth, int framebufferHeight) {

   public enum Pass { STROKE, LINE, OCCLUDED_LINE, HALO, MODEL, MATERIAL }

   public static VisualStyleContext capture(Pass pass, boolean dynamic) {
      var window = Minecraft.getInstance().getWindow();
      return new VisualStyleContext(GeometryPalette.renderSnapshot().selected().id(), pass, dynamic,
         io.github.fastformer.client.preview.frame.PreviewAnimation.current().elapsedSeconds(),
         guiScaleFactor(), window.getWidth(), window.getHeight());
   }

   public static float guiScaleFactor() {
      return (float)Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
   }
}
