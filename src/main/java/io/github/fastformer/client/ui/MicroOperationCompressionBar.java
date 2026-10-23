package io.github.fastformer.client.ui;

import io.github.fastformer.fastplace.geometry.GeometryPalette;
import io.github.fastformer.fastplace.history.TinkerHistoryMergeLimits;
import io.github.fastformer.fastplace.history.TinkerHistoryMergeLimits.Setting;
import java.util.function.BiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

final class MicroOperationCompressionBar extends AbstractWidget {
   private static final Setting[] CONTROLS = {
      Setting.NEAR_DISTANCE, Setting.FAR_DISTANCE, Setting.NEAR_LIMIT, Setting.MIDDLE_LIMIT, Setting.FAR_LIMIT
   };
   private static final int GRAPH_TOP = 14;
   private static final int GRAPH_BOTTOM = 36;
   private static final double DISTANCE_SCALE = Math.log1p(256);
   private final BiConsumer<Setting, Integer> save;
   private TinkerHistoryMergeLimits limits;
   private TinkerHistoryMergeLimits savedLimits;
   private Setting selected = Setting.NEAR_LIMIT;
   private double dragStartY;
   private double dragStartX;
   private int dragStartBoundary;
   private int dragStartValue;

   MicroOperationCompressionBar(int x, int y, int width, TinkerHistoryMergeLimits limits,
      BiConsumer<Setting, Integer> save) {
      super(x, y, width, 56, Component.translatable("fastformer.settings.tinker_history_merge"));
      this.limits = limits;
      this.savedLimits = limits;
      this.save = save;
      setTooltip(Tooltip.create(Component.translatable("fastformer.settings.tinker_history.help")));
      updateMessage();
   }

