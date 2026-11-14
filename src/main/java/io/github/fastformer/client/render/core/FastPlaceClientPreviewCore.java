package io.github.fastformer.client.render.core;

import io.github.fastformer.client.session.ClientSessionSystem;

import static io.github.fastformer.common.math.MinecraftVectors.normalize;

import io.github.fastformer.core.math.Axis;

import io.github.fastformer.client.render.PreviewStyle;
import io.github.fastformer.fastplace.geometry.GeometryPalette;

import static io.github.fastformer.client.render.core.PreviewRenderResources.*;
import static io.github.fastformer.client.render.type.PreviewRenderTypes.*;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import io.github.fastformer.client.input.FastPlaceClientInput;
import io.github.fastformer.client.input.ModifierReticleMode;
import io.github.fastformer.client.input.PointerDragSnapshotView;
import io.github.fastformer.client.interaction.intent.InteractionContext;
import io.github.fastformer.client.interaction.intent.OperationInteractionIntent;
import io.github.fastformer.client.placement.QuickReplaceMode;
import io.github.fastformer.client.render.OperationFaceHitInterpolator;
import io.github.fastformer.client.render.SmoothReticlePostEffect;
import io.github.fastformer.client.render.WorkspacePointerPrompt;
import io.github.fastformer.client.render.WorkspaceSubmissionHud;
import io.github.fastformer.client.preview.ClientPreviewSystem;
import io.github.fastformer.client.preview.frame.ClientPreviewFrameSystem;
import io.github.fastformer.client.preview.frame.WorldPreviewFrame;
import io.github.fastformer.client.preview.quickshape.QuickShapePreviewInput;
import io.github.fastformer.client.preview.geometry.GeometryPreviewInput;
import io.github.fastformer.client.preview.geometry.GeometryPreviewModel;
import io.github.fastformer.client.render.hud.GeometryTextBlockRenderer;
import io.github.fastformer.client.render.hud.GizmoHudTextFormatter;
import io.github.fastformer.client.render.hud.PreviewFeedbackHud;
import io.github.fastformer.client.feedback.InteractionFeedback;
import io.github.fastformer.client.feedback.AxisFeedback;
import io.github.fastformer.client.feedback.ScrollFeedbackData;
import io.github.fastformer.client.operation.pointer.OperationPointerKind;
import io.github.fastformer.client.operation.pointer.OperationPointerTarget;
import io.github.fastformer.client.render.model.*;
import io.github.fastformer.client.session.preview.ClientPreviewState;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.GeometryAction;
import io.github.fastformer.fastplace.geometry.GeometryHit;
import io.github.fastformer.fastplace.geometry.GeometryMode;
import io.github.fastformer.fastplace.geometry.GeometryNumbers;
import io.github.fastformer.fastplace.geometry.GeometryPreviewPlan;
import io.github.fastformer.fastplace.geometry.GeometryRayVisibility;
import io.github.fastformer.fastplace.geometry.OperationGeometry;
import io.github.fastformer.fastplace.geometry.SelectionPrism;
import io.github.fastformer.fastplace.geometry.cone.ConePrismStage;
import io.github.fastformer.fastplace.geometry.controlpoint.ControlPoint;
import io.github.fastformer.fastplace.geometry.generation.FastPlaceGeometry;
import io.github.fastformer.fastplace.geometry.interaction.GeometryInteractionHit;
import io.github.fastformer.fastplace.geometry.raycast.LongRangeBlockRaycast;
import io.github.fastformer.fastplace.geometry.text.GizmoTextComponent;
import io.github.fastformer.fastplace.geometry.text.GizmoTextContext;
import io.github.fastformer.fastplace.placement.context.PlaceableItems;
import io.github.fastformer.fastplace.placement.context.PlacementContextSnapshot;
import io.github.fastformer.fastplace.quickshape.FaceMode;
import io.github.fastformer.fastplace.quickshape.LineMode;
import io.github.fastformer.fastplace.quickshape.PointMode;
import io.github.fastformer.fastplace.quickshape.PolygonVolumeShape;
import io.github.fastformer.fastplace.quickshape.QuickShapeMode;
import io.github.fastformer.fastplace.quickshape.QuickShapeModeRules;
import io.github.fastformer.fastplace.quickshape.QuickShapeStage;
import io.github.fastformer.fastplace.quickshape.RaycastPlacement;
import io.github.fastformer.fastplace.quickshape.VolumeMode;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import io.github.fastformer.fastplace.selection.OperationStageMode;
import io.github.fastformer.fastplace.session.FastPlaceActivity;
import io.github.fastformer.fastplace.text.TranslatableText;
import io.github.fastformer.protocol.s2c.geometry.GeometryPreviewPayload;
import io.github.fastformer.protocol.s2c.operation.OperationPreviewPayload;
import io.github.fastformer.common.preview.quickshape.BuildingPreviewSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult.Type;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent.Post;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

@EventBusSubscriber(
   modid = "fastformer",
   value = {Dist.CLIENT}
)
public final class FastPlaceClientPreviewCore {
   private static final double PREVIEW_REACH = LongRangeBlockRaycast.MAX_REACH;
   static final float GHOST_FACE_ALPHA_MIN = 0.10F;
   static final float GHOST_FACE_ALPHA_MAX = PreviewStyle.FACE_ALPHA;
   private static final long FACE_NORMAL_INTERPOLATION_NANOS = 100_000_000L;
   static final float SELECTION_HIGHLIGHT_ALPHA = PreviewStyle.FACE_HOVER_ALPHA;
   static final float GHOST_OUTLINE_ALPHA_MIN = 0.70F;
   static final float GHOST_OUTLINE_ALPHA_MAX = PreviewStyle.OUTLINE_ALPHA;

   /** Drops cached meshes that baked the previous theme's colors. */
   public static void onThemeChanged() {
      ClientSessionSystem.instance().inputSession().invalidateGeometryPlan();
      ClientPreviewSystem.instance().clear();
      PreviewRenderResources.clearShells();
   }

   /** Theme ink; every ink pass is preceded by a {@link GeometryPalette#halo()} pass. */
   static float inkRed() { return GeometryPalette.ink().red(); }
   static float inkGreen() { return GeometryPalette.ink().green(); }
   static float inkBlue() { return GeometryPalette.ink().blue(); }
   private static final double PENDING_DASH_SPEED = PreviewStyle.DASH_SPEED;
   private static final double SELECTION_DASH_LENGTH = PreviewStyle.DASH_LENGTH;
   private static final double SELECTION_DASH_PERIOD = PreviewStyle.DASH_PERIOD;
   private static final long GHOST_BREATH_PERIOD_NANOS = 2_400_000_000L;
   static final double SELECTION_FACE_INFLATE = OperationSelectionVolume.RAYCAST_INFLATE;
   private static final ResourceLocation CROSSHAIR_SPRITE = ResourceLocation.withDefaultNamespace("hud/crosshair");
   static final double EPSILON = 1.0E-7;
   private static ClientPreviewState previews() {
      return ClientSessionSystem.instance().previews();
   }
   private static io.github.fastformer.client.operation.pointer.RemoteSelectionPointer remotePointer() {
      return ClientSessionSystem.instance().operations().remotePointer();
   }
   private static InteractionFeedback feedback() {
      return ClientSessionSystem.instance().inputSession().feedback();
   }
   static final OperationFaceHitInterpolator OPERATION_FACE_INTERPOLATOR = new OperationFaceHitInterpolator(FACE_NORMAL_INTERPOLATION_NANOS);
   static final OperationFaceHitInterpolator WORKSPACE_FACE_INTERPOLATOR = new OperationFaceHitInterpolator(FACE_NORMAL_INTERPOLATION_NANOS);
   private static boolean smoothReticleFrame;

