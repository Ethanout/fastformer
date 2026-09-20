package io.github.fastformer.client.ui;

import io.github.fastformer.fastplace.FaceRasterizationMode;
import io.github.fastformer.fastplace.OperationConflictMode;
import io.github.fastformer.fastplace.PlacementUpdateMode;
import io.github.fastformer.network.payload.settings.FaceRasterizationSettingPayload;
import io.github.fastformer.network.payload.settings.MiddleConfirmSettingPayload;
import io.github.fastformer.network.payload.settings.SettingsActionPayload;
import io.github.fastformer.network.payload.settings.PlacementEffectSettingPayload;
import io.github.fastformer.fastplace.placement.effect.PlacementEffect;
import io.github.fastformer.fastplace.placement.effect.PlacementEffectRegistry;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
   private FaceRasterizationMode faceRasterizationMode;
   private OperationConflictMode placementConflictMode;
   private PlacementUpdateMode placementUpdateMode;
   private final Set<ResourceLocation> enabledPlacementEffects;
   private boolean emptyHandWrench;
   private boolean globalFrozen;
   private int worldUndoHistoryLimit;
   private int sessionUndoHistoryLimit;
   private Button middleConfirmButton;
   private Button faceRasterizationButton;
   private Button placementConflictButton;
   private Button placementUpdateButton;
   private final Map<ResourceLocation, Button> placementEffectButtons = new HashMap<>();
   private Button emptyHandWrenchButton;
   private Button globalFreezeButton;
   private Button worldHistoryButton;
   private Button sessionHistoryButton;

   public FastFormerSettingsScreen(
      boolean middleConfirmEnabled,
      FaceRasterizationMode faceRasterizationMode,
      OperationConflictMode placementConflictMode,
      PlacementUpdateMode placementUpdateMode,
      List<ResourceLocation> enabledPlacementEffects,
      boolean emptyHandWrench,
      boolean globalFrozen,
      int worldUndoHistoryLimit,
      int sessionUndoHistoryLimit
   ) {
      super(Component.translatable("fastformer.settings.title"));
      this.middleConfirmEnabled = middleConfirmEnabled;
      this.faceRasterizationMode = faceRasterizationMode == null
         ? FaceRasterizationMode.POINT_SWEEP
         : faceRasterizationMode;
      this.placementConflictMode = placementConflictMode == null ? OperationConflictMode.REPLACE : placementConflictMode;
      this.placementUpdateMode = placementUpdateMode == null ? PlacementUpdateMode.CLIENT_ONLY : placementUpdateMode;
      this.enabledPlacementEffects = new HashSet<>(
         enabledPlacementEffects == null ? List.of() : enabledPlacementEffects
      );
      this.emptyHandWrench = emptyHandWrench;
      this.globalFrozen = globalFrozen;
      this.worldUndoHistoryLimit = Math.clamp((long)worldUndoHistoryLimit, 1, 800);
      this.sessionUndoHistoryLimit = Math.clamp((long)sessionUndoHistoryLimit, 1, 800);
   }

   public static void open(
      boolean middleConfirmEnabled,
      FaceRasterizationMode faceRasterizationMode,
      OperationConflictMode placementConflictMode,
      PlacementUpdateMode placementUpdateMode,
      List<ResourceLocation> enabledPlacementEffects,
      boolean emptyHandWrench,
      boolean globalFrozen,
      int worldUndoHistoryLimit,
      int sessionUndoHistoryLimit
   ) {
      Minecraft.getInstance().setScreen(
         new FastFormerSettingsScreen(
            middleConfirmEnabled, faceRasterizationMode, placementConflictMode,
            placementUpdateMode, enabledPlacementEffects, emptyHandWrench, globalFrozen,
            worldUndoHistoryLimit, sessionUndoHistoryLimit
         )
      );
   }

   @Override
   protected void init() {
      int centerX = this.width / 2;
      int top = this.settingsTop();
      int leftX = centerX - 206;
      int rightX = centerX + 6;
      this.middleConfirmButton = this.addRenderableWidget(
         Button.builder(this.middleConfirmLabel(), button -> this.toggleMiddleConfirm())
            .bounds(leftX, top, 200, 20)
            .build()
      );
      this.faceRasterizationButton = this.addRenderableWidget(
         Button.builder(this.faceRasterizationLabel(), button -> this.cycleFaceRasterization())
            .bounds(leftX, top + 34, 200, 20)
            .build()
      );
      this.placementConflictButton = this.addRenderableWidget(
         Button.builder(this.placementConflictLabel(), button -> this.cyclePlacementConflict())
            .bounds(leftX, top + 68, 200, 20)
            .build()
      );
      this.placementUpdateButton = this.addRenderableWidget(
         Button.builder(this.placementUpdateLabel(), button -> this.cyclePlacementUpdate())
            .bounds(leftX, top + 102, 200, 20)
            .build()
      );
      int effectRow = 0;
      this.placementEffectButtons.clear();
      for (PlacementEffect effect : PlacementEffectRegistry.effects()) {
         Button button = this.addRenderableWidget(
            Button.builder(this.placementEffectLabel(effect.id()), ignored -> this.togglePlacementEffect(effect.id()))
               .bounds(rightX, top + effectRow * 34, 200, 20).build()
         );
         this.placementEffectButtons.put(effect.id(), button);
         effectRow++;
      }
      int staticRow = this.rightStaticStartRow();
      this.emptyHandWrenchButton = this.addRenderableWidget(
         Button.builder(this.emptyHandWrenchLabel(), button -> this.toggleEmptyHandWrench())
            .bounds(rightX, top + staticRow * 34, 200, 20).build()
      );
      this.globalFreezeButton = this.addRenderableWidget(
         Button.builder(this.globalFreezeLabel(), button -> this.toggleGlobalFreeze())
            .bounds(rightX, top + (staticRow + 1) * 34, 200, 20).build()
      );
      this.addRenderableWidget(Button.builder(Component.literal("-"), button -> this.adjustWorldHistory(-10))
         .bounds(rightX, top + (staticRow + 2) * 34, 20, 20).build());
      this.worldHistoryButton = this.addRenderableWidget(Button.builder(this.worldHistoryLabel(), button -> {})
         .bounds(rightX + 24, top + (staticRow + 2) * 34, 152, 20).build());
      this.addRenderableWidget(Button.builder(Component.literal("+"), button -> this.adjustWorldHistory(10))
         .bounds(rightX + 180, top + (staticRow + 2) * 34, 20, 20).build());
      this.addRenderableWidget(Button.builder(Component.literal("-"), button -> this.adjustSessionHistory(-10))
         .bounds(rightX, top + (staticRow + 3) * 34, 20, 20).build());
      this.sessionHistoryButton = this.addRenderableWidget(Button.builder(this.sessionHistoryLabel(), button -> {})
         .bounds(rightX + 24, top + (staticRow + 3) * 34, 152, 20).build());
      this.addRenderableWidget(Button.builder(Component.literal("+"), button -> this.adjustSessionHistory(10))
         .bounds(rightX + 180, top + (staticRow + 3) * 34, 20, 20).build());
      this.addRenderableWidget(
         Button.builder(Component.translatable("gui.done"), button -> this.onClose())
            .bounds(centerX - 100, top + this.contentRows() * 34 + 6, 200, 20)
            .build()
      );
   }

   @Override
   public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      this.renderBackground(graphics, mouseX, mouseY, partialTick);
      int top = this.settingsTop();
      int leftX = this.width / 2 - 206;
      int rightX = this.width / 2 + 6;
      graphics.drawCenteredString(this.font, this.title, this.width / 2, top - 18, 0xFFFFFFFF);
      this.labelAt(graphics, "fastformer.settings.middle_confirm", leftX, top - 11);
      this.labelAt(graphics, "fastformer.settings.face_rasterization", leftX, top + 23);
      this.labelAt(graphics, "fastformer.settings.placement_conflict", leftX, top + 57);
      this.labelAt(graphics, "fastformer.settings.placement_update", leftX, top + 91);
      int effectRow = 0;
      for (PlacementEffect effect : PlacementEffectRegistry.effects()) {
         this.labelAt(graphics, effect.translationKey(), rightX, top + effectRow * 34 - 11);
         effectRow++;
      }
      int staticRow = this.rightStaticStartRow();
      this.labelAt(graphics, "fastformer.settings.empty_hand_wrench", rightX, top + staticRow * 34 - 11);
      this.labelAt(graphics, "fastformer.settings.global_freeze", rightX, top + (staticRow + 1) * 34 - 11);
      this.labelAt(graphics, "fastformer.settings.world_history", rightX, top + (staticRow + 2) * 34 - 11);
      this.labelAt(graphics, "fastformer.settings.session_history", rightX, top + (staticRow + 3) * 34 - 11);
      super.render(graphics, mouseX, mouseY, partialTick);
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

   private void cycleFaceRasterization() {
      this.faceRasterizationMode = this.faceRasterizationMode.next();
      this.faceRasterizationButton.setMessage(this.faceRasterizationLabel());
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.getConnection() != null
         && NetworkRegistry.hasChannel(
            minecraft.getConnection(), FaceRasterizationSettingPayload.TYPE.id()
         )) {
         PacketDistributor.sendToServer(
            new FaceRasterizationSettingPayload(this.faceRasterizationMode),
            new CustomPacketPayload[0]
         );
      }
   }

   private Component faceRasterizationLabel() {
      return Component.translatable(switch (this.faceRasterizationMode) {
         case POINT_SWEEP -> "fastformer.settings.face_rasterization.point_sweep";
         case GRADIENT_CROSS_INTERPOLATED_EXPERIMENTAL -> "fastformer.settings.face_rasterization.gradient_cross_experimental";
      });
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

   private int rightStaticStartRow() {
      return PlacementEffectRegistry.effects().size();
   }

   private int contentRows() {
      return Math.max(4, this.rightStaticStartRow() + 4);
   }

   private int settingsTop() {
      return Math.max(36, this.height / 2 - (this.contentRows() * 34 + 20) / 2);
   }

   private void toggleEmptyHandWrench() {
      this.emptyHandWrench = !this.emptyHandWrench;
      this.emptyHandWrenchButton.setMessage(this.emptyHandWrenchLabel());
      this.send(SettingsActionPayload.Action.TOGGLE_EMPTY_HAND_WRENCH);
   }

   private Component emptyHandWrenchLabel() {
      return Component.translatable(this.emptyHandWrench ? "options.on" : "options.off");
   }

   private void toggleGlobalFreeze() {
      this.globalFrozen = !this.globalFrozen;
      this.globalFreezeButton.setMessage(this.globalFreezeLabel());
      this.send(SettingsActionPayload.Action.TOGGLE_GLOBAL_FREEZE);
   }

   private Component globalFreezeLabel() {
      return Component.translatable(this.globalFrozen ? "options.on" : "options.off");
   }

   private Component placementUpdateLabel() {
      return Component.translatable(this.placementUpdateMode.translationKey());
   }

   private void adjustWorldHistory(int amount) {
      this.worldUndoHistoryLimit = Math.clamp((long)this.worldUndoHistoryLimit + amount, 1, 800);
      this.worldHistoryButton.setMessage(this.worldHistoryLabel());
      this.send(amount < 0 ? SettingsActionPayload.Action.DECREASE_WORLD_HISTORY : SettingsActionPayload.Action.INCREASE_WORLD_HISTORY);
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

   private void label(GuiGraphics graphics, String key, int offsetY) {
      graphics.drawString(this.font, Component.translatable(key), this.width / 2 - 100, this.height / 2 + offsetY, 0xFFE0E7ED);
   }

   private void labelAt(GuiGraphics graphics, String key, int y) {
      graphics.drawString(this.font, Component.translatable(key), this.width / 2 - 100, y, 0xFFE0E7ED);
   }

   private void labelAt(GuiGraphics graphics, String key, int x, int y) {
      graphics.drawString(this.font, Component.translatable(key), x, y, 0xFFE0E7ED);
   }

   private static <E extends Enum<E>> E next(E current, E[] values) {
      return values[(current.ordinal() + 1) % values.length];
   }

   @Override
   public boolean isPauseScreen() {
      return false;
   }
}
