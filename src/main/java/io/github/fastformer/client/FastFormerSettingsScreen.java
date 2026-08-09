package io.github.fastformer.client;

import io.github.fastformer.fastplace.FaceRasterizationMode;
import io.github.fastformer.fastplace.OperationConflictMode;
import io.github.fastformer.fastplace.PlacementUpdateMode;
import io.github.fastformer.fastplace.RaycastPlacement;
import io.github.fastformer.network.FaceRasterizationSettingPayload;
import io.github.fastformer.network.MiddleConfirmSettingPayload;
import io.github.fastformer.network.SettingsActionPayload;
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
   private RaycastPlacement raycastPlacement;
   private OperationConflictMode placementConflictMode;
   private PlacementUpdateMode placementUpdateMode;
   private boolean smartWoodFrame;
   private int worldUndoHistoryLimit;
   private int sessionUndoHistoryLimit;
   private Button middleConfirmButton;
   private Button faceRasterizationButton;
   private Button raycastPlacementButton;
   private Button placementConflictButton;
   private Button placementUpdateButton;
   private Button smartWoodFrameButton;
   private Button worldHistoryButton;
   private Button sessionHistoryButton;

   public FastFormerSettingsScreen(
      boolean middleConfirmEnabled,
      FaceRasterizationMode faceRasterizationMode,
      RaycastPlacement raycastPlacement,
      OperationConflictMode placementConflictMode,
      PlacementUpdateMode placementUpdateMode,
      boolean smartWoodFrame,
      int worldUndoHistoryLimit,
      int sessionUndoHistoryLimit
   ) {
      super(Component.translatable("fastformer.settings.title"));
      this.middleConfirmEnabled = middleConfirmEnabled;
      this.faceRasterizationMode = faceRasterizationMode == null
         ? FaceRasterizationMode.POINT_SWEEP
         : faceRasterizationMode;
      this.raycastPlacement = raycastPlacement == null ? RaycastPlacement.EMBEDDED : raycastPlacement;
      this.placementConflictMode = placementConflictMode == null ? OperationConflictMode.REPLACE : placementConflictMode;
      this.placementUpdateMode = placementUpdateMode == null ? PlacementUpdateMode.NORMAL : placementUpdateMode;
      this.smartWoodFrame = smartWoodFrame;
      this.worldUndoHistoryLimit = Math.clamp((long)worldUndoHistoryLimit, 1, 800);
      this.sessionUndoHistoryLimit = Math.clamp((long)sessionUndoHistoryLimit, 1, 800);
   }

   public static void open(
      boolean middleConfirmEnabled,
      FaceRasterizationMode faceRasterizationMode,
      RaycastPlacement raycastPlacement,
      OperationConflictMode placementConflictMode,
      PlacementUpdateMode placementUpdateMode,
      boolean smartWoodFrame,
      int worldUndoHistoryLimit,
      int sessionUndoHistoryLimit
   ) {
      Minecraft.getInstance().setScreen(
         new FastFormerSettingsScreen(
            middleConfirmEnabled, faceRasterizationMode, raycastPlacement, placementConflictMode,
            placementUpdateMode, smartWoodFrame, worldUndoHistoryLimit, sessionUndoHistoryLimit
         )
      );
   }

   @Override
   protected void init() {
      int centerX = this.width / 2;
      this.middleConfirmButton = this.addRenderableWidget(
         Button.builder(this.middleConfirmLabel(), button -> this.toggleMiddleConfirm())
            .bounds(centerX - 100, this.height / 2 - 82, 200, 20)
            .build()
      );
      this.faceRasterizationButton = this.addRenderableWidget(
         Button.builder(this.faceRasterizationLabel(), button -> this.cycleFaceRasterization())
            .bounds(centerX - 100, this.height / 2 - 46, 200, 20)
            .build()
      );
      this.raycastPlacementButton = this.addRenderableWidget(
         Button.builder(this.raycastPlacementLabel(), button -> this.cycleRaycastPlacement())
            .bounds(centerX - 100, this.height / 2 - 10, 200, 20)
            .build()
      );
      this.placementConflictButton = this.addRenderableWidget(
         Button.builder(this.placementConflictLabel(), button -> this.cyclePlacementConflict())
            .bounds(centerX - 100, this.height / 2 + 26, 200, 20)
            .build()
      );
      this.placementUpdateButton = this.addRenderableWidget(
         Button.builder(this.placementUpdateLabel(), button -> this.cyclePlacementUpdate())
            .bounds(centerX - 100, this.height / 2 + 62, 200, 20)
            .build()
      );
      this.smartWoodFrameButton = this.addRenderableWidget(
         Button.builder(this.smartWoodFrameLabel(), button -> this.toggleSmartWoodFrame())
            .bounds(centerX - 100, this.height / 2 + 98, 200, 20).build()
      );
      this.addRenderableWidget(Button.builder(Component.literal("-"), button -> this.adjustWorldHistory(-10))
         .bounds(centerX - 100, this.height / 2 + 134, 20, 20).build());
      this.worldHistoryButton = this.addRenderableWidget(Button.builder(this.worldHistoryLabel(), button -> {})
         .bounds(centerX - 76, this.height / 2 + 134, 152, 20).build());
      this.addRenderableWidget(Button.builder(Component.literal("+"), button -> this.adjustWorldHistory(10))
         .bounds(centerX + 80, this.height / 2 + 134, 20, 20).build());
      this.addRenderableWidget(Button.builder(Component.literal("-"), button -> this.adjustSessionHistory(-10))
         .bounds(centerX - 100, this.height / 2 + 170, 20, 20).build());
      this.sessionHistoryButton = this.addRenderableWidget(Button.builder(this.sessionHistoryLabel(), button -> {})
         .bounds(centerX - 76, this.height / 2 + 170, 152, 20).build());
      this.addRenderableWidget(Button.builder(Component.literal("+"), button -> this.adjustSessionHistory(10))
         .bounds(centerX + 80, this.height / 2 + 170, 20, 20).build());
      this.addRenderableWidget(
         Button.builder(Component.translatable("gui.done"), button -> this.onClose())
            .bounds(centerX - 100, this.height / 2 + 206, 200, 20)
            .build()
      );
   }

   @Override
   public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
      this.renderBackground(graphics, mouseX, mouseY, partialTick);
      graphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 122, 0xFFFFFFFF);
      this.label(graphics, "fastformer.settings.middle_confirm", -94);
      this.label(graphics, "fastformer.settings.face_rasterization", -58);
      this.label(graphics, "fastformer.settings.raycast_placement", -22);
      this.label(graphics, "fastformer.settings.placement_conflict", 14);
      this.label(graphics, "fastformer.settings.placement_update", 50);
      this.label(graphics, "fastformer.settings.smart_wood_frame", 86);
      this.label(graphics, "fastformer.settings.world_history", 122);
      this.label(graphics, "fastformer.settings.session_history", 158);
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

   private void cycleRaycastPlacement() {
      this.raycastPlacement = this.raycastPlacement == RaycastPlacement.EMBEDDED ? RaycastPlacement.SURFACE : RaycastPlacement.EMBEDDED;
      this.raycastPlacementButton.setMessage(this.raycastPlacementLabel());
      this.send(SettingsActionPayload.Action.CYCLE_RAYCAST_PLACEMENT);
   }

   private Component raycastPlacementLabel() {
      return Component.translatable(this.raycastPlacement.translationKey());
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

   private void toggleSmartWoodFrame() {
      this.smartWoodFrame = !this.smartWoodFrame;
      this.smartWoodFrameButton.setMessage(this.smartWoodFrameLabel());
      this.send(SettingsActionPayload.Action.TOGGLE_SMART_WOOD_FRAME);
   }

   private Component smartWoodFrameLabel() {
      return Component.translatable(this.smartWoodFrame ? "options.on" : "options.off");
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

   private static <E extends Enum<E>> E next(E current, E[] values) {
      return values[(current.ordinal() + 1) % values.length];
   }

   @Override
   public boolean isPauseScreen() {
      return false;
   }
}