   @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      var font = Minecraft.getInstance().font;
      int near = boundaryX(limits.nearDistance());
      int far = boundaryX(limits.farDistance());
      int[] edges = {getX(), near, far, getX() + width};
      graphics.drawCenteredString(font, Component.translatable("fastformer.settings.micro_history.boundaries",
         limits.nearDistance(), limits.farDistance()), getX() + width / 2, getY(), GeometryPalette.screenLabel().argb());
      graphics.fill(getX(), getY() + GRAPH_TOP, getX() + width, getY() + GRAPH_BOTTOM, 0xff242424);
      for (int band = 0; band < 3; band++) renderBand(graphics, edges[band], edges[band + 1], band);
      renderBoundary(graphics, near, Setting.NEAR_DISTANCE);
      renderBoundary(graphics, far, Setting.FAR_DISTANCE);
      if (isFocused()) graphics.renderOutline(getX() - 1, getY() - 1, width + 2, height + 2, 0xffa0a0a0);
   }

   private void renderBand(GuiGraphics graphics, int left, int right, int band) {
      Setting setting = CONTROLS[band + 2];
      int limit = limits.value(setting);
      int top = getY() + GRAPH_BOTTOM - 2 - (int)Math.round(Math.log(limit) / Math.log(256) * (GRAPH_BOTTOM - GRAPH_TOP - 4));
      boolean selectedBand = setting == selected;
      graphics.fill(left + 1, top, Math.max(left + 1, right - 1), getY() + GRAPH_BOTTOM - 1,
         selectedBand ? 0xff758692 : 0xff50575c);
      graphics.fill(left + 1, top, Math.max(left + 1, right - 1), top + 1,
         selectedBand ? GeometryPalette.screenTitle().argb() : 0xffcccccc);
      int center = (left + right) / 2;
      if (right - left > 8) graphics.fill(center - 2, top - 2, center + 3, top + 3, 0xffeeeeee);
      // Fixed caption columns keep narrow distance bands readable.
      graphics.drawCenteredString(Minecraft.getInstance().font, Component.translatable(
         "fastformer.settings.micro_history.band." + band, limit), getX() + width * (2 * band + 1) / 6,
         getY() + 42, selectedBand ? GeometryPalette.screenTitle().argb() : GeometryPalette.screenLabel().argb());
   }

   private void renderBoundary(GuiGraphics graphics, int x, Setting setting) {
      int color = selected == setting ? GeometryPalette.screenTitle().argb() : 0xffeeeeee;
      graphics.fill(x - 1, getY() + GRAPH_TOP - 3, x + 2, getY() + GRAPH_BOTTOM + 3, color);
      graphics.fill(x - 3, getY() + GRAPH_TOP - 4, x + 4, getY() + GRAPH_TOP, color);
   }

   private int boundaryX(int distance) {
      return getX() + 4 + (int)Math.round(Math.log1p(distance) / DISTANCE_SCALE * (width - 8));
   }

   @Override public void onClick(double mouseX, double mouseY) {
      if (mouseY >= getY() + GRAPH_TOP - 4 && mouseY <= getY() + GRAPH_BOTTOM + 3) {
         double nearDistance = Math.abs(mouseX - boundaryX(limits.nearDistance()));
         double farDistance = Math.abs(mouseX - boundaryX(limits.farDistance()));
         if (Math.min(nearDistance, farDistance) <= 6) {
            selected = nearDistance <= farDistance ? Setting.NEAR_DISTANCE : Setting.FAR_DISTANCE;
         } else selected = mouseX < boundaryX(limits.nearDistance()) ? Setting.NEAR_LIMIT
            : mouseX < boundaryX(limits.farDistance()) ? Setting.MIDDLE_LIMIT : Setting.FAR_LIMIT;
      } else {
         int band = Math.clamp((int)((mouseX - getX()) * 3 / width), 0, 2);
         selected = CONTROLS[band + 2];
      }
      dragStartY = mouseY;
      dragStartX = mouseX;
      dragStartValue = limits.value(selected);
      dragStartBoundary = boundaryX(dragStartValue);
      updateMessage();
   }

   @Override protected void onDrag(double mouseX, double mouseY, double deltaX, double deltaY) {
      int value;
      if (selected == Setting.NEAR_DISTANCE || selected == Setting.FAR_DISTANCE) {
         double draggedX = dragStartBoundary + mouseX - dragStartX;
         double fraction = Math.clamp((draggedX - getX() - 4) / (width - 8), 0, 1);
         value = (int)Math.round(Math.expm1(fraction * DISTANCE_SCALE));
      } else {
         double exponent = Math.clamp((dragStartY - mouseY) / 20, -8, 8);
         value = (int)Math.round(dragStartValue * Math.pow(2, exponent));
      }
      changeValue(value);
   }

   @Override public void onRelease(double mouseX, double mouseY) { commit(); }

   @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
      if (!active || !visible || !isMouseOver(mouseX, mouseY) || vertical == 0) return false;
      changeValue(limits.value(selected) + (int)Math.signum(vertical));
      commit();
      return true;
   }

   @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
      if (!isFocused() || !active) return false;
      if (keyCode == GLFW.GLFW_KEY_SPACE) {
         for (int index = 0; index < CONTROLS.length; index++) {
            if (CONTROLS[index] == selected) { selected = CONTROLS[(index + 1) % CONTROLS.length]; break; }
         }
         updateMessage();
         return true;
      }
      int delta = keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_DOWN ? -1
         : keyCode == GLFW.GLFW_KEY_RIGHT || keyCode == GLFW.GLFW_KEY_UP ? 1 : 0;
      if (delta == 0) return false;
      changeValue(limits.value(selected) + delta);
      commit();
      return true;
   }

   private void changeValue(int value) {
      limits = limits.with(selected, value);
      updateMessage();
   }

   private void updateMessage() {
      setMessage(Component.translatable(selected.translationKey(), limits.value(selected)));
   }

   private void commit() {
      for (Setting setting : CONTROLS) {
         if (limits.value(setting) != savedLimits.value(setting)) save.accept(setting, limits.value(setting));
      }
      savedLimits = limits;
   }

   @Override protected void updateWidgetNarration(NarrationElementOutput narration) {
      narration.add(NarratedElementType.TITLE, Component.translatable("fastformer.settings.tinker_history_merge")
         .append(": ").append(getMessage()));
   }
}
