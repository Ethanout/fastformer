package io.github.fastformer.client.render.hud;

import io.github.fastformer.client.render.PreviewStyle;
import io.github.fastformer.client.preview.style.GeometryPalette;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** Wraps bottom hints within the screen and returns their top edge. */
public final class HudTextRenderer {
   private HudTextRenderer() { }

   public static int centered(GuiGraphics graphics, Minecraft minecraft, Component text, int bottomLineY) {
      int width = Math.max(1, graphics.guiWidth() - PreviewStyle.HUD_MARGIN * 2);
      var lines = minecraft.font.split(text, width);
      return centeredLines(graphics, minecraft, lines, bottomLineY);
   }

   static int centeredLines(GuiGraphics graphics, Minecraft minecraft,
      java.util.List<net.minecraft.util.FormattedCharSequence> lines, int bottomLineY) {
      return centeredLines(graphics, minecraft.font, lines, bottomLineY);
   }

   static int centeredLines(GuiGraphics graphics, net.minecraft.client.gui.Font font,
      java.util.List<net.minecraft.util.FormattedCharSequence> lines, int bottomLineY) {
      int top = bottomLineY - Math.max(0, lines.size() - 1) * PreviewStyle.HUD_LINE_HEIGHT;
      int y = top;
      for (var line : lines) {
         graphics.drawString(font, line, (graphics.guiWidth() - font.width(line)) / 2,
            y, GeometryPalette.text().argb(), true);
         y += PreviewStyle.HUD_LINE_HEIGHT;
      }
      return top;
   }
}
