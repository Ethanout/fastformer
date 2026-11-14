package io.github.fastformer.client.render.hud;

import io.github.fastformer.client.preview.hud.SelectionHudModel;

import io.github.fastformer.client.preview.hud.HudModel;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Draws frozen HUD content using only frame-local graphics, font, and layout. */
public final class HudFrameRenderer {
   private HudFrameRenderer() {}

   public static void draw(GuiGraphics graphics, Font font, BottomHudLayout bottom, HudModel model) {
      for (var text : model.topLeft()) graphics.drawString(font, text.content(), 8, text.y(), text.color(), true);
      for (var text : model.topRight()) graphics.drawString(font, text.content(),
         Math.max(8, graphics.guiWidth() - font.width(text.content()) - 8), text.y(), text.color(), true);
      for (var text : model.bottom()) bottom.render(graphics, font, text);
      drawScroll(graphics, font, bottom, model);
      int y = graphics.guiHeight() / 2 + 6;
      for (var line : model.crosshair()) {
         int x = graphics.guiWidth() / 2 + 10;
         for (var run : line.runs()) {
            x += run.gap();
            graphics.pose().pushPose();
            graphics.pose().translate(x, y + (run.scale() == 1 ? 0 : 1), 0);
            graphics.pose().scale(run.scale(), run.scale(), 1);
            graphics.drawString(font, run.content(), 0, 0, run.color(), true);
            graphics.pose().popPose();
            x += (int)Math.ceil(font.width(run.content()) * run.scale());
         }
         y += 10;
      }
   }

   private static void drawScroll(GuiGraphics graphics, Font font, BottomHudLayout layout, HudModel model) {
      var data = model.scroll();
      int alpha = model.scrollAlpha();
      if (alpha <= 0 || data == null || data.axes().isEmpty() && data.text().isBlank()) return;
      int y = layout.nextLine();
      if (!layout.visibleAt(y)) return;
      if (data.axes().isEmpty()) {
         graphics.drawCenteredString(font, data.text(), graphics.guiWidth() / 2, y, alpha << 24 | model.scrollColor() & 0xFFFFFF);
         return;
      }
      int width = data.axes().stream().mapToInt(axis -> font.width(axis.label() + ":" + axis.value())).sum()
         + 14 * Math.max(0, data.axes().size() - 1);
      int x = (graphics.guiWidth() - width) / 2;
      for (var axis : data.axes()) {
         String text = axis.label() + ":" + axis.value();
         graphics.drawString(font, text, x, y, alpha << 24 | axis.color() & 0xFFFFFF, true);
         x += font.width(text) + 14;
      }
   }

   public static void drawSelection(GuiGraphics graphics, Font font, BottomHudLayout bottom, SelectionHudModel model) {
      for (var label : model.labels()) {
         graphics.pose().pushPose();
         graphics.pose().translate(label.x(), label.y(), 0);
         graphics.drawString(font, label.text(), 0, 0, label.color(), true);
         graphics.pose().popPose();
      }
      for (var corner : model.corners()) {
         graphics.pose().pushPose();
         graphics.pose().translate(corner.x(), corner.y(), 0);
         graphics.pose().scale((float)(1 / corner.scale()), (float)(1 / corner.scale()), 1);
         graphics.fill(Math.min(0, corner.dx()), 0, Math.max(0, corner.dx()) + corner.thickness(), corner.thickness(), corner.color());
         graphics.fill(0, Math.min(0, corner.dy()), corner.thickness(), Math.max(0, corner.dy()) + corner.thickness(), corner.color());
         graphics.pose().popPose();
      }
      for (var text : model.bottom()) bottom.render(graphics, font, text);
   }
}