   private FastPlaceClientPreviewCore() {
   }

   public static boolean active() {
      return previews().building().active();
   }

   public static boolean enabled() {
      return previews().building().enabled();
   }

   public static boolean middleConfirmEnabled() {
      return previews().building().middleConfirmEnabled();
   }

   public static boolean taskActive() {
      return previews().activity().task();
   }

   public static FastPlaceActivity activity() {
      return previews().activity();
   }

   public static boolean activityCancellable() {
      return previews().activity().cancellable();
   }

   public static boolean operationActive() {
      return ClientSessionSystem.instance().operations().active()
         || ClientSessionSystem.instance().operations().selectionSessionActive();
   }

   public static boolean operationSelectionReady() {
      return ClientSessionSystem.instance().operations().operationSelectionReady();
   }

   public static boolean operationSelectionConfirmed() {
      return ClientSessionSystem.instance().operations().operationSelectionConfirmed();
   }

   public static boolean operationAdjustmentStarted() {
      return ClientSessionSystem.instance().operations().operationAdjustmentStarted();
   }

   public static boolean operationCuboid() {
      return ClientSessionSystem.instance().operations().operationCuboid();
   }

   public static boolean operationPrism() {
      return ClientSessionSystem.instance().operations().operationPrism();
   }

   public static AxisGizmo.Hit operationGizmoHit() {
      OperationInteractionIntent.Gizmo workspaceTarget = operationWorkspaceGizmoHit();
      if (ClientSessionSystem.instance().operations().active()) {
         return workspaceTarget == null ? null : workspaceTarget.hit();
      }
      AxisGizmo gizmo = remotePointer().operationGizmo();
      Minecraft minecraft = Minecraft.getInstance();
      return gizmo == null || minecraft.player == null
         ? null
         : gizmo.hitTest(minecraft.player.getEyePosition(), minecraft.player.getViewVector(1.0F), PREVIEW_REACH);
   }

   public static Optional<OperationInteractionIntent> operationInteractionIntent() {
      return ClientSessionSystem.instance().operations().pointer().frame().target();
   }

   public static OperationInteractionIntent.Gizmo operationWorkspaceGizmoHit() {
      return ClientSessionSystem.instance().operations().pointer().frame().gizmo();
   }

   static OperationGeometry.RayHit operationFaceTarget(OperationSelectionVolume selection) {
      OperationGeometry.RayHit dragged = PointerDragSnapshotView.operationFaceHit(FastPlaceClientInput.currentSession());
      if (dragged == null) {
         return remotePointer().operationFaceHit();
      }
      if (selection == null || selection.prism() != null || dragged.axis() < 0 || dragged.axis() > 2) {
         return dragged;
      }
      AABB bounds = selection.bounds();
      boolean positive = dragged.normal().dot(selection.axis(dragged.axis())) > 0.0;
      double coordinate = switch (dragged.axis()) {
         case 0 -> positive ? bounds.maxX : bounds.minX;
         case 1 -> positive ? bounds.maxY : bounds.minY;
         default -> positive ? bounds.maxZ : bounds.minZ;
      };
      Vec3 point = switch (dragged.axis()) {
         case 0 -> new Vec3(
            coordinate,
            Math.clamp(dragged.point().y, bounds.minY, bounds.maxY),
            Math.clamp(dragged.point().z, bounds.minZ, bounds.maxZ)
         );
         case 1 -> new Vec3(
            Math.clamp(dragged.point().x, bounds.minX, bounds.maxX),
            coordinate,
            Math.clamp(dragged.point().z, bounds.minZ, bounds.maxZ)
         );
         default -> new Vec3(
            Math.clamp(dragged.point().x, bounds.minX, bounds.maxX),
            Math.clamp(dragged.point().y, bounds.minY, bounds.maxY),
            coordinate
         );
      };
      Vec3 normal = selection.axis(dragged.axis()).scale(positive ? 1.0 : -1.0);
      return new OperationGeometry.RayHit(point, normal, dragged.distance(), dragged.axis());
   }

   public static boolean geometryActive() {
      return previews().geometry().active();
   }

   public static boolean buildingRaycastSubmodeAvailable() {
      return ClientSessionSystem.instance().quickShapePointer().raycastSubmodeAvailable(
         previews().geometry().active() || previews().operation().active());
   }

   private static BlockPos pointUnderCrosshair(List<BlockPos> points) {
      Minecraft minecraft = Minecraft.getInstance();
      LocalPlayer player = minecraft.player;
      if (player == null || points.isEmpty()) {
         return null;
      }
      Vec3 eye = player.getEyePosition();
      double maxDistance = PREVIEW_REACH;
      BlockHitResult worldHit = raycastBlocks(player);
      if (worldHit.getType() == Type.BLOCK) {
         maxDistance = Math.min(maxDistance, eye.distanceTo(worldHit.getLocation()) + 1.0E-4);
      }
      return io.github.fastformer.client.interaction.pointer.PointRaycast.nearest(
         points, eye, player.getViewVector(1.0F), maxDistance);
   }

   public static AABB operationBounds() {
      OperationSelectionVolume selection = remotePointer().operationSelection();
      return selection == null ? null : selection.bounds();
   }

   @SubscribeEvent
   public static void onRenderGuiLayerPre(RenderGuiLayerEvent.Pre event) {
      if (!VanillaGuiLayers.CROSSHAIR.equals(event.getName())) {
         return;
      }

      smoothReticleFrame = false;
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.screen != null || minecraft.player == null) {
         SmoothReticlePostEffect.updateTarget(ModifierReticleMode.NONE);
         return;
      }
      boolean reticleNeeded = SmoothReticlePostEffect.updateTarget(FastPlaceClientInput.modifierReticleMode());
      if (!reticleNeeded) {
         return;
      }

