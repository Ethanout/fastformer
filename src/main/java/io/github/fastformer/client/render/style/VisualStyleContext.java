package io.github.fastformer.client.render.style;

import io.github.fastformer.fastplace.geometry.GeometryPalette;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

/** Read-only draw context. Positions and matrices remain in the caller's coordinate space. */
public record VisualStyleContext(ResourceLocation theme, Pass pass, boolean dynamic, double timeSeconds,
   float guiScale, int framebufferWidth, int framebufferHeight) {
   private static final long START = System.nanoTime();

   public enum Pass { STROKE, LINE, OCCLUDED_LINE, HALO, MODEL, MATERIAL }

   public static VisualStyleContext capture(Pass pass, boolean dynamic) {
      var window = Minecraft.getInstance().getWindow();
      return new VisualStyleContext(ResourceLocation.fromNamespaceAndPath("fastformer",
         GeometryPalette.theme().name().toLowerCase(java.util.Locale.ROOT)), pass, dynamic,
         (System.nanoTime() - START) / 1.0E9, guiScaleFactor(), window.getWidth(), window.getHeight());
   }

   public static float guiScaleFactor() {
      return (float)Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
   }
}
