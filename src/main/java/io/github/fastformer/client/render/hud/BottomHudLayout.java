package io.github.fastformer.client.render.hud;

import io.github.fastformer.client.render.PreviewStyle;
import io.github.fastformer.client.mixin.GuiAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** Shares the item-name line with vanilla and stacks the remaining hints above it. */
public final class BottomHudLayout {
   private final int itemNameY;
   private final boolean itemNameVisible;
   private final boolean actionBarVisible;
   private int nextLineY;

   public BottomHudLayout(int screenHeight, int leftHeight, int rightHeight, boolean canHurtPlayer,
      boolean itemNameVisible, boolean actionBarVisible) {
      itemNameY = screenHeight - Math.max(59, Math.max(leftHeight, rightHeight)) + (canHurtPlayer ? 0 : 14);
      nextLineY = itemNameY;
      this.itemNameVisible = itemNameVisible;
      this.actionBarVisible = actionBarVisible;
   }

   public static BottomHudLayout forFrame(GuiGraphics graphics, Minecraft minecraft) {
      var gui = (GuiAccessor) minecraft.gui;
      boolean itemNameVisible = gui.fastformer$toolHighlightTimer() > 0 && !gui.fastformer$lastToolHighlight().isEmpty();
      return new BottomHudLayout(graphics.guiHeight(), minecraft.gui.leftHeight, minecraft.gui.rightHeight,
         minecraft.gameMode == null || minecraft.gameMode.canHurtPlayer(), itemNameVisible, gui.fastformer$overlayMessageTime() > 0);
   }

   public void render(GuiGraphics graphics, Minecraft minecraft, Component text) {
      if (text == null || text.getString().isBlank()) return;
      var lines = minecraft.font.split(text, Math.max(1, graphics.guiWidth() - PreviewStyle.HUD_MARGIN * 2));
      int bottom = reserveLines(lines.size());
      if (visibleAt(bottom)) HudTextRenderer.centeredLines(graphics, minecraft, lines, bottom);
   }

   public int nextLine() {
      return reserveLines(1);
   }

   int reserveLines(int count) {
      int y = nextLineY;
      nextLineY -= PreviewStyle.HUD_LINE_HEIGHT * Math.max(1, count);
      return y;
   }

   public boolean visibleAt(int bottomLineY) {
      return !actionBarVisible && (!itemNameVisible || bottomLineY < itemNameY);
   }
}