      // NeoForge exposes the vanilla crosshair as a cancellable GUI layer. This
      // keeps the post effect from inverting a second, pixelated crosshair.
      smoothReticleFrame = SmoothReticlePostEffect.prepare(minecraft);
      if (smoothReticleFrame) {
         event.setCanceled(true);
      }
   }

   @SubscribeEvent
   public static void onRenderGui(Post event) {
      Minecraft minecraft = Minecraft.getInstance();
      GuiGraphics graphics = event.getGuiGraphics();
      if (minecraft.screen instanceof io.github.fastformer.client.ui.FastFormerSettingsScreen
         || minecraft.screen instanceof io.github.fastformer.client.ui.GeometryRadialScreen) {
         smoothReticleFrame = false;
         return;
      }
      if (FastPlaceClientInput.currentSession().smartFill().active() && minecraft.player != null) {
         var fill = FastPlaceClientInput.currentSession().smartFill();
         graphics.drawString(minecraft.font, Component.translatable(fill.statusKey(), fill.blocks().size()),
            8, 8, GeometryPalette.text().argb(), true);
         graphics.drawString(minecraft.font, Component.translatable(fill.ready()
            ? "fastformer.fill.controls" : "fastformer.fill.cancel"), 8, 20, GeometryPalette.muted().argb(), true);
         restoreVanillaCrosshairIfNeeded(graphics);
         smoothReticleFrame = false;
         return;
      }
      if (QuickReplaceMode.active() && minecraft.player != null) {
         graphics.drawString(minecraft.font, Component.translatable("fastformer.quick_replace.active"),
            8, 8, GeometryPalette.text().argb(), true);
         graphics.drawString(minecraft.font, Component.translatable(QuickReplaceMode.canReplace(minecraft)
            ? "fastformer.quick_replace.controls" : "fastformer.quick_replace.hold_block"),
            8, 20, GeometryPalette.muted().argb(), true);
         if (previews().activity().task()) graphics.drawString(minecraft.font,
            Component.translatable(previews().activity().translationKey()), 8, 32, GeometryPalette.valid().argb(), true);
         restoreVanillaCrosshairIfNeeded(graphics);
         smoothReticleFrame = false;
         return;
      }
      BuildingPreviewSnapshot snapshot = previews().building();
      boolean persistentFreeScroll = feedback().refreshFreeScrollSession(previews().building(), System.nanoTime());
      boolean reconnectRestorePending = ClientSessionSystem.instance().operations().reconnectRestorePending()
         || previews().reconnectRestorePending();
      // A local submission locks the workspace before the server reports its task.
      // The HUD must stay alive in that window, or the player sees no reason for
      // the refused clicks. See WorkspaceSubmissionHud.
      boolean workspaceSubmissionPending = ClientSessionSystem.instance().operations().workspaceSubmissionPending();
      WorkspaceSubmissionHud.Plan submissionPlan = WorkspaceSubmissionHud.plan(
         workspaceSubmissionPending,
         previews().activity().task(),
         QuickReplaceMode.active(),
         reconnectRestorePending
      );
      if (!WorkspaceSubmissionHud.hudActive(
         reconnectRestorePending,
         snapshot.enabled(),
         persistentFreeScroll,
         previews().geometry().active(),
         operationActive(),
         previews().activity().task(),
         QuickReplaceMode.active(),
         workspaceSubmissionPending
      )) {
         restoreVanillaCrosshairIfNeeded(graphics);
         smoothReticleFrame = false;
         return;
      }

      Vec3 view = minecraft.player == null ? new Vec3(0.0, 0.0, 1.0) : minecraft.player.getViewVector(1.0F);
      float hudOpacity = previews().activity().task() || workspaceSubmissionPending || reconnectRestorePending
         ? 1.0F : InteractionContext.previewVisibility(minecraft, event.getPartialTick().getGameTimeDeltaTicks());
      if (hudOpacity <= 0.0F) {
         restoreVanillaCrosshairIfNeeded(graphics);
         smoothReticleFrame = false;
         return;
      }
      graphics.setColor(1.0F, 1.0F, 1.0F, hudOpacity);
      try {

      Component fillModeName = Component.translatable(
         (previews().geometry().active() ? previews().geometry().fillMode() : snapshot.fillMode()).translationKey()
      );
      // The lock blocks the block key, so a pending frame names the fill mode
      // only. The status text keeps the fill information and promises no key.
      String fillKey = WorkspaceSubmissionHud.fillModeKey(workspaceSubmissionPending);
      MutableComponent fill = (WorkspaceSubmissionHud.FILL_MODE_STATUS_KEY.equals(fillKey)
            ? Component.translatable(fillKey, fillModeName)
            : Component.translatable(fillKey, fillModeName, keyName(minecraft.options.keySwapOffhand)))
         .withStyle(GeometryPalette.text().style());
      if (!ClientSessionSystem.instance().modeView().idleActionsAllowed()) graphics.drawString(minecraft.font, fill, 8, 8, -1, true);

      MutableComponent embeddedHint = buildingRaycastHint();
      if (embeddedHint != null && minecraft.screen == null
         && !previews().activity().task() && !submissionPlan.suppressActionPrompts()) {
         int x = Math.max(8, graphics.guiWidth() - minecraft.font.width(embeddedHint) - 8);
         graphics.drawString(minecraft.font, embeddedHint, x, 8, GeometryPalette.muted().argb(), true);
      }
      if (previews().activity().task()) {
         MutableComponent task = Component.translatable(previews().activity().translationKey()).withStyle(GeometryPalette.valid().style());
         // The cancel key reports the pending state instead of cancelling, so an
         // exit hint on this line would be false while the submission waits.
         if (WorkspaceSubmissionHud.showsCancelHint(previews().activity(), workspaceSubmissionPending)) {
            String hintKey = "fastformer.activity.cancel_hint";
            task.append(Component.literal(" ").append(Component.translatable(hintKey)).withStyle(GeometryPalette.muted().style()));
         }
         graphics.drawString(minecraft.font, task, 8, 20, -1, true);
      } else if (submissionPlan.showsWaitingLine()) {
         // The locked workspace refuses every build and selection action. Explain
         // the wait instead of leaving the player without a reason.
         graphics.drawString(minecraft.font,
            Component.translatable(submissionPlan.waitingKey()).withStyle(GeometryPalette.accent().style()),
            8, submissionPlan.waitingLineY(), -1, true);
      }
      if (reconnectRestorePending) {
         graphics.drawString(minecraft.font,
            Component.translatable("fastformer.message.reconnect_restore_prompt"), 8, 32, GeometryPalette.accent().argb(), true);
      }

      var bottomLayout = io.github.fastformer.client.render.hud.BottomHudLayout.forFrame(graphics, minecraft);
      GeometryPreviewPlan geometryPlan = null;
      if (!previews().activity().task()) {
         geometryPlan = renderSessionHud(graphics, minecraft, view, persistentFreeScroll,
            submissionPlan.suppressActionPrompts(), bottomLayout);
      } else if (persistentFreeScroll) {
         // Wheel updates can briefly publish a task snapshot. Keep the latched
         // free-scroll coordinates visible while the session HUD is suppressed.
         renderScrollFeedbackBottom(graphics, minecraft, persistentFreeScroll, bottomLayout);
      }
      renderCrosshairHud(graphics, minecraft, geometryPlan);
      SmartSelectionRenderer.hud(graphics, minecraft, bottomLayout);
      graphics.flush();
      RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
      boolean cancelledVanillaCrosshair = smoothReticleFrame;
      boolean renderedReticle = cancelledVanillaCrosshair
         && SmoothReticlePostEffect.process(minecraft, event.getPartialTick().getGameTimeDeltaTicks());
      if (cancelledVanillaCrosshair && !renderedReticle) {
         // The shader failed after the layer event had already cancelled the
         // vanilla layer. Restore the actual vanilla sprite, not a GUI variant.
         restoreVanillaCrosshairIfNeeded(graphics);
         graphics.flush();
      }
      smoothReticleFrame = false;
      } finally {
         restoreVanillaCrosshairIfNeeded(graphics);
         graphics.flush();
         RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
         smoothReticleFrame = false;
      }
   }

   public static boolean geometryAllows(GeometryAction action) {
      return ClientSessionSystem.instance().geometryPointer().allows(action);
   }

   private static GeometryPreviewPlan renderSessionHud(
      GuiGraphics graphics, Minecraft minecraft, Vec3 view, boolean persistentFreeScroll,
      boolean suppressActionPrompts, io.github.fastformer.client.render.hud.BottomHudLayout bottomLayout
   ) {
      if (previews().geometry().active()) {
         GeometryPreviewPlan geometryPlan = geometryPreviewPlan(view);
         GeometryTextBlockRenderer.render(graphics, minecraft, geometryPlan, bottomLayout);
         renderScrollFeedbackBottom(graphics, minecraft, persistentFreeScroll, bottomLayout);
         return geometryPlan;
      }

      // A locked workspace refuses the confirm key and the pointer actions, so the
      // next-step hint must stop. The mode line stays: it reports which stage and
      // mode the workspace is in, which the player still owns during the wait.
      MutableComponent primary;
      MutableComponent secondary = null;
      if (operationActive()) {
         primary = operationBottomStatus();
         if (!suppressActionPrompts) secondary = Component.translatable(ClientSessionSystem.instance().operations().selectionEdits().smartSampling()
            ? "fastformer.hud.smart_sampling" : io.github.fastformer.client.interaction.SmartSelectionEditView.active(minecraft)
            ? "fastformer.hud.smart_isolation_controls" : ClientSessionSystem.instance().operations().selectionEdits().smartTool()
            ? "fastformer.hud.smart_controls" : "fastformer.hud.selection_controls")
            .withStyle(GeometryPalette.muted().style());
      } else {
         primary = buildingBottomStatus();
         secondary = suppressActionPrompts ? null : buildingBottomHint();
      }
      bottomLayout.render(graphics, minecraft, secondary);
      bottomLayout.render(graphics, minecraft, primary);
      renderScrollFeedbackBottom(graphics, minecraft, persistentFreeScroll, bottomLayout);
      return null;
   }

   private static int drawScaledLabel(
      GuiGraphics graphics, Minecraft minecraft, Component label, int x, int y, int color
   ) {
      float scale = 0.75F;
      graphics.pose().pushPose();
      graphics.pose().translate(x, y + 1, 0.0F);
      graphics.pose().scale(scale, scale, 1.0F);
      graphics.drawString(minecraft.font, label, 0, 0, color, true);
      graphics.pose().popPose();
      return x + scaledLabelWidth(minecraft, label);
   }

   private static int scaledLabelWidth(Minecraft minecraft, Component label) {
      return (int)Math.ceil(minecraft.font.width(label) * 0.75F);
   }

   private static double axisComponent(Vec3 value, Axis axis) {
      return switch (axis) {
         case X -> value.x;
         case Y -> value.y;
         case Z -> value.z;
      };
   }

   private static double geometryGizmoValue(
      GeometryPreviewPlan geometryPlan, Axis axis, AxisGizmo.Operation operation
   ) {
      return ClientSessionSystem.instance().geometryPointer().gizmoValue(geometryPlan, axis, operation);
   }

   private static MutableComponent buildingBottomStatus() {
      if (!previews().building().active()) {
         Minecraft minecraft = Minecraft.getInstance();
         if (minecraft.player == null || minecraft.screen != null || QuickReplaceMode.active()
            || !previews().building().enabled()) return null;
         return idleBottomStatus(minecraft.player.getMainHandItem().isEmpty(),
            PlaceableItems.isPlaceable(minecraft.player.getMainHandItem()));
      }

      return buildingStageStatus(previews().building(), effectiveStage(previews().building()));
   }

   private static MutableComponent buildingStageStatus(BuildingPreviewSnapshot snapshot, QuickShapeStage stage) {
      MutableComponent status = Component.translatable(stage.translationKey()).withStyle(GeometryPalette.text().style());
      status.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY));
      if (stage == QuickShapeStage.VOLUME && snapshot.polygonClosed()) {
         PolygonVolumeShape selected = snapshot.polygonVolumeShape();
         PolygonVolumeShape[] modes = PolygonVolumeShape.values();
         for (int index = 0; index < modes.length; index++) {
            if (index > 0) {
                status.append(Component.literal(" / ").withStyle(ChatFormatting.DARK_GRAY));
            }
            appendBuildingMode(status, modes[index], modes[index] == selected);
         }
      } else {
         List<? extends QuickShapeMode> modes = QuickShapeModeRules.allowedModes(
            stage,
            snapshot.lineMode()
         );
         QuickShapeMode selected = selectedMode(snapshot, stage);
         for (int index = 0; index < modes.size(); index++) {
            if (index > 0) {
                status.append(Component.literal(" / ").withStyle(ChatFormatting.DARK_GRAY));
            }
            QuickShapeMode mode = modes.get(index);
            appendBuildingMode(status, mode, mode == selected);
         }
      }
      return status;
   }

   static MutableComponent idleBottomStatus(boolean emptyHand, boolean placeable) {
      if (emptyHand || !placeable) {
         return appendSelectionModes(Component.translatable("fastformer.hud.selection_label")
            .withStyle(GeometryPalette.text().style()),
            io.github.fastformer.client.operation.selection.SelectionToolPreference.get());
      }
      MutableComponent status = Component.translatable("fastformer.hud.quick_shape_label").withStyle(GeometryPalette.text().style());
      status.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY));
      for (LineMode mode : LineMode.values()) {
         if (mode != LineMode.values()[0]) status.append(Component.literal(" / ").withStyle(ChatFormatting.DARK_GRAY));
         appendBuildingMode(status, mode, mode == previews().building().lineMode());
      }
      return status;
   }

   private static void appendBuildingMode(MutableComponent status, TranslatableText mode, boolean selected) {
      status.append(Component.translatable(mode.translationKey()).withStyle(selected ? GeometryPalette.accent().style() : GeometryPalette.muted().style()));
   }

   private static MutableComponent buildingRaycastHint() {
      if (!previews().building().active() || previews().geometry().active()
         || previews().operation().active()
         || !buildingRaycastSubmodeAvailable()
         || !PointerDragSnapshotView.modifierHeld(FastPlaceClientInput.currentSession())
         || previews().building().raycastPlacement() == RaycastPlacement.EMBEDDED) {
         return null;
      }
      return Component.translatable("fastformer.hud.raycast_embedded_hint");
   }

   private static boolean buildingRaycastSubmode() {
      if (previews().geometry().active() || previews().operation().active()) {
         return false;
      }
      return io.github.fastformer.client.quickshape.pointer.QuickShapeModes.raycastSubmode(previews().building());
   }

   private static MutableComponent buildingBottomHint() {
      if (!previews().building().active()) {
         Minecraft minecraft = Minecraft.getInstance();
         if (minecraft.player == null || minecraft.screen != null || QuickReplaceMode.active()
            || !previews().building().enabled()) return null;
         if (idlePreviewHit(minecraft) != null) {
            return (PlaceableItems.isPlaceable(minecraft.player.getMainHandItem())
               ? Component.translatable("fastformer.hud.idle_entry_far_placement", keyName(minecraft.options.keyUse), keyName(minecraft.options.keyPickItem))
               : Component.translatable("fastformer.hud.idle_entry_far", keyName(minecraft.options.keyUse)))
               .withStyle(GeometryPalette.muted().style());
         }
         return Component.translatable("fastformer.hud.idle_entry", keyName(minecraft.options.keyUse))
            .withStyle(GeometryPalette.muted().style());
      }
      QuickShapeStage stage = effectiveStage(previews().building());
      if (stage == QuickShapeStage.FACE && previews().building().faceMode() == FaceMode.POLYGON && !previews().building().polygonClosed()) {
         return Component.translatable("fastformer.message.polygon_close_hint").withStyle(GeometryPalette.muted().style());
      }
      return Component.translatable(
         stage == QuickShapeStage.VOLUME
            ? previews().building().polygonClosed()
               ? "fastformer.message.building_hint_height"
               : "fastformer.message.building_hint_points"
            : "fastformer.message.building_hint_points"
      ).withStyle(GeometryPalette.muted().style());
   }

   static MutableComponent operationBottomStatus() {
      boolean localSelection = ClientSessionSystem.instance().operations().active() || ClientSessionSystem.instance().operations().selectionEdits().selectionDraftActive();
      boolean confirmed = !localSelection && operationSelectionReady();
      MutableComponent status = Component.translatable(
         confirmed || ClientSessionSystem.instance().operations().active() && !ClientSessionSystem.instance().operations().selectionEdits().selectionDraftActive()
            ? "fastformer.hud.operation_label" : "fastformer.hud.selection_label"
      ).withStyle(GeometryPalette.text().style());
      if (confirmed) {
         status.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY));
         OperationStageMode[] stageModes = OperationStageMode.values();
         for (int index = 0; index < stageModes.length; index++) {
            if (index > 0) {
               status.append(Component.literal(" / ").withStyle(ChatFormatting.DARK_GRAY));
            }
            OperationStageMode mode = stageModes[index];
            status.append(Component.translatable(mode.translationKey()).withStyle(
               mode == previews().operation().operationStageMode() ? GeometryPalette.accent().style() : GeometryPalette.muted().style()
            ));
         }
         return status;
      }
      return appendSelectionModes(status, ClientSessionSystem.instance().operations().selectionEdits().draftSelectionMode());
   }

   private static MutableComponent appendSelectionModes(MutableComponent status, OperationSelectionMode selected) {
      status.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY));
      OperationSelectionMode[] modes = {OperationSelectionMode.CUBOID, OperationSelectionMode.SMART};
      for (int index = 0; index < modes.length; index++) {
         if (index > 0) {
            status.append(Component.literal(" / ").withStyle(ChatFormatting.DARK_GRAY));
         }
         OperationSelectionMode mode = modes[index];
         status.append(Component.translatable(mode.translationKey()).withStyle(
            mode == selected ? GeometryPalette.accent().style() : GeometryPalette.muted().style()
         ));
      }
      return status;
   }

   private static void renderCrosshairHud(
      GuiGraphics graphics, Minecraft minecraft, GeometryPreviewPlan geometryPlan
   ) {
      if (minecraft.screen != null || minecraft.player == null) {
         return;
      }
      int x = graphics.guiWidth() / 2 + 10;
      int y = graphics.guiHeight() / 2 + 6;
      boolean workspaceLocked = ClientSessionSystem.instance().operations().active() && ClientSessionSystem.instance().operations().workspace().locked();
      GeometryInteractionHit interactionHit = ClientSessionSystem.instance().geometryPointer().interactionHit();
      // This text offers a pointer action. The locked workspace refuses that
      // action, so draw the text only while the workspace accepts new actions.
      if (interactionHit != null && ClientSessionSystem.instance().geometryPointer().gizmoHit() == null
         && WorkspacePointerPrompt.acceptsNewAction(workspaceLocked)) {
         graphics.drawString(minecraft.font, interactionHit.hoverText(), x, y, GeometryPalette.text().argb(), true);
         y += 10;
      }
      OperationInteractionIntent operationTarget = operationInteractionIntent().orElse(null);
      if (operationGizmoHit() == null
         && remotePointer().operationPointUnderCrosshairIndex() < 0
         && operationTarget instanceof OperationInteractionIntent.Face face) {
         for (Component line : WorkspacePointerPrompt.crosshairActionLines(face, workspaceLocked)) {
            graphics.drawString(minecraft.font, line, x, y, GeometryPalette.text().argb(), true);
            y += 10;
         }
         return;
      }
      GizmoHudInput hudInput = gizmoHudInput(geometryPlan);
      Axis dragAxis = hudInput.dragAxis();
      Axis axis = dragAxis;
      AxisGizmo.Operation operation = dragAxis == null ? null : hudInput.dragOperation();
      AxisGizmo displayedGizmo = hudInput.gizmo();
      boolean retainedGizmo = false;
      boolean hoveredGizmo = false;
      int gizmoAlpha = 255;
      if (axis == null) {
         AxisGizmo gizmo = displayedGizmo;
         AxisGizmo.Hit hit = gizmo == null || !hudInput.allowNearBlock() && InteractionContext.vanillaOwnsPointer(minecraft)
            ? null
            : gizmo.hitTest(
               minecraft.player.getEyePosition(),
               minecraft.player.getViewVector(1.0F),
               hudInput.operationSelection() ? PREVIEW_REACH : visiblePreviewReach(minecraft.player)
            );
         if (hit != null) {
            axis = hit.handle().axis();
            operation = hit.handle().operation();
            hoveredGizmo = true;
            long now = System.nanoTime();
            double speed = feedback().gizmoDwellMultiplier(axis.name() + ":" + operation.name(), now);
            gizmoAlpha = feedback().gizmoAlpha(now, axis.name() + ":" + operation.name(), speed);
            retainedGizmo = gizmoAlpha > 0 && axis == feedback().lastGizmoAxis();
         } else {
            long now = System.nanoTime();
            gizmoAlpha = feedback().gizmoAlpha(now, null, 1.0);
            if (gizmoAlpha > 0) {
               axis = feedback().lastGizmoAxis();
               operation = feedback().lastGizmoOperation();
               retainedGizmo = axis != null;
            }
         }
      }
      if (WorkspacePointerPrompt.acceptsNewAction(workspaceLocked)
         && axis != null && operation != null) {
         int axisColor = gizmoAxisHudColor(axis);
         if (retainedGizmo && !hoveredGizmo) {
            axisColor = withAlpha(axisColor, gizmoAlpha);
         }
         GizmoTextComponent textComponent = displayedGizmo == null
            ? GizmoTextComponent.none()
            : displayedGizmo.textComponent();
         boolean valueAvailable = dragAxis != null || retainedGizmo;
         boolean useTemplate = !textComponent.empty() && (hoveredGizmo || dragAxis != null);
         if (useTemplate) {
            double baseValue = dragAxis != null
               ? hudInput.dragBaseValue()
               : feedback().lastGizmoBaseValue();
            double currentValue = gizmoHudValue(hudInput, geometryPlan, axis, operation);
            int steps = dragAxis != null ? hudInput.dragSteps() : feedback().lastGizmoSteps();
            GizmoTextContext context = hudInput.operationSelection() && operationSelectionReady()
               ? GizmoHudTextFormatter.operationTransformTextContext(axis, operation, baseValue, currentValue, steps)
               : GizmoHudTextFormatter.textContext(axis, operation, baseValue, currentValue, steps);
            String text = textComponent.render(context, valueAvailable);
            if (!text.isBlank()) {
               graphics.drawString(minecraft.font, text, x, y, axisColor, true);
            }
         } else {
            int labelEnd = operation == AxisGizmo.Operation.MOVE && valueAvailable
               ? drawGizmoAxisLabel(graphics, minecraft, axis, x, y, axisColor)
                  : drawGizmoActionLabel(graphics, minecraft, axis, operation, x, y, axisColor);
            if (valueAvailable) {
               double baseValue = dragAxis != null
                  ? hudInput.dragBaseValue()
                  : feedback().lastGizmoBaseValue();
               double currentValue = gizmoHudValue(hudInput, geometryPlan, axis, operation);
               String value = GizmoHudTextFormatter.formatValue(
                  dragAxis != null ? hudInput.dragOperation() : feedback().lastGizmoOperation(),
                  baseValue,
                  currentValue,
                  dragAxis != null ? hudInput.dragSteps() : feedback().lastGizmoSteps()
               );
               int valueColor = retainedGizmo ? withAlpha(axisColor, gizmoAlpha) : axisColor;
               graphics.drawString(minecraft.font, value, labelEnd + 3, y, valueColor, true);
            }
         }
      }

   }

   private static GizmoHudInput gizmoHudInput(GeometryPreviewPlan geometryPlan) {
      if (ClientSessionSystem.instance().operations().active()) {
         OperationInteractionIntent.Gizmo target = operationWorkspaceGizmoHit();
         return new GizmoHudInput(
            target == null ? null : target.gizmo(),
            PointerDragSnapshotView.operationAxis(FastPlaceClientInput.currentSession()),
            PointerDragSnapshotView.operationOperation(FastPlaceClientInput.currentSession()),
            PointerDragSnapshotView.operationBaseValue(FastPlaceClientInput.currentSession()),
            PointerDragSnapshotView.operationSteps(FastPlaceClientInput.currentSession()),
            true,
            false
         );
      }
      if (previews().operation().active()) {
         return new GizmoHudInput(
            remotePointer().operationGizmo(),
            PointerDragSnapshotView.operationAxis(FastPlaceClientInput.currentSession()),
            PointerDragSnapshotView.operationOperation(FastPlaceClientInput.currentSession()),
            PointerDragSnapshotView.operationBaseValue(FastPlaceClientInput.currentSession()),
            PointerDragSnapshotView.operationSteps(FastPlaceClientInput.currentSession()),
            true,
            true
         );
      }
      return new GizmoHudInput(
         geometryPlan == null ? null : geometryPlan.gizmo(),
         PointerDragSnapshotView.axis(FastPlaceClientInput.currentSession()),
         PointerDragSnapshotView.operation(FastPlaceClientInput.currentSession()),
         PointerDragSnapshotView.baseValue(FastPlaceClientInput.currentSession()),
         PointerDragSnapshotView.steps(FastPlaceClientInput.currentSession()),
         false,
         false
      );
   }

   private static double gizmoHudValue(
      GizmoHudInput input,
      GeometryPreviewPlan geometryPlan,
      Axis axis,
      AxisGizmo.Operation operation
   ) {
      if (!input.operationSelection()) {
         return geometryGizmoValue(geometryPlan, axis, operation);
      }
      if (operationSelectionReady()) {
         return remotePointer().operationGizmoValue(axis, operation);
      }
      double value = input.gizmo() == null
         ? input.dragBaseValue()
         : axisComponent(input.gizmo().center(), axis);
      if (axis == input.dragAxis()
         && operation == AxisGizmo.Operation.MOVE
         && input.dragSteps() != 0
         && Math.abs(value - input.dragBaseValue()) < EPSILON) {
         return input.dragBaseValue() + input.dragSteps();
      }
      return value;
   }

   private static int gizmoAxisHudColor(Axis axis) {
      return 0xFF000000 | AxisGizmo.axisColor(axis);
   }

   private static void restoreVanillaCrosshairIfNeeded(GuiGraphics graphics) {
      if (smoothReticleFrame) {
         smoothReticleFrame = false;
         renderVanillaCrosshair(graphics);
      }
   }

   private static void renderVanillaCrosshair(GuiGraphics graphics) {
      RenderSystem.enableBlend();
      RenderSystem.blendFuncSeparate(
         GlStateManager.SourceFactor.ONE_MINUS_DST_COLOR,
         GlStateManager.DestFactor.ONE_MINUS_SRC_COLOR,
         GlStateManager.SourceFactor.ONE,
         GlStateManager.DestFactor.ZERO
      );
      int x = (graphics.guiWidth() - 15) / 2;
      int y = (graphics.guiHeight() - 15) / 2;
      graphics.blitSprite(CROSSHAIR_SPRITE, x, y, 15, 15);
      RenderSystem.defaultBlendFunc();
      RenderSystem.disableBlend();
   }

   private static int drawGizmoActionLabel(
      GuiGraphics graphics,
      Minecraft minecraft,
      Axis axis,
      AxisGizmo.Operation operation,
      int x,
      int y,
      int color
   ) {
      int axisEnd = drawGizmoAxisLabel(graphics, minecraft, axis, x, y, color);
      return drawScaledLabel(
         graphics,
         minecraft,
         Component.translatable(GizmoHudTextFormatter.transformLabelKey(operation)),
         axisEnd + 3,
         y,
         color
      );
   }

   private static int drawGizmoAxisLabel(
      GuiGraphics graphics,
      Minecraft minecraft,
      Axis axis,
      int x,
      int y,
      int color
   ) {
      graphics.drawString(minecraft.font, axis.name(), x, y, color, true);
      return x + minecraft.font.width(axis.name());
   }

   private static void renderScrollFeedbackBottom(
      GuiGraphics graphics, Minecraft minecraft, boolean persistentFreeScroll,
      io.github.fastformer.client.render.hud.BottomHudLayout bottomLayout
   ) {
      Object target = minecraft.hitResult instanceof BlockHitResult hit ? hit.getBlockPos().immutable() : null;
      int alpha = feedback().scrollAlpha(persistentFreeScroll, target, System.nanoTime());
      PreviewFeedbackHud.renderScrollFeedback(graphics, minecraft, scrollFeedbackData(), alpha, bottomLayout);
   }

   private static ScrollFeedbackData scrollFeedbackData() {
      if (previews().operation().active() && operationSelectionReady()) {
      BlockPos value = operationSelectionConfirmed() && PointerDragSnapshotView.modifierHeld(FastPlaceClientInput.currentSession())
            ? previews().operation().stackVector()
            : previews().operation().translation();
         return feedback().coordinates(value);
      }
      if (previews().geometry().active() && previews().geometry().mode() == GeometryMode.CONE_PRISM) {
         Vec3 offset = previews().geometry().coneTopOffset();
         return new ScrollFeedbackData(
            List.of(
               new AxisFeedback("X", GeometryNumbers.fixed(offset.x, 2), feedback().axisColor(Axis.X)),
               new AxisFeedback("Z", GeometryNumbers.fixed(offset.z, 2), feedback().axisColor(Axis.Z))
            ),
            ""
         );
      }
      if (previews().building().active()) {
         BuildingPreviewSnapshot building = previews().building();
         // Keep the free-scroll payload aligned with persistent visibility.
         // effectiveStage() can briefly describe the previous server snapshot
         // while the active line session already owns the scroll offset.
         if (feedback().freeScrollSession() && building.points().size() <= 1) {
            BlockPos offset = building.points().size() == 1
               ? building.freeScrollOffset() : feedback().freeScrollOffset();
            ScrollFeedbackData data = feedback().coordinates(offset);
            feedback().rememberFreeScrollData(data);
            return data;
         }
         QuickShapeStage stage = effectiveStage(building);
         if (stage == QuickShapeStage.FACE && previews().building().faceMode() == FaceMode.PARALLELOGRAM_BASE_PLANE) {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
               return new ScrollFeedbackData(
                  List.of(),
                  GeometryNumbers.fixed(
                     FastPlaceGeometry.faceBaseOffsetValue(previews().building().points(), previews().building().faceBaseOffset(), player.getViewVector(1.0F)),
                     0
                  )
               );
            }
         }
         if (stage == QuickShapeStage.VOLUME && FastPlaceGeometry.usesVolumeOffset(ClientPreviewSystem.instance().quickShape().effectiveModes(previews().building()))) {
            return previews().building().volumeMode() == VolumeMode.FREE
               ? feedback().coordinates(previews().building().volumeBaseOffset())
               : new ScrollFeedbackData(List.of(), formatScalar(previews().building().volumeBaseOffset()));
         }
      }
      // A wheel request can produce one or more inactive snapshots while the
      // server applies the new offset. Keep rendering the latched value for
      // that gap; otherwise the HUD disappears even though the session is
      // still valid and the feedback component keeps it visible.
      if (feedback().freeScrollSession()) {
         ScrollFeedbackData data = feedback().freeScrollData();
         return data != null ? data : feedback().coordinates(feedback().freeScrollOffset());
      }
      return null;
   }

   private static int withAlpha(int color, int alpha) {
      return (Math.clamp(alpha, 0, 255) << 24) | (color & 0x00FFFFFF);
   }



   private static void renderOperationSelection(
      RenderLevelStageEvent event, Minecraft minecraft, LocalPlayer player, OperationPreviewPayload snapshot
   ) {
      OperationPreviewRenderer.renderOperationSelection(event, minecraft, player, snapshot);
   }

   private static void renderClientOperationWorkspace(
      RenderLevelStageEvent event, Minecraft minecraft, OperationInteractionIntent pointerIntent
   ) {
      OperationPreviewRenderer.renderClientOperationWorkspace(event, minecraft, pointerIntent);
   }

   private static void renderSelectionCreationCandidate(
      RenderLevelStageEvent event, Minecraft minecraft, OperationInteractionIntent pointerIntent
   ) {
      OperationPreviewRenderer.renderSelectionCreationCandidate(event, minecraft, pointerIntent);
   }

   @SubscribeEvent
   public static void onRenderBlockHighlight(net.neoforged.neoforge.client.event.RenderHighlightEvent.Block event) {
      if (hasVisibleBlueOutline(Minecraft.getInstance())) event.setCanceled(true);
   }

   private static boolean hasVisibleBlueOutline(Minecraft minecraft) {
      var player = minecraft.player;
      if (player == null || minecraft.level == null || QuickReplaceMode.active()) return false;
      if (ClientSessionSystem.instance().modeView().idleActionsAllowed()) {
         var hit = idlePreviewHit(minecraft);
         var preview = hit == null ? null : InitialBlockPreview.resolve(player, hit, FastPlaceClientInput.modifierHeld());
         return preview != null && !preview.mesh().edges().isEmpty();
      }
      var snapshot = previews().building();
      var pointer = operationInteractionIntent().orElse(null);
      var owner = PreviewRenderOwner.select(snapshot.active(), previews().operation().active(),
         ClientSessionSystem.instance().operations().active() || ClientSessionSystem.instance().operations().selectionEdits().selectionDraftActive(), previews().geometry().active());
      if (owner == PreviewRenderOwner.OPERATION || owner == PreviewRenderOwner.GEOMETRY
         || pointer instanceof OperationInteractionIntent.CreateSelection) return true;
      if (InteractionContext.previewVisibility(minecraft) <= 0.01F) return false;
      if (snapshot.active()) return true;
      if (!snapshot.enabled() || !(player.getMainHandItem().isEmpty()
         || PlaceableItems.isPlaceable(player.getMainHandItem()))) return false;
      var preview = InitialBlockPreview.resolve(player, raycastBlocks(player),
         PointerDragSnapshotView.modifierHeld(FastPlaceClientInput.currentSession()));
      return preview != null && !preview.mesh().edges().isEmpty();
   }

   private static BlockHitResult idlePreviewHit(Minecraft minecraft) {
      var player = minecraft.player;
      if (player == null || minecraft.level == null || !player.isCreative() || !enabled()
         || minecraft.screen != null || minecraft.getCameraEntity() != player
         || !FastPlaceClientInput.modifierHeld() && minecraft.hitResult != null
            && minecraft.hitResult.getType() == Type.ENTITY) return null;
      var hit = ClientSessionSystem.instance().inputSession().pointer().selection(player, FastPlaceClientInput.modifierHeld()).hit();
      boolean embedded = FastPlaceClientInput.modifierHeld() && PlaceableItems.isPlaceable(player.getMainHandItem());
      return embedded || LongRangeBlockRaycast.beyondInteractionRange(player, hit) ? hit : null;
   }

   @SubscribeEvent
   public static void onRenderLevelStage(RenderLevelStageEvent event) {
      long revision = io.github.fastformer.client.render.theme.VisualThemes.revision();
      if (renderedThemeRevision != revision) {
         onThemeChanged();
         renderedThemeRevision = revision;
      }
      if (event.getStage() == Stage.AFTER_LEVEL) {
         SmartSelectionIsolationRenderer.render(event, Minecraft.getInstance());
         return;
      }
      BuildingPreviewSnapshot snapshot = previews().building();
      if (event.getStage() == Stage.AFTER_PARTICLES) {
          Minecraft minecraft = Minecraft.getInstance();
          LocalPlayer player = minecraft.player;
          io.github.fastformer.client.render.HistoryConflictRenderer.render(event, minecraft);
          if (FastPlaceClientInput.currentSession().smartFill().active()) {
             SmartFillPreviewRenderer.render(event, minecraft);
             return;
          }
          if (QuickReplaceMode.active()) {
             if (QuickReplaceMode.canReplace(minecraft) && minecraft.level != null && player != null) {
                QuickReplacePreviewRenderer.render(event, minecraft, player);
             }
             return;
          }
          if (ClientSessionSystem.instance().modeView().idleActionsAllowed()) {
             var hit = idlePreviewHit(minecraft);
             if (hit != null) InitialBlockPreviewRenderer.render(event, minecraft, player, hit,
                FastPlaceClientInput.modifierHeld(), 1.0F);
             return;
          }
          OperationInteractionIntent pointerIntent = player == null ? null : operationInteractionIntent().orElse(null);
          boolean selectionCandidate = pointerIntent instanceof OperationInteractionIntent.CreateSelection;
          if (!(snapshot.active() || previews().operation().active()
             || ClientSessionSystem.instance().operations().active() || ClientSessionSystem.instance().operations().selectionEdits().selectionDraftActive() || previews().geometry().active() || QuickReplaceMode.active() || selectionCandidate)) {
             return;
          }
         PreviewRenderOwner renderOwner = PreviewRenderOwner.select(
            snapshot.active(),
            previews().operation().active(),
            ClientSessionSystem.instance().operations().active() || ClientSessionSystem.instance().operations().selectionEdits().selectionDraftActive(),
            previews().geometry().active()
         );
         if (minecraft.level != null && player != null && renderOwner == PreviewRenderOwner.GEOMETRY) {
            renderGeometryWall(event, minecraft);
         } else if (minecraft.level != null && player != null
            && (renderOwner == PreviewRenderOwner.OPERATION || selectionCandidate)) {
            // The confirmed selection is retained as source data, but the workspace owns
            // its overlay and hit targets from this point on.
            if (OperationPreviewRenderer.shouldRenderServerSelection(
               previews().operation().active() && !ClientSessionSystem.instance().operations().selectionEdits().smartTool(),
               ClientSessionSystem.instance().operations().active(),
               ClientSessionSystem.instance().operations().selectionEdits().selectionDraftActive()
            )) {
               renderOperationSelection(event, minecraft, player, previews().operation());
            }
            if (ClientSessionSystem.instance().operations().active()) {
               renderClientOperationWorkspace(event, minecraft, pointerIntent);
            }
            OperationPreviewRenderer.renderDraft(event, minecraft, pointerIntent);
            renderSelectionCreationCandidate(event, minecraft, pointerIntent);
         } else if (minecraft.level != null
            && player != null
            && snapshot.active()) {
            BlockPos candidate = buildingCandidatePoint(snapshot, player);
            BlockPos hovered = snapshot.points().isEmpty() ? null : pointUnderCrosshair(List.of(snapshot.points().getFirst()));
            var context = previewPlacementContext(snapshot, player);
            var prototype = PlaceableItems.placementState(player.getMainHandItem(), player, context).orElse(null);
            var model = ClientPreviewSystem.instance().quickShape().buildModel(new QuickShapePreviewInput(snapshot, player, player.getEyePosition(),
               player.getViewVector(1.0F), candidate, hovered, prototype, context));
            ClientPreviewSystem.instance().quickShape().drawWorld(worldPreviewFrame(event, minecraft), model);
         }
      }
   }

   private static void renderGeometryWall(RenderLevelStageEvent event, Minecraft minecraft) {
      Vec3 view = minecraft.player == null ? new Vec3(0, 0, 1) : minecraft.player.getViewVector(1.0F);
      ClientPreviewSystem.instance().geometry().drawWorld(worldPreviewFrame(event, minecraft), geometryPreviewModel(view));
   }

   private static WorldPreviewFrame worldPreviewFrame(RenderLevelStageEvent event, Minecraft minecraft) {
      return new WorldPreviewFrame(event.getPoseStack(), minecraft.renderBuffers().bufferSource(), event.getCamera().getPosition(),
         1.0F, ClientPreviewFrameSystem.animation(), io.github.fastformer.client.render.theme.VisualThemes.revision(),
         event.getModelViewMatrix(), event.getProjectionMatrix());
   }

   private static BlockPos buildingCandidatePoint(BuildingPreviewSnapshot snapshot, LocalPlayer player) {
      return ClientSessionSystem.instance().quickShapePointer().candidate(snapshot);
   }

   private static PlacementContextSnapshot previewPlacementContext(
      BuildingPreviewSnapshot snapshot, LocalPlayer player
   ) {
      return ClientSessionSystem.instance().quickShapePointer().placementContext(snapshot);
   }

   private static GeometryPreviewPlan geometryPreviewPlan(Vec3 view) {
      return geometryPreviewModel(view).plan();
   }

   private static GeometryPreviewModel geometryPreviewModel(Vec3 view) {
      var pointer = ClientSessionSystem.instance().geometryPointer();
      var plan = pointer.plan();
      var model = ClientPreviewSystem.instance().geometry().buildModel(new GeometryPreviewInput(previews().geometry(),
         PointerDragSnapshotView.modifierHeld(FastPlaceClientInput.currentSession()), plan));
      return model.withGizmo(pointer.presentedPlan().gizmo());
   }

   private static double visiblePreviewReach(LocalPlayer player) {
      if (player == null) {
         return 0.0;
      }
      var ray = blockPointer(player);
      BlockHitResult hit = ray.hit();
      double limit = ray.distance();
      return hit.getType() == Type.BLOCK
         ? GeometryRayVisibility.visibleReach(limit, player.getEyePosition(), hit)
         : limit;
   }

   private static long renderedThemeRevision = -1;

   public static void clearWorldRenderState() {
      ClientSessionSystem.instance().inputSession().clearGeometryPointer();
      OPERATION_FACE_INTERPOLATOR.reset();
      ClientPreviewSystem.instance().quickShape().clear();
      PreviewRenderResources.clearShells();
      ClientPreviewSystem.instance().geometry().clear();
      SmoothReticlePostEffect.reset();
      smoothReticleFrame = false;
   }

   /** Returns the current long-range block target used by preview placement. */
   public static BlockPos previewRaycastTarget(LocalPlayer player) {
      if (player == null || player.level() == null) {
         return null;
      }
      BlockHitResult hit = raycastBlocks(player);
      return hit.getType() == Type.BLOCK ? hit.getBlockPos().immutable() : null;
   }

   public static BlockHitResult reachRaycast(LocalPlayer player) {
      return ClientSessionSystem.instance().inputSession().pointer().outline(player).hit();
   }

   private static BlockHitResult raycastBlocks(LocalPlayer player) {
      return blockPointer(player).hit();
   }

   private static LongRangeBlockRaycast.Result blockPointer(LocalPlayer player) {
      var operations = ClientSessionSystem.instance().operations();
      var hand = player.getMainHandItem();
      var policy = io.github.fastformer.client.interaction.pointer.PointerRayPolicy.select(
         previews().geometry().active(), previews().building().active(), operations.active(),
         operations.selectionSessionActive() || operations.selectionEdits().selectionDraftActive(),
         hand.isEmpty(), PlaceableItems.isPlaceable(hand));
      var pointer = ClientSessionSystem.instance().inputSession().pointer();
      return pointer.sample(player, FastPlaceClientInput.modifierHeld(), policy);
   }

   static double pendingGridDashOffset() {
      return io.github.fastformer.client.preview.frame.ClientPreviewFrameSystem.animation()
         .dashOffset(PENDING_DASH_SPEED, SELECTION_DASH_PERIOD);
   }

   static float ghostBreathPulse() {
      return io.github.fastformer.client.preview.frame.ClientPreviewFrameSystem.animation()
         .breathPulse(GHOST_BREATH_PERIOD_NANOS);
   }

   private static QuickShapeMode selectedMode(BuildingPreviewSnapshot snapshot, QuickShapeStage stage) {
      return (QuickShapeMode)(switch (stage) {
         case POINT -> snapshot.pointMode();
         case LINE -> snapshot.lineMode();
         case FACE -> snapshot.faceMode();
         case VOLUME -> snapshot.volumeMode();
      });
   }

   private static MutableComponent contextValue(BuildingPreviewSnapshot snapshot) {
      if (!snapshot.active()) {
         return null;
      }
      Minecraft minecraft = Minecraft.getInstance();
      LocalPlayer player = minecraft.player;
      if (player == null) {
         return null;
      }
      QuickShapeStage stage = effectiveStage(snapshot);
      if (stage == QuickShapeStage.LINE && snapshot.lineMode() == LineMode.FREE_SCROLL) {
         BlockPos offset = snapshot.freeScrollOffset();
         return Component.translatable("fastformer.message.context.free_scroll", offset.getX(), offset.getY(), offset.getZ()).withStyle(GeometryPalette.accent().style());
      } else if (stage == QuickShapeStage.LINE && snapshot.lineMode() == LineMode.RAYCAST) {
         return Component.translatable(
               "fastformer.message.context.raycast",
               snapshot.raycastPlacement() == io.github.fastformer.fastplace.quickshape.RaycastPlacement.EMBEDDED
                  ? Component.translatable("fastformer.mode.raycast.embedded")
                  : Component.translatable("fastformer.mode.raycast.surface")
            )
            .withStyle(GeometryPalette.accent().style());
      } else if (stage == QuickShapeStage.FACE && snapshot.faceMode() == FaceMode.PARALLELOGRAM_BASE_PLANE) {
         return Component.translatable(
               "fastformer.message.context.face_offset",
               GeometryNumbers.fixed(FastPlaceGeometry.faceBaseOffsetValue(snapshot.points(), snapshot.faceBaseOffset(), player.getViewVector(1.0F)), 0)
             )
             .withStyle(GeometryPalette.accent().style());
      } else if (stage == QuickShapeStage.FACE && snapshot.faceMode() == FaceMode.POLYGON && !snapshot.polygonClosed()) {
         return Component.translatable("fastformer.message.polygon_close_hint").withStyle(GeometryPalette.accent().style());
      } else {
         return stage == QuickShapeStage.VOLUME && FastPlaceGeometry.usesVolumeOffset(snapshot.modes())
            ? Component.translatable(
                  snapshot.volumeMode() == VolumeMode.FREE ? "fastformer.message.context.volume_free" : "fastformer.message.context.volume_base",
                  snapshot.volumeMode() == VolumeMode.FREE ? formatOffset(snapshot.volumeBaseOffset()) : formatScalar(snapshot.volumeBaseOffset())
               )
               .withStyle(GeometryPalette.accent().style())
            : null;
      }
   }

   private static String formatOffset(Vec3 offset) {
      Vec3 clean = GeometryNumbers.cleanZero(offset);
      return GeometryNumbers.fixed(clean.x, 0) + ", " + GeometryNumbers.fixed(clean.y, 0) + ", " + GeometryNumbers.fixed(clean.z, 0);
   }

   private static String formatScalar(Vec3 offset) {
      double value = Math.abs(offset.x) >= Math.abs(offset.y) && Math.abs(offset.x) >= Math.abs(offset.z)
         ? offset.x
         : Math.abs(offset.y) >= Math.abs(offset.z) ? offset.y : offset.z;
      return GeometryNumbers.fixed(value, 0);
   }

   private static QuickShapeStage effectiveStage(BuildingPreviewSnapshot snapshot) {
      return QuickShapeStage.resolve(snapshot.points().size(), snapshot.faceMode(), snapshot.polygonClosed());
   }

   private static String keyName(KeyMapping mapping) {
      return mapping.getTranslatedKeyMessage().getString();
   }

}
