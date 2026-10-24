package io.github.fastformer.client.ui;

import io.github.fastformer.fastplace.geometry.generation.FaceRasterizationMode;
import io.github.fastformer.workspace.submission.OperationConflictMode;
import io.github.fastformer.fastplace.placement.PlacementUpdateMode;
import io.github.fastformer.network.payload.settings.MiddleConfirmSettingPayload;
import io.github.fastformer.network.payload.settings.SettingsActionPayload;
import io.github.fastformer.network.payload.settings.TinkerHistorySettingPayload;
import io.github.fastformer.fastplace.history.TinkerHistoryMergeLimits;
import net.minecraft.client.gui.components.Tooltip;
import io.github.fastformer.network.payload.settings.PlacementEffectSettingPayload;
import io.github.fastformer.fastplace.placement.effect.PlacementEffect;
import io.github.fastformer.fastplace.placement.effect.PlacementEffectRegistry;
import io.github.fastformer.client.FastFormerClientConfig;
import io.github.fastformer.client.render.core.FastPlaceClientPreviewCore;
import io.github.fastformer.fastplace.geometry.GeometryPalette;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

public final class FastFormerSettingsScreen extends Screen {
   private boolean middleConfirmEnabled;
   private OperationConflictMode placementConflictMode;
   private PlacementUpdateMode placementUpdateMode;
   private final Set<ResourceLocation> enabledPlacementEffects;
   private boolean emptyHandWrench;
   private boolean skipRepeatedCropStages;
   private boolean vanillaBlockHistory;
   private boolean fallingDisabled;
   private int worldUndoHistoryLimit;
   private int sessionUndoHistoryLimit;
   private TinkerHistoryMergeLimits tinkerHistoryMergeLimits;
   private Button middleConfirmButton;
   private Button placementConflictButton;
   private Button placementUpdateButton;
   private final Map<ResourceLocation, Button> placementEffectButtons = new HashMap<>();
   private Button emptyHandWrenchButton;
   private Button cropStagesButton;
   private Button fallingRuleButton;
   private Button worldHistoryButton;
   private Button sessionHistoryButton;
   private Button vanillaHistoryButton;
   private Button themeButton;
   private Category category = Category.OPERATIONS;
   private OperationCategory operationCategory = OperationCategory.PLACEMENT;
   private final List<SettingRow> rows = new ArrayList<>();
   private int firstRow;
   private static final int ROW_HEIGHT = 30;

   private enum Category {
      OPERATIONS, APPEARANCE, HISTORY;
      String key() { return "fastformer.settings.category." + name().toLowerCase(java.util.Locale.ROOT); }
   }

   private enum OperationCategory {
      PLACEMENT, MICRO, FLIGHT;
      String key() { return "fastformer.settings.operation_category." + name().toLowerCase(java.util.Locale.ROOT); }
   }

   private record ControlBounds(int x, int y, int width) { }
   private record SettingRow(Component label, Consumer<ControlBounds> controls) { }

   public FastFormerSettingsScreen(
      boolean middleConfirmEnabled,
      FaceRasterizationMode faceRasterizationMode,
      OperationConflictMode placementConflictMode,
      PlacementUpdateMode placementUpdateMode,
      List<ResourceLocation> enabledPlacementEffects,
      boolean emptyHandWrench,
      boolean fallingDisabled,
      int worldUndoHistoryLimit,
      int sessionUndoHistoryLimit,
      TinkerHistoryMergeLimits tinkerHistoryMergeLimits,
      boolean skipRepeatedCropStages,
      boolean vanillaBlockHistory
   ) {
      super(Component.translatable("fastformer.settings.title"));
      this.middleConfirmEnabled = middleConfirmEnabled;
      this.placementConflictMode = placementConflictMode == null ? OperationConflictMode.REPLACE : placementConflictMode;
      this.placementUpdateMode = placementUpdateMode == null ? PlacementUpdateMode.CLIENT_ONLY : placementUpdateMode;
      this.enabledPlacementEffects = new HashSet<>(
         enabledPlacementEffects == null ? List.of() : enabledPlacementEffects
      );
      this.emptyHandWrench = emptyHandWrench;
      this.skipRepeatedCropStages = skipRepeatedCropStages;
      this.vanillaBlockHistory = vanillaBlockHistory;
      this.fallingDisabled = fallingDisabled;
      this.worldUndoHistoryLimit = Math.clamp((long)worldUndoHistoryLimit, 1, 800);
      this.sessionUndoHistoryLimit = Math.clamp((long)sessionUndoHistoryLimit, 1, 800);
      this.tinkerHistoryMergeLimits = tinkerHistoryMergeLimits == null ? TinkerHistoryMergeLimits.DEFAULTS : tinkerHistoryMergeLimits;
   }

