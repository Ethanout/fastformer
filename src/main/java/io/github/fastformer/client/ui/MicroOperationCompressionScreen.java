package io.github.fastformer.client.ui;

import io.github.fastformer.fastplace.geometry.GeometryPalette;
import io.github.fastformer.fastplace.history.TinkerHistoryMergeLimits;
import io.github.fastformer.fastplace.history.TinkerHistoryMergeLimits.Setting;
import io.github.fastformer.fastplace.history.TinkerHistoryMergeLimits.Tier;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.function.BiConsumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Each row reads as a sentence with an age threshold and a record count. */
final class MicroOperationCompressionScreen extends Screen {
   private static final int FIELD_WIDTH = 40;
   private final Screen parent;
   private final BiConsumer<Setting, Integer> save;
   private final EnumMap<Setting, EditBox> fields = new EnumMap<>(Setting.class);
   private final List<Label> labels = new ArrayList<>();
   private TinkerHistoryMergeLimits limits;
   private int firstRow;

   private record Label(Component text, int x, int y, boolean wrap) { }

   MicroOperationCompressionScreen(Screen parent, TinkerHistoryMergeLimits limits, BiConsumer<Setting, Integer> save) {
      super(Component.translatable("fastformer.settings.tinker_history_merge"));
      this.parent = parent;
      this.limits = limits;
      this.save = save;
   }

   @Override protected void init() {
      fields.clear();
      labels.clear();
      firstRow = Math.clamp(firstRow, 0, Tier.values().length - visibleRows());
      for (int index = firstRow; index < Math.min(3, firstRow + visibleRows()); index++) {
         addTier(Tier.values()[index], rowTop() + (index - firstRow) * rowHeight());
      }
      int buttonWidth = Math.min(150, (panelWidth() - 8) / 2);
      addRenderableWidget(Button.builder(Component.translatable("controls.reset"), button -> {
         apply(TinkerHistoryMergeLimits.DEFAULTS);
         rebuildWidgets();
      }).bounds(width / 2 - buttonWidth - 4, height - 28, buttonWidth, 20).build());
      addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
         .bounds(width / 2 + 4, height - 28, buttonWidth, 20).build());
   }

   private void addTier(Tier tier, int y) {
      int x = label(Component.translatable(tier.translationKey()), left(), y + 5);
      x = number(tier.afterSetting(), x, y);
      x = label(text("after"), x, y + 5);
      if (wrapped()) {
         y += 24;
         x = left();
      }
      x = label(text("every"), x, y + 5);
      x = number(tier.groupSetting(), x, y);
      label(text("records"), x, y + 5);
      if (tier != Tier.RECENT) labels.add(new Label(text(tier == Tier.OLDER ? "recent_record" : "older_record"), left(), y + 27, true));
   }

   private int label(Component text, int x, int y) {
      labels.add(new Label(text, x, y, false));
      return x + font.width(text) + 3;
   }

   private int number(Setting setting, int x, int y) {
      var field = new EditBox(font, x, y, FIELD_WIDTH, 20, title);
      field.setMaxLength(5);
      field.setFilter(value -> value.isEmpty() || value.chars().allMatch(character -> character >= '0' && character <= '9'));
      field.setValue(Integer.toString(limits.value(setting)));
      boolean threshold = setting.ordinal() % 2 == 0;
      field.setTooltip(Tooltip.create(text(threshold ? "threshold_help" : "group_help")));
      fields.put(setting, addRenderableWidget(field));
      return x + FIELD_WIDTH + 3;
   }

   private int value(Setting setting) {
      var field = fields.get(setting);
      return field == null || field.getValue().isEmpty() ? limits.value(setting) : Integer.parseInt(field.getValue());
   }

   private void saveFields() {
      apply(new TinkerHistoryMergeLimits(value(Setting.RECENT_AFTER), value(Setting.RECENT_GROUP),
         value(Setting.OLDER_AFTER), value(Setting.OLDER_GROUP), value(Setting.OLDEST_AFTER), value(Setting.OLDEST_GROUP)));
   }

   private void apply(TinkerHistoryMergeLimits target) {
      // Expand upper bounds first so saving several edited thresholds cannot truncate a lower one.
      update(Setting.OLDEST_AFTER, Math.max(limits.oldestAfter(), target.oldestAfter()));
      update(Setting.OLDER_AFTER, Math.max(limits.olderAfter(), target.olderAfter()));
      for (var setting : Setting.values()) update(setting, target.value(setting));
   }

   private void update(Setting setting, int value) {
      var updated = limits.with(setting, value);
      if (!updated.equals(limits)) {
         limits = updated;
         save.accept(setting, updated.value(setting));
      }
   }

   @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      super.render(graphics, mouseX, mouseY, partialTick);
      int color = GeometryPalette.screenLabel().argb();
      graphics.drawCenteredString(font, title, width / 2, 16, GeometryPalette.screenTitle().argb());
      graphics.drawWordWrap(font, text("help"), left(), 34, panelWidth(), color);
      for (var label : labels) {
         if (label.wrap()) graphics.drawWordWrap(font, label.text(), label.x(), label.y(), panelWidth(), color);
         else graphics.drawString(font, label.text(), label.x(), label.y(), color);
      }
      if (visibleRows() < 3) graphics.drawCenteredString(font, text("scroll"), width / 2, height - 42, color);
   }

   @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
      int next = Math.clamp(firstRow - (int)Math.signum(vertical), 0, 3 - visibleRows());
      if (next == firstRow) return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
      saveFields();
      firstRow = next;
      rebuildWidgets();
      return true;
   }

   @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
      if (key == 257 || key == 335) {
         saveFields();
         rebuildWidgets();
         return true;
      }
      return super.keyPressed(key, scanCode, modifiers);
   }

   @Override public void resize(net.minecraft.client.Minecraft minecraft, int width, int height) {
      saveFields();
      super.resize(minecraft, width, height);
   }

   @Override public void onClose() {
      saveFields();
      minecraft.setScreen(parent);
   }

   private Component text(String key) { return Component.translatable("fastformer.settings.compression." + key); }
   private int rowTop() { return 46 + font.split(text("help"), panelWidth()).size() * font.lineHeight; }
   private int panelWidth() { return Math.min(680, width - 32); }
   private int left() { return (width - panelWidth()) / 2; }
   private boolean wrapped() {
      int widestPrefix = 0;
      for (var tier : Tier.values()) widestPrefix = Math.max(widestPrefix, font.width(Component.translatable(tier.translationKey())));
      return widestPrefix + 2 * FIELD_WIDTH + font.width(text("after")) + font.width(text("every"))
         + font.width(text("records")) + 18 > panelWidth();
   }
   private int rowHeight() {
      int noteLines = Math.max(font.split(text("recent_record"), panelWidth()).size(), font.split(text("older_record"), panelWidth()).size());
      return (wrapped() ? 24 : 0) + 33 + noteLines * font.lineHeight;
   }
   private int visibleRows() { return Math.clamp((height - rowTop() - 48) / rowHeight(), 1, 3); }
}
