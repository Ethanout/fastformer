package io.github.fastformer.client.render.hud;

import io.github.fastformer.client.feedback.AxisFeedback;
import io.github.fastformer.client.feedback.ScrollFeedbackData;
import io.github.fastformer.fastplace.geometry.GeometryPalette;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/** Draws a supplied feedback value and opacity; owns no interaction state. */
public final class PreviewFeedbackHud {
   private static final int AXIS_SPACING = 14;

   private PreviewFeedbackHud() {}

   public static void renderScrollFeedback(
      GuiGraphics graphics, Minecraft minecraft, ScrollFeedbackData data, int alpha, BottomHudLayout layout
   ) {
      if (alpha <= 0 || data == null || data.axes().isEmpty() && data.text().isBlank()) return;
      int y = layout.nextLine();
      if (!layout.visibleAt(y)) return;
      if (!data.axes().isEmpty()) renderAxes(graphics, minecraft, data.axes(), y, alpha);
      else graphics.drawCenteredString(minecraft.font, data.text(), graphics.guiWidth() / 2, y,
         withAlpha(GeometryPalette.text().argb(), alpha));
   }

   private static void renderAxes(GuiGraphics graphics, Minecraft minecraft, List<AxisFeedback> axes, int y, int alpha) {
      int width = axes.stream().mapToInt(axis -> minecraft.font.width(axis.label() + ":" + axis.value())).sum()
         + AXIS_SPACING * Math.max(0, axes.size() - 1);
      int x = (graphics.guiWidth() - width) / 2;
      for (AxisFeedback axis : axes) {
         String text = axis.label() + ":" + axis.value();
         graphics.drawString(minecraft.font, text, x, y, withAlpha(axis.color(), alpha), true);
         x += minecraft.font.width(text) + AXIS_SPACING;
      }
   }

   private static int withAlpha(int color, int alpha) {
      return (Math.clamp(alpha, 0, 255) << 24) | (color & 0x00FFFFFF);
   }
}