   public static void open(
      boolean middleConfirmEnabled,
      FaceRasterizationMode faceRasterizationMode,
      OperationConflictMode placementConflictMode,
      PlacementUpdateMode placementUpdateMode,
      List<ResourceLocation> enabledPlacementEffects,
      boolean emptyHandWrench,
      boolean fallingDisabled,
      int worldUndoHistoryLimit,
      int sessionUndoHistoryLimit,
      TinkerHistoryMergeLimits tinkerHistoryMergeLimits,
      boolean skipRepeatedCropStages,
      boolean vanillaBlockHistory
   ) {
      Minecraft.getInstance().setScreen(
         new FastFormerSettingsScreen(
            middleConfirmEnabled, faceRasterizationMode, placementConflictMode,
            placementUpdateMode, enabledPlacementEffects, emptyHandWrench, fallingDisabled,
            worldUndoHistoryLimit, sessionUndoHistoryLimit, tinkerHistoryMergeLimits, skipRepeatedCropStages, vanillaBlockHistory
         )
      );
   }

   @Override
   protected void init() {
      rows.clear();
      placementEffectButtons.clear();
      fallingRuleButton = null;
      addCategoryTabs();
      switch (category) {
         case OPERATIONS -> {
            addOperationTabs();
            switch (operationCategory) {
               case PLACEMENT -> addPlacementRows();
               case MICRO -> addMicroRows();
               case FLIGHT -> addFlightRows();
            }
         }
         case APPEARANCE -> rows.add(new SettingRow(Component.translatable("fastformer.settings.theme"), bounds ->
            themeButton = addControl(bounds, themeLabel(), button -> cycleTheme())));
         case HISTORY -> addHistoryRows();
      }
      firstRow = Math.clamp(firstRow, 0, Math.max(0, rows.size() - visibleRows()));
      for (int index = firstRow; index < Math.min(rows.size(), firstRow + visibleRows()); index++) {
         SettingRow row = rows.get(index);
         if (row.controls() != null) row.controls().accept(new ControlBounds(
            panelLeft() + panelWidth() - controlWidth(), contentTop() + (index - firstRow) * ROW_HEIGHT, controlWidth()));
      }
      addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> onClose())
         .bounds(width / 2 - 80, height - 28, 160, 20).build());
   }

   private void addCategoryTabs() {
      int tabWidth = (panelWidth() - 8) / 3;
      for (Category tab : Category.values()) {
         Button button = addRenderableWidget(Button.builder(Component.translatable(tab.key()), ignored -> {
            category = tab;
            firstRow = 0;
            rebuildWidgets();
         }).bounds(panelLeft() + tab.ordinal() * (tabWidth + 4), 34, tabWidth, 20).build());
         button.active = tab != category;
      }
   }

   private void addOperationTabs() {
      int count = OperationCategory.values().length;
      int tabWidth = (panelWidth() - 4 * (count - 1)) / count;
      for (OperationCategory tab : OperationCategory.values()) {
         Button button = addRenderableWidget(Button.builder(Component.translatable(tab.key()), ignored -> {
            operationCategory = tab;
            firstRow = 0;
            rebuildWidgets();
         }).bounds(panelLeft() + tab.ordinal() * (tabWidth + 4), 60, tabWidth, 20).build());
         button.active = tab != operationCategory;
      }
   }

   private void addPlacementRows() {
      rows.add(new SettingRow(Component.translatable("fastformer.settings.middle_confirm"), bounds ->
         middleConfirmButton = addControl(bounds, middleConfirmLabel(), button -> toggleMiddleConfirm())));
      rows.add(new SettingRow(Component.translatable("fastformer.settings.placement_conflict"), bounds ->
         placementConflictButton = addControl(bounds, placementConflictLabel(), button -> cyclePlacementConflict())));
      rows.add(new SettingRow(Component.translatable("fastformer.settings.placement_update"), bounds ->
         placementUpdateButton = addControl(bounds, placementUpdateLabel(), button -> cyclePlacementUpdate())));
      rows.add(new SettingRow(Component.translatable("fastformer.settings.disable_falling"), bounds ->
         fallingRuleButton = addControl(bounds, fallingRuleLabel(), button -> toggleFallingRule())));
      for (PlacementEffect effect : PlacementEffectRegistry.effects()) {
         rows.add(new SettingRow(Component.translatable(effect.translationKey()), bounds -> placementEffectButtons.put(effect.id(),
            addControl(bounds, placementEffectLabel(effect.id()), button -> togglePlacementEffect(effect.id())))));
      }
   }

   private void addMicroRows() {
      rows.add(new SettingRow(Component.translatable("fastformer.settings.empty_hand_wrench"), bounds ->
         emptyHandWrenchButton = addControl(bounds, emptyHandWrenchLabel(), button -> toggleEmptyHandWrench())));
      rows.add(new SettingRow(Component.translatable("fastformer.settings.crop_stages"), bounds -> {
         cropStagesButton = addControl(bounds, cropStagesLabel(), button -> toggleCropStages());
         cropStagesButton.setTooltip(Tooltip.create(Component.translatable("fastformer.settings.crop_stages.help")));
      }));
      rows.add(new SettingRow(Component.translatable("fastformer.settings.vanilla_block_history"), bounds -> {
         vanillaHistoryButton = addControl(bounds, vanillaHistoryLabel(), button -> toggleVanillaHistory());
         vanillaHistoryButton.setTooltip(Tooltip.create(Component.translatable("fastformer.settings.vanilla_block_history.help")));
      }));
   }

   private void addHistoryRows() {
      rows.add(new SettingRow(Component.translatable("fastformer.settings.world_history"), bounds ->
         worldHistoryButton = addHistoryCounter(bounds, worldHistoryLabel(), this::adjustWorldHistory)));
      rows.add(new SettingRow(Component.translatable("fastformer.settings.session_history"), bounds ->
         sessionHistoryButton = addHistoryCounter(bounds, sessionHistoryLabel(), this::adjustSessionHistory)));
      rows.add(new SettingRow(Component.translatable("fastformer.settings.tinker_history_merge"), bounds -> {
         Button configure = addControl(bounds, Component.translatable("fastformer.settings.compression.configure"),
            button -> minecraft.setScreen(new MicroOperationCompressionScreen(this, tinkerHistoryMergeLimits, this::setTinkerHistory)));
         configure.setTooltip(Tooltip.create(Component.translatable("fastformer.settings.tinker_history.help")));
      }));
   }

   private void addFlightRows() {
      rows.add(new SettingRow(Component.translatable("fastformer.settings.flight_dash_distance"), bounds ->
         addRenderableWidget(new FlightDistanceSlider(bounds.x(), bounds.y(), bounds.width()))));
      rows.add(new SettingRow(Component.translatable("fastformer.settings.flight_acceleration"), bounds -> {
         Button toggle = addControl(bounds, flightAccelerationLabel(), button -> {
            FastFormerClientConfig.setFlightAcceleration(!FastFormerClientConfig.flightSettings().accelerationEnabled());
            button.setMessage(flightAccelerationLabel());
         });
         toggle.setTooltip(Tooltip.create(Component.translatable("fastformer.settings.flight_acceleration.help")));
      }));
   }

   private Component flightAccelerationLabel() {
      return Component.translatable(FastFormerClientConfig.flightSettings().accelerationEnabled() ? "options.on" : "options.off");
   }

   private Button addControl(ControlBounds bounds, Component label, Button.OnPress action) {
      return addRenderableWidget(Button.builder(label, action).bounds(bounds.x(), bounds.y(), bounds.width(), 20).build());
   }

   private Button addHistoryCounter(ControlBounds bounds, Component label, java.util.function.IntConsumer adjust) {
      addControl(new ControlBounds(bounds.x(), bounds.y(), 20), Component.literal("-"), button -> adjust.accept(-10));
      Button value = addControl(new ControlBounds(bounds.x() + 24, bounds.y(), bounds.width() - 48), label, button -> { });
      value.active = false;
      addControl(new ControlBounds(bounds.x() + bounds.width() - 20, bounds.y(), 20), Component.literal("+"), button -> adjust.accept(10));
      return value;
   }

   @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      super.render(graphics, mouseX, mouseY, partialTick);
      graphics.drawCenteredString(font, title, width / 2, 16, GeometryPalette.screenTitle().argb());
      for (int index = firstRow; index < Math.min(rows.size(), firstRow + visibleRows()); index++) {
         SettingRow row = rows.get(index);
         int labelWidth = row.controls() == null ? panelWidth() : panelWidth() - controlWidth() - 12;
         graphics.drawWordWrap(font, row.label(), panelLeft(), contentTop() + (index - firstRow) * ROW_HEIGHT + 5,
            labelWidth, row.controls() == null ? GeometryPalette.screenTitle().argb() : GeometryPalette.screenLabel().argb());
      }
      if (rows.size() > visibleRows()) {
         int x = panelLeft() + panelWidth() + 4;
         int trackHeight = visibleRows() * ROW_HEIGHT;
         int thumbHeight = Math.max(10, trackHeight * visibleRows() / rows.size());
         int y = contentTop() + (trackHeight - thumbHeight) * firstRow / (rows.size() - visibleRows());
         graphics.fill(x, contentTop(), x + 3, contentTop() + trackHeight, 0x40505050);
         graphics.fill(x, y, x + 3, y + thumbHeight, GeometryPalette.screenLabel().argb());
      }
   }

   @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
      if (mouseY >= contentTop() && mouseY < height - 36 && mouseX >= panelLeft() && mouseX <= panelLeft() + panelWidth() + 8) {
         int next = Math.clamp(firstRow - (int)Math.signum(vertical), 0, Math.max(0, rows.size() - visibleRows()));
         if (next != firstRow) { firstRow = next; rebuildWidgets(); return true; }
      }
      return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
   }

   private void toggleMiddleConfirm() {
      this.middleConfirmEnabled = !this.middleConfirmEnabled;
      this.middleConfirmButton.setMessage(this.middleConfirmLabel());
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.getConnection() != null && NetworkRegistry.hasChannel(minecraft.getConnection(), MiddleConfirmSettingPayload.TYPE.id())) {
         PacketDistributor.sendToServer(new MiddleConfirmSettingPayload(this.middleConfirmEnabled), new CustomPacketPayload[0]);
      }
   }

   private Component middleConfirmLabel() {
      return Component.translatable(this.middleConfirmEnabled ? "options.on" : "options.off");
   }

   private void cyclePlacementConflict() {
      this.placementConflictMode = next(this.placementConflictMode, OperationConflictMode.values());
      this.placementConflictButton.setMessage(this.placementConflictLabel());
      this.send(SettingsActionPayload.Action.CYCLE_PLACEMENT_CONFLICT);
   }

   private Component placementConflictLabel() {
      return Component.translatable(this.placementConflictMode.translationKey());
   }

   private void cyclePlacementUpdate() {
      this.placementUpdateMode = next(this.placementUpdateMode, PlacementUpdateMode.values());
      this.placementUpdateButton.setMessage(this.placementUpdateLabel());
      this.send(SettingsActionPayload.Action.CYCLE_PLACEMENT_UPDATE);
   }

   private void togglePlacementEffect(ResourceLocation effectId) {
      boolean enabled = !this.enabledPlacementEffects.remove(effectId);
      if (enabled) {
         this.enabledPlacementEffects.add(effectId);
      }
      this.placementEffectButtons.get(effectId).setMessage(this.placementEffectLabel(effectId));
      if (this.minecraft.getConnection() != null
         && NetworkRegistry.hasChannel(this.minecraft.getConnection(), PlacementEffectSettingPayload.TYPE.id())) {
         PacketDistributor.sendToServer(
            new PlacementEffectSettingPayload(effectId, enabled),
            new CustomPacketPayload[0]
         );
      }
   }

   private Component placementEffectLabel(ResourceLocation effectId) {
      return Component.translatable(this.enabledPlacementEffects.contains(effectId) ? "options.on" : "options.off");
   }

   private int panelWidth() { return Math.min(480, width - 28); }
   private int panelLeft() { return (width - panelWidth()) / 2; }
   private int controlWidth() { return Math.min(200, panelWidth() / 2); }
   private int contentTop() { return category == Category.OPERATIONS ? 92 : 66; }
   private int visibleRows() { return Math.max(1, (height - 40 - contentTop()) / ROW_HEIGHT); }

   private void toggleEmptyHandWrench() {
      this.emptyHandWrench = !this.emptyHandWrench;
      this.emptyHandWrenchButton.setMessage(this.emptyHandWrenchLabel());
      this.send(SettingsActionPayload.Action.TOGGLE_EMPTY_HAND_WRENCH);
   }

   private void toggleVanillaHistory() {
      vanillaBlockHistory = !vanillaBlockHistory;
      vanillaHistoryButton.setMessage(vanillaHistoryLabel());
      send(SettingsActionPayload.Action.TOGGLE_VANILLA_BLOCK_HISTORY);
   }

   private Component vanillaHistoryLabel() {
      return Component.translatable(vanillaBlockHistory ? "options.on" : "options.off");
   }

   private Component emptyHandWrenchLabel() {
      return Component.translatable(this.emptyHandWrench ? "options.on" : "options.off");
   }

   private void toggleCropStages() {
      this.skipRepeatedCropStages = !this.skipRepeatedCropStages;
      this.cropStagesButton.setMessage(this.cropStagesLabel());
      this.send(SettingsActionPayload.Action.TOGGLE_SKIP_REPEATED_CROP_STAGES);
   }

   private Component cropStagesLabel() {
      return Component.translatable(this.skipRepeatedCropStages ? "options.on" : "options.off");
   }

   private void toggleFallingRule() {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.getConnection() == null || !NetworkRegistry.hasChannel(minecraft.getConnection(), SettingsActionPayload.TYPE.id())) return;
      this.fallingRuleButton.active = false;
      PacketDistributor.sendToServer(new SettingsActionPayload(SettingsActionPayload.Action.TOGGLE_FALLING_DISABLED, !this.fallingDisabled));
   }

   private int rulePollTicks;

   @Override public void tick() {
      super.tick();
      if (fallingRuleButton == null) return;
      if (Minecraft.getInstance().getConnection() == null) { this.fallingRuleButton.active = false; return; }
      if (rulePollTicks++ % 20 == 0) this.send(SettingsActionPayload.Action.QUERY_FALLING_DISABLED);
   }

   public static void applyFallingState(io.github.fastformer.network.payload.settings.FallingStatePayload payload) {
      if (Minecraft.getInstance().screen instanceof FastFormerSettingsScreen screen) {
         screen.fallingDisabled = payload.fallingDisabled();
         if (screen.fallingRuleButton != null) {
            screen.fallingRuleButton.setMessage(screen.fallingRuleLabel());
            screen.fallingRuleButton.active = payload.allowed();
         }
      }
   }

   private Component fallingRuleLabel() {
      return Component.translatable(this.fallingDisabled ? "options.on" : "options.off");
   }

   private Component themeLabel() {
      return Component.translatable(GeometryPalette.theme().translationKey());
   }

   private void cycleTheme() {
      FastFormerClientConfig.setTheme(GeometryPalette.theme().next());
      FastPlaceClientPreviewCore.onThemeChanged();
      this.themeButton.setMessage(this.themeLabel());
   }

   private Component placementUpdateLabel() {
      return Component.translatable(this.placementUpdateMode.translationKey());
   }

   private void adjustWorldHistory(int amount) {
      this.worldUndoHistoryLimit = Math.clamp((long)this.worldUndoHistoryLimit + amount, 1, 800);
      this.worldHistoryButton.setMessage(this.worldHistoryLabel());
      this.send(amount < 0 ? SettingsActionPayload.Action.DECREASE_WORLD_HISTORY : SettingsActionPayload.Action.INCREASE_WORLD_HISTORY);
   }

   private void setTinkerHistory(TinkerHistoryMergeLimits.Setting setting, int value) {
      this.tinkerHistoryMergeLimits = this.tinkerHistoryMergeLimits.with(setting, value);
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.getConnection() != null && NetworkRegistry.hasChannel(minecraft.getConnection(), TinkerHistorySettingPayload.TYPE.id())) {
         PacketDistributor.sendToServer(new TinkerHistorySettingPayload(setting, this.tinkerHistoryMergeLimits.value(setting)));
      }
   }

   private Component worldHistoryLabel() {
      return Component.literal(String.valueOf(this.worldUndoHistoryLimit));
   }

   private void adjustSessionHistory(int amount) {
      this.sessionUndoHistoryLimit = Math.clamp((long)this.sessionUndoHistoryLimit + amount, 1, 800);
      this.sessionHistoryButton.setMessage(this.sessionHistoryLabel());
      this.send(amount < 0 ? SettingsActionPayload.Action.DECREASE_SESSION_HISTORY : SettingsActionPayload.Action.INCREASE_SESSION_HISTORY);
   }

   private Component sessionHistoryLabel() {
      return Component.literal(String.valueOf(this.sessionUndoHistoryLimit));
   }

   private void send(SettingsActionPayload.Action action) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.getConnection() != null && NetworkRegistry.hasChannel(minecraft.getConnection(), SettingsActionPayload.TYPE.id())) {
         PacketDistributor.sendToServer(new SettingsActionPayload(action), new CustomPacketPayload[0]);
      }
   }

   private static <E extends Enum<E>> E next(E current, E[] values) {
      return values[(current.ordinal() + 1) % values.length];
   }

   @Override
   public boolean isPauseScreen() {
      return false;
   }
}
