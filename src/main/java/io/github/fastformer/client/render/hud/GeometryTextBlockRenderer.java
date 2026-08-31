package io.github.fastformer.client.render.hud;

import io.github.fastformer.fastplace.geometry.GeometryTextBlock;
import io.github.fastformer.fastplace.geometry.GeometryPreviewPlan;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** Places geometry workflow text in its declared HUD regions. */
public final class GeometryTextBlockRenderer {
   private static final int LINE_HEIGHT = 12;

   private GeometryTextBlockRenderer() {
   }

   public static void render(GuiGraphics graphics, Minecraft minecraft, GeometryPreviewPlan plan) {
      if (plan == null) {
         return;
      }
      LayoutLines lines = new LayoutLines();
      for (GeometryTextBlock block : plan.textBlocks()) {
         if (!isRenderable(block)) {
            continue;
         }
         Component content = hintStyle(block);
         switch (block.placement()) {
            case TOP_LEFT -> graphics.drawString(
               minecraft.font, content, 8, 32 + lines.nextTopLeft() * LINE_HEIGHT, -1, true
            );
            case TOP_RIGHT -> graphics.drawString(
               minecraft.font,
               content,
               Math.max(8, graphics.guiWidth() - minecraft.font.width(content) - 8),
               32 + lines.nextTopRight() * LINE_HEIGHT,
               -1,
               true
            );
            case BOTTOM_CENTER -> graphics.drawCenteredString(
               minecraft.font,
               content,
               graphics.guiWidth() / 2,
               graphics.guiHeight() - 64 - lines.nextBottomCenter() * LINE_HEIGHT,
               -1
            );
            case BOTTOM_HINT -> graphics.drawCenteredString(
               minecraft.font,
               content,
               graphics.guiWidth() / 2,
               graphics.guiHeight() - 50 - lines.nextBottomHint() * LINE_HEIGHT,
               -1
            );
            case CROSSHAIR -> graphics.drawString(
               minecraft.font,
               content,
               graphics.guiWidth() / 2 + 10,
               graphics.guiHeight() / 2 + 6 + lines.nextCrosshair() * LINE_HEIGHT,
               -1,
               true
            );
         }
      }
   }

   private static boolean isRenderable(GeometryTextBlock block) {
      return block.visible()
         && GeometryTextBlock.hasContent(block.content())
         && !isLayoutControl(block.id());
   }

   private static boolean isLayoutControl(String id) {
      return GeometryTextBlock.STAGE_ID.equals(id)
         || GeometryTextBlock.MODE_ID.equals(id)
         || GeometryTextBlock.VALUE_ID.equals(id);
   }

   private static Component hintStyle(GeometryTextBlock block) {
      return GeometryTextBlock.HINT_ID.equals(block.id())
         ? block.content().copy().withStyle(ChatFormatting.GRAY)
         : block.content();
   }

   private static final class LayoutLines {
      private int topLeft;
      private int topRight;
      private int bottomCenter;
      private int bottomHint;
      private int crosshair;

      private int nextTopLeft() {
         return topLeft++;
      }

      private int nextTopRight() {
         return topRight++;
      }

      private int nextBottomCenter() {
         return bottomCenter++;
      }

      private int nextBottomHint() {
         return bottomHint++;
      }

      private int nextCrosshair() {
         return crosshair++;
      }
   }
}
