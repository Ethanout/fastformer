package io.github.fastformer.client.render.core;

import io.github.fastformer.client.session.ClientSessionSystem;
import io.github.fastformer.client.preview.hud.HudModel;
import io.github.fastformer.client.feedback.SelectionFaceFeedback;
import io.github.fastformer.client.feedback.FeedbackFrame;
import io.github.fastformer.client.render.hud.HudFrameRenderer;



import io.github.fastformer.core.math.Axis;

import io.github.fastformer.client.render.PreviewStyle;
import io.github.fastformer.client.preview.style.GeometryPalette;

import static io.github.fastformer.client.render.core.PreviewRenderResources.*;
import static io.github.fastformer.client.render.type.PreviewRenderTypes.*;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import io.github.fastformer.client.input.FastPlaceClientInput;
import io.github.fastformer.client.preview.frame.ModifierReticleMode;
import io.github.fastformer.client.input.PointerDragSnapshotView;
import io.github.fastformer.client.interaction.intent.InteractionContext;
import io.github.fastformer.client.interaction.intent.OperationInteractionIntent;
import io.github.fastformer.client.placement.QuickReplaceMode;
import io.github.fastformer.client.render.SmoothReticlePostEffect;
import io.github.fastformer.client.interaction.intent.WorkspacePointerPrompt;
import io.github.fastformer.client.render.WorkspaceSubmissionHud;
import io.github.fastformer.client.preview.ClientPreviewSystem;
import io.github.fastformer.client.preview.frame.PreviewAnimation;
import io.github.fastformer.client.preview.frame.WorldPreviewFrame;
import io.github.fastformer.client.preview.quickshape.QuickShapePreviewInput;
import io.github.fastformer.client.preview.geometry.GeometryPreviewInput;
import io.github.fastformer.client.preview.geometry.GeometryPreviewModel;
import io.github.fastformer.client.render.hud.GizmoHudTextFormatter;
import io.github.fastformer.client.feedback.InteractionFeedback;
import io.github.fastformer.client.feedback.AxisFeedback;
import io.github.fastformer.client.feedback.ScrollFeedbackData;
import io.github.fastformer.client.render.model.*;
import io.github.fastformer.client.session.preview.ClientPreviewState;
import io.github.fastformer.common.geometry.gizmo.AxisGizmo;
import io.github.fastformer.common.geometry.GeometryAction;
import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.common.geometry.GeometryNumbers;
import io.github.fastformer.common.geometry.preview.GeometryPreviewPlan;
import io.github.fastformer.fastplace.geometry.OperationGeometry;
import io.github.fastformer.fastplace.geometry.generation.FastPlaceGeometry;
import io.github.fastformer.common.geometry.interaction.GeometryInteractionHit;
import io.github.fastformer.common.raycast.LongRangeBlockRaycast;
import io.github.fastformer.common.geometry.gizmo.GizmoTextComponent;
import io.github.fastformer.common.geometry.gizmo.GizmoTextContext;
import io.github.fastformer.common.placement.context.PlaceableItems;
import io.github.fastformer.common.placement.PlacementContextSnapshot;
import io.github.fastformer.common.quickshape.mode.FaceMode;
import io.github.fastformer.common.quickshape.mode.LineMode;
import io.github.fastformer.common.quickshape.mode.PolygonVolumeShape;
import io.github.fastformer.common.quickshape.mode.QuickShapeMode;
import io.github.fastformer.common.quickshape.mode.QuickShapeModeRules;
import io.github.fastformer.common.quickshape.mode.QuickShapeStage;
import io.github.fastformer.common.quickshape.mode.RaycastPlacement;
import io.github.fastformer.common.quickshape.mode.VolumeMode;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import io.github.fastformer.fastplace.selection.OperationStageMode;
import io.github.fastformer.fastplace.session.FastPlaceActivity;
import io.github.fastformer.common.text.TranslatableText;
import io.github.fastformer.protocol.s2c.operation.OperationPreviewPayload;
import io.github.fastformer.common.preview.quickshape.BuildingPreviewSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
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
   static final float SELECTION_HIGHLIGHT_ALPHA = PreviewStyle.FACE_HOVER_ALPHA;
   static final float GHOST_OUTLINE_ALPHA_MIN = 0.70F;
   static final float GHOST_OUTLINE_ALPHA_MAX = PreviewStyle.OUTLINE_ALPHA;

   /** Drops cached meshes that baked the previous theme's colors. */
   public static void onThemeChanged() {
      ClientSessionSystem.instance().inputSession().invalidateGeometryPlan();
      ClientPreviewSystem.instance().invalidateResources();
      PreviewRenderResources.clearShells();
       SmoothReticlePostEffect.reloadResources();
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
   private static HudModel hudFrame = HudModel.EMPTY;
   private static GeometryPreviewModel geometryFrame;
   private static AxisGizmo remoteGizmoFrame;
   private static ModifierReticleMode reticleFrame = ModifierReticleMode.NONE;
   private static OperationGeometry.RayHit operationGuideFrame;
   private static OperationGeometry.RayHit workspaceGuideFrame;
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
         return;
      }
      boolean reticleNeeded = SmoothReticlePostEffect.frameNeedsRender();
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
      if (!hudFrame.visible()) {
         restoreVanillaCrosshairIfNeeded(graphics);
         smoothReticleFrame = false;
         return;
      }
      graphics.setColor(1, 1, 1, hudFrame.opacity());
      try {
         var bottom = io.github.fastformer.client.render.hud.BottomHudLayout.forFrame(graphics, minecraft);
         HudFrameRenderer.draw(graphics, minecraft.font, bottom, hudFrame);
         SmartSelectionRenderer.drawHud(graphics, minecraft.font, bottom);
         graphics.flush();
         RenderSystem.setShaderColor(1, 1, 1, 1);
         if (smoothReticleFrame && !SmoothReticlePostEffect.process(minecraft, event.getPartialTick().getGameTimeDeltaTicks())) {
            restoreVanillaCrosshairIfNeeded(graphics);
         }
         smoothReticleFrame = false;
      } finally {
         restoreVanillaCrosshairIfNeeded(graphics);
         graphics.flush();
         graphics.setColor(1, 1, 1, 1);
         RenderSystem.setShaderColor(1, 1, 1, 1);
         smoothReticleFrame = false;
      }
   }

   /** Transitional capture adapter for legacy selection types. Called only at the frame boundary. */
   @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
   public static void onVisualFrame(net.neoforged.neoforge.client.event.RenderFrameEvent.Pre event) {
      long now = PreviewAnimation.current().nanos();
      captureVisualFrame(Minecraft.getInstance(), now, now);
   }

   public static void captureVisualFrame(Minecraft minecraft, long frameId, long now) {
      long themeRevision = io.github.fastformer.client.render.theme.VisualThemes.revision();
      if (renderedThemeRevision != themeRevision) {
         onThemeChanged();
         renderedThemeRevision = themeRevision;
      }
      var sessions = ClientSessionSystem.instance();
      var input = sessions.inputSession();
      var operations = sessions.operations();
      SmartSelectionRenderer.beginVisualFrame();
      boolean visible = minecraft.player != null && minecraft.level != null && minecraft.screen == null;
      reticleFrame = visible ? FastPlaceClientInput.modifierReticleMode() : ModifierReticleMode.NONE;
      SmoothReticlePostEffect.capture(reticleFrame, now);
      geometryFrame = visible && previews().geometry().active() ? geometryPreviewModel(minecraft.player.getViewVector(1)) : null;
      var geometryPlan = geometryFrame == null ? null : geometryFrame.plan();
      remoteGizmoFrame = visible ? remotePointer().operationGizmo() : null;
      OperationInteractionIntent target = visible ? operations.visualHoverIntent() : null;
      var workspaceHit = target instanceof OperationInteractionIntent.Face face ? face : null;
      var operationHit = visible && !operations.active() ? operationFaceTarget(remotePointer().operationSelection()) : null;
      var guides = input.feedback().faces().capture(frameId, now, sessions.currentScope(), faceValue(operationHit),
         faceValue(workspaceHit == null ? null : workspaceHit.hit()), workspaceHit == null ? null : workspaceHit.partId());
      operationGuideFrame = rayHit(guides.operation());
      workspaceGuideFrame = rayHit(guides.workspace());
      operations.pointer().prune(operations.workspace().parts());
      hudFrame = captureHud(minecraft, geometryPlan, frameId, now, visible);
   }

   static OperationGeometry.RayHit operationGuideFrame() { return operationGuideFrame; }
   static OperationGeometry.RayHit workspaceGuideFrame() { return workspaceGuideFrame; }
   static AxisGizmo remoteGizmoFrame() { return remoteGizmoFrame; }

   private static SelectionFaceFeedback.Face faceValue(OperationGeometry.RayHit hit) {
      return hit == null ? null : new SelectionFaceFeedback.Face(hit.point(), hit.normal(), hit.distance(), hit.axis());
   }

   private static OperationGeometry.RayHit rayHit(SelectionFaceFeedback.Face hit) {
      return hit == null ? null : new OperationGeometry.RayHit(hit.point(), hit.normal(), hit.distance(), hit.axis());
   }

   private static HudModel captureHud(
      Minecraft minecraft, GeometryPreviewPlan geometryPlan, long frameId, long now, boolean visible
   ) {
      if (!visible) return HudModel.EMPTY;
      var left = new ArrayList<HudModel.Text>();
      var right = new ArrayList<HudModel.Text>();
      var bottom = new ArrayList<Component>();
      var fill = FastPlaceClientInput.currentSession().smartFill();
      if (fill.active()) {
         left.add(hudText(Component.translatable(fill.statusKey(), fill.blocks().size()), 8, GeometryPalette.text().argb()));
         left.add(hudText(Component.translatable(fill.ready() ? "fastformer.fill.controls" : "fastformer.fill.cancel"), 20, GeometryPalette.muted().argb()));
         return hudModel(1, left, right, bottom, List.of(), null, 0);
      }
      if (QuickReplaceMode.active()) {
         left.add(hudText(Component.translatable("fastformer.quick_replace.active"), 8, GeometryPalette.text().argb()));
         left.add(hudText(Component.translatable(QuickReplaceMode.canReplace(minecraft)
            ? "fastformer.quick_replace.controls" : "fastformer.quick_replace.hold_block"), 20, GeometryPalette.muted().argb()));
         if (previews().activity().task()) left.add(hudText(Component.translatable(previews().activity().translationKey()), 32, GeometryPalette.valid().argb()));
         return hudModel(1, left, right, bottom, List.of(), null, 0);
      }
      var building = previews().building();
      var gizmoInput = gizmoHudInput(geometryPlan);
      var gizmoHit = capturedGizmoHit(gizmoInput);
      Object gizmoTarget = gizmoHit == null ? null : gizmoHit.handle().axis().name() + ":" + gizmoHit.handle().operation().name();
      Object scrollTarget = minecraft.hitResult instanceof BlockHitResult hit ? hit.getBlockPos().immutable() : null;
      var feedbackData = building.active() && building.lineMode() == LineMode.FREE_SCROLL && building.points().size() <= 1
         ? feedback().coordinates(building.freeScrollOffset()) : scrollFeedbackData();
      var frame = feedback().capture(frameId, now, building, scrollTarget, gizmoTarget, feedbackData, gizmoInput.dragAxis() == null);
      boolean reconnect = ClientSessionSystem.instance().operations().reconnectRestorePending() || previews().reconnectRestorePending();
      boolean pending = ClientSessionSystem.instance().operations().workspaceSubmissionPending();
      boolean task = previews().activity().task();
      var submission = WorkspaceSubmissionHud.plan(pending, task, false, reconnect);
      if (!WorkspaceSubmissionHud.hudActive(reconnect, building.enabled(), frame.persistentFreeScroll(),
         previews().geometry().active(), operationActive(), task, false, pending)) return HudModel.EMPTY;
      float opacity = task || pending || reconnect ? 1 : InteractionContext.previewVisibility(minecraft);
      Component fillMode = Component.translatable((previews().geometry().active()
         ? previews().geometry().fillMode() : building.fillMode()).translationKey());
      String fillKey = WorkspaceSubmissionHud.fillModeKey(pending);
      Component modeText = (WorkspaceSubmissionHud.FILL_MODE_STATUS_KEY.equals(fillKey)
         ? Component.translatable(fillKey, fillMode)
         : Component.translatable(fillKey, fillMode, keyName(minecraft.options.keySwapOffhand))).withStyle(GeometryPalette.text().style());
      if (!ClientSessionSystem.instance().modeView().idleActionsAllowed()) left.add(hudText(modeText, 8, -1));
      Component embedded = buildingRaycastHint();
      if (embedded != null && !task && !submission.suppressActionPrompts()) right.add(hudText(embedded, 8, GeometryPalette.muted().argb()));
      if (task) {
         MutableComponent text = Component.translatable(previews().activity().translationKey()).withStyle(GeometryPalette.valid().style());
         if (WorkspaceSubmissionHud.showsCancelHint(previews().activity(), pending)) text.append(Component.literal(" ")
            .append(Component.translatable("fastformer.activity.cancel_hint")).withStyle(GeometryPalette.muted().style()));
         left.add(hudText(text, 20, -1));
      } else if (submission.showsWaitingLine()) left.add(hudText(Component.translatable(submission.waitingKey())
         .withStyle(GeometryPalette.accent().style()), submission.waitingLineY(), -1));
      if (reconnect) left.add(hudText(Component.translatable("fastformer.message.reconnect_restore_prompt"), 32, GeometryPalette.accent().argb()));
      if (!task) captureSessionHud(minecraft, geometryPlan, submission.suppressActionPrompts(), left, right, bottom);
      var scroll = !task || frame.persistentFreeScroll() ? frame.scroll() : null;
      var crosshair = new ArrayList<HudModel.Line>();
      if (!task && geometryPlan != null) for (var block : geometryPlan.textBlocks()) {
         if (block.visible() && block.placement() == io.github.fastformer.common.geometry.preview.GeometryTextBlock.Placement.CROSSHAIR
            && io.github.fastformer.common.geometry.preview.GeometryTextBlock.hasContent(block.content())) crosshair.add(hudLine(block.content(), -1));
      }
      crosshair.addAll(captureCrosshairHud(minecraft, task ? null : geometryPlan, frame, gizmoInput, gizmoHit));
      return hudModel(opacity, left, right, bottom, crosshair, scroll, frame.scrollAlpha());
   }

   private static void captureSessionHud(Minecraft minecraft, GeometryPreviewPlan plan, boolean suppress,
      List<HudModel.Text> left,
      List<HudModel.Text> right, List<Component> bottom) {
      if (plan != null) {
         int leftY = 32, rightY = 32;
         var hints = new ArrayList<Component>();
         var statuses = new ArrayList<Component>();
         for (var block : plan.textBlocks()) {
            if (!block.visible() || !io.github.fastformer.common.geometry.preview.GeometryTextBlock.hasContent(block.content())
               || Set.of("stage", "mode", "value").contains(block.id())) continue;
            Component content = io.github.fastformer.common.geometry.preview.GeometryTextBlock.HINT_ID.equals(block.id())
               ? block.content().copy().withStyle(GeometryPalette.muted().style()) : block.content();
            switch (block.placement()) {
               case TOP_LEFT -> { left.add(hudText(content, leftY, -1)); leftY += PreviewStyle.HUD_LINE_HEIGHT; }
               case TOP_RIGHT -> { right.add(hudText(content, rightY, -1)); rightY += PreviewStyle.HUD_LINE_HEIGHT; }
               case BOTTOM_HINT -> hints.add(content);
               case BOTTOM_CENTER -> statuses.add(content);
               case CROSSHAIR -> { }
            }
         }
         bottom.addAll(hints);
         bottom.addAll(statuses);
         return;
      }
      Component primary;
      Component secondary = null;
      if (operationActive()) {
         primary = operationBottomStatus();
         if (!suppress) secondary = Component.translatable(ClientSessionSystem.instance().operations().selectionEdits().smartSampling()
            ? "fastformer.hud.smart_sampling" : io.github.fastformer.client.interaction.SmartSelectionEditView.active(minecraft)
            ? "fastformer.hud.smart_isolation_controls" : ClientSessionSystem.instance().operations().selectionEdits().smartTool()
            ? "fastformer.hud.smart_controls" : "fastformer.hud.selection_controls").withStyle(GeometryPalette.muted().style());
      } else { primary = buildingBottomStatus(); if (!suppress) secondary = buildingBottomHint(); }
      if (secondary != null) bottom.add(secondary);
      if (primary != null) bottom.add(primary);
   }

   private static HudModel hudModel(float opacity,
      List<HudModel.Text> left, List<HudModel.Text> right,
      List<Component> bottom, List<HudModel.Line> crosshair, ScrollFeedbackData scroll, int alpha) {
      return new HudModel(true, opacity, left, right, bottom, crosshair, scroll, alpha, GeometryPalette.text().argb());
   }

   private static HudModel.Text hudText(Component content, int y, int color) {
      return new HudModel.Text(content, y, color);
   }

   private static HudModel.Line hudLine(Component content, int color) {
      return new HudModel.Line(List.of(
         new HudModel.Run(content, color, 1, 0)));
   }

   /** Reuses the hovered handle already attached to the presented gizmo; never raycasts again. */
   private static AxisGizmo.Hit capturedGizmoHit(GizmoHudInput input) {
      if (input.gizmo() == null) return null;
      for (var handle : input.gizmo().handles()) if (handle.hovered()) return new AxisGizmo.Hit(handle, input.gizmo().handleCenter(handle), 0, 0);
      return null;
   }

   public static boolean geometryAllows(GeometryAction action) {
      return ClientSessionSystem.instance().geometryPointer().allows(action);
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

   private static MutableComponent buildingBottomHint() {
      if (!previews().building().active()) {
         Minecraft minecraft = Minecraft.getInstance();
         if (minecraft.player == null || minecraft.screen != null || QuickReplaceMode.active()
            || !previews().building().enabled()) return null;
         if (ClientPreviewSystem.instance().idleFrame().block() != null) {
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

   private static List<HudModel.Line> captureCrosshairHud(
      Minecraft minecraft, GeometryPreviewPlan geometryPlan, FeedbackFrame frame, GizmoHudInput hudInput, AxisGizmo.Hit capturedHit
   ) {
      var lines = new ArrayList<HudModel.Line>();
      if (minecraft.screen != null || minecraft.player == null) {
         return lines;
      }
      boolean workspaceLocked = ClientSessionSystem.instance().operations().active() && ClientSessionSystem.instance().operations().workspace().locked();
      GeometryInteractionHit interactionHit = ClientSessionSystem.instance().geometryPointer().interactionHit();
      // This text offers a pointer action. The locked workspace refuses that
      // action, so draw the text only while the workspace accepts new actions.
      if (interactionHit != null && capturedHit == null
         && WorkspacePointerPrompt.acceptsNewAction(workspaceLocked)) {
         lines.add(hudLine(interactionHit.hoverText(), GeometryPalette.text().argb()));
      }
      OperationInteractionIntent operationTarget = operationInteractionIntent().orElse(null);
      if (capturedHit == null
         && remotePointer().operationPointUnderCrosshairIndex() < 0
         && operationTarget instanceof OperationInteractionIntent.Face face) {
         for (Component line : WorkspacePointerPrompt.crosshairActionLines(face, workspaceLocked)) {
            lines.add(hudLine(line, GeometryPalette.text().argb()));
         }
         return lines;
      }
      Axis dragAxis = hudInput.dragAxis();
      Axis axis = dragAxis;
      AxisGizmo.Operation operation = dragAxis == null ? null : hudInput.dragOperation();
      AxisGizmo displayedGizmo = hudInput.gizmo();
      boolean retainedGizmo = false;
      boolean hoveredGizmo = false;
      int gizmoAlpha = 255;
      if (axis == null) {
         AxisGizmo.Hit hit = capturedHit;
         if (hit != null) {
            axis = hit.handle().axis();
            operation = hit.handle().operation();
            hoveredGizmo = true;
            gizmoAlpha = frame.gizmoAlpha();
            retainedGizmo = gizmoAlpha > 0 && axis == frame.lastAxis();
         } else {
            gizmoAlpha = frame.gizmoAlpha();
            if (gizmoAlpha > 0) {
               axis = frame.lastAxis();
               operation = frame.lastOperation();
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
               : frame.lastBaseValue();
            double currentValue = gizmoHudValue(hudInput, geometryPlan, axis, operation);
            int steps = dragAxis != null ? hudInput.dragSteps() : frame.lastSteps();
            GizmoTextContext context = hudInput.operationSelection() && operationSelectionReady()
               ? GizmoHudTextFormatter.operationTransformTextContext(axis, operation, baseValue, currentValue, steps)
               : GizmoHudTextFormatter.textContext(axis, operation, baseValue, currentValue, steps);
            String text = textComponent.render(context, valueAvailable);
            if (!text.isBlank()) {
               lines.add(hudLine(Component.literal(text), axisColor));
            }
         } else {
            var runs = new ArrayList<HudModel.Run>();
            runs.add(new HudModel.Run(Component.literal(axis.name()), axisColor, 1, 0));
            if (operation != AxisGizmo.Operation.MOVE || !valueAvailable) runs.add(
               new HudModel.Run(Component.translatable(GizmoHudTextFormatter.transformLabelKey(operation)), axisColor, 0.75F, 3));
            if (valueAvailable) {
               double baseValue = dragAxis != null
                  ? hudInput.dragBaseValue()
                  : frame.lastBaseValue();
               double currentValue = gizmoHudValue(hudInput, geometryPlan, axis, operation);
               String value = GizmoHudTextFormatter.formatValue(
                  dragAxis != null ? hudInput.dragOperation() : frame.lastOperation(),
                  baseValue,
                  currentValue,
                  dragAxis != null ? hudInput.dragSteps() : frame.lastSteps()
               );
               int valueColor = retainedGizmo ? withAlpha(axisColor, gizmoAlpha) : axisColor;
               runs.add(new HudModel.Run(Component.literal(value), valueColor, 1, 3));
            }
            lines.add(new HudModel.Line(runs));
         }
      }
      return lines;
   }

   private static GizmoHudInput gizmoHudInput(GeometryPreviewPlan geometryPlan) {
      if (ClientSessionSystem.instance().operations().active()) {
         OperationInteractionIntent.Gizmo target = operationWorkspaceGizmoHit();
         var drag = FastPlaceClientInput.currentSession().selectionGestureSnapshot().gizmo();
         return new GizmoHudInput(
            target == null ? null : target.gizmo(),
            drag == null ? null : drag.axis(),
            drag == null ? null : drag.operation(),
            PointerDragSnapshotView.operationBaseValue(FastPlaceClientInput.currentSession()),
            PointerDragSnapshotView.operationSteps(FastPlaceClientInput.currentSession()),
            true,
            false
         );
      }
      if (previews().operation().active()) {
         return new GizmoHudInput(
            remoteGizmoFrame,
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
      return 0xFF000000 | io.github.fastformer.client.gizmo.GizmoColors.axis(axis);
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
          return ClientPreviewSystem.instance().idleFrame().hasOutline();
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
          PointerDragSnapshotView.modifierHeld(FastPlaceClientInput.currentSession()),
          io.github.fastformer.common.raycast.InteractionRaycastRules.passesThroughFluids(player,
             PointerDragSnapshotView.modifierHeld(FastPlaceClientInput.currentSession())));
      return preview != null && !preview.mesh().edges().isEmpty();
   }

   @SubscribeEvent
   public static void onRenderLevelStage(RenderLevelStageEvent event) {
      if (event.getStage() == Stage.AFTER_LEVEL) {
         SmartSelectionIsolationRenderer.render(event, Minecraft.getInstance());
         return;
      }
      BuildingPreviewSnapshot snapshot = previews().building();
      if (event.getStage() == Stage.AFTER_PARTICLES) {
          Minecraft minecraft = Minecraft.getInstance();
          LocalPlayer player = minecraft.player;
          io.github.fastformer.client.render.HistoryConflictRenderer.render(event, minecraft,
             io.github.fastformer.client.input.HistoryConflictConfirmation.pending(),
             io.github.fastformer.client.input.HistoryConflictConfirmation.positions());
          if (FastPlaceClientInput.currentSession().smartFill().active()) {
             var system = ClientPreviewSystem.instance();
             system.fill().drawWorld(worldPreviewFrame(event, minecraft), system.fillFrame());
             return;
          }
          if (QuickReplaceMode.active()) {
             if (QuickReplaceMode.canReplace(minecraft) && minecraft.level != null && player != null) {
                var system = ClientPreviewSystem.instance();
                system.replace().drawWorld(worldPreviewFrame(event, minecraft), system.replaceFrame());
             }
             return;
          }
          if (ClientSessionSystem.instance().modeView().idleActionsAllowed()) {
              var system = ClientPreviewSystem.instance();
              system.idle().drawWorld(worldPreviewFrame(event, minecraft), system.idleFrame());
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
      if (geometryFrame != null) ClientPreviewSystem.instance().geometry().drawWorld(worldPreviewFrame(event, minecraft), geometryFrame);
   }

   private static WorldPreviewFrame worldPreviewFrame(RenderLevelStageEvent event, Minecraft minecraft) {
      return new WorldPreviewFrame(minecraft, event.getPoseStack(), minecraft.renderBuffers().bufferSource(), event.getCamera().getPosition(),
         1.0F, PreviewAnimation.current(), io.github.fastformer.client.render.theme.VisualThemes.revision(),
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

   private static GeometryPreviewModel geometryPreviewModel(Vec3 view) {
      var pointer = ClientSessionSystem.instance().geometryPointer();
      var plan = pointer.plan();
      var model = ClientPreviewSystem.instance().geometry().buildModel(new GeometryPreviewInput(previews().geometry(),
         PointerDragSnapshotView.modifierHeld(FastPlaceClientInput.currentSession()), plan));
      return new GeometryPreviewModel(pointer.presentedPlan(), model.blocks());
   }

   private static long renderedThemeRevision = -1;

   public static void clearWorldRenderState() {
      ClientSessionSystem.instance().inputSession().clearGeometryPointer();
      feedback().faces().clear();
      hudFrame = HudModel.EMPTY;
      geometryFrame = null;
      remoteGizmoFrame = null;
      operationGuideFrame = null;
      workspaceGuideFrame = null;
      reticleFrame = ModifierReticleMode.NONE;
      ClientPreviewSystem.instance().clear();
      PreviewRenderResources.clearShells();
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
      return io.github.fastformer.client.preview.frame.PreviewAnimation.current()
         .dashOffset(PENDING_DASH_SPEED, SELECTION_DASH_PERIOD);
   }

   static float ghostBreathPulse() {
      return io.github.fastformer.client.preview.frame.PreviewAnimation.current()
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
