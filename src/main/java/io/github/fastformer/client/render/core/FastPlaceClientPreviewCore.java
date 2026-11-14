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
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import io.github.fastformer.client.controlpoint.ControlPointPresentation;
import io.github.fastformer.client.gizmo.GizmoRenderer;
import io.github.fastformer.client.gizmo.GizmoViewScale;
import io.github.fastformer.client.gizmo.OperationGizmoPresentation;
import io.github.fastformer.client.input.FastPlaceClientInput;
import io.github.fastformer.client.input.ModifierReticleMode;
import io.github.fastformer.client.input.PointerDragSnapshotView;
import io.github.fastformer.client.interaction.intent.InteractionContext;
import io.github.fastformer.client.interaction.intent.OperationInteractionIntent;
import io.github.fastformer.client.placement.QuickReplaceMode;
import io.github.fastformer.client.placement.effect.PlacementEffectPreview;
import io.github.fastformer.client.render.FastPlaceClientShaders;
import io.github.fastformer.client.render.GhostOutlineDepthBias;
import io.github.fastformer.client.render.OperationFaceHitInterpolator;
import io.github.fastformer.client.render.PendingPreviewGrid;
import io.github.fastformer.client.render.PreviewAsyncPolicy;
import io.github.fastformer.client.render.SmoothReticlePostEffect;
import io.github.fastformer.client.render.WorkspacePointerPrompt;
import io.github.fastformer.client.render.WorkspaceSubmissionHud;
import io.github.fastformer.client.render.core.BuildingShellRenderer.BuildingShellVisibility;
import io.github.fastformer.client.render.geometry.PreviewGeometrySupport;
import io.github.fastformer.client.render.guide.GuideRenderer;
import io.github.fastformer.client.render.hud.GeometryTextBlockRenderer;
import io.github.fastformer.client.render.hud.GizmoHudTextFormatter;
import io.github.fastformer.client.render.hud.PreviewFeedbackHud;
import io.github.fastformer.client.render.interaction.OperationPointerKind;
import io.github.fastformer.client.render.interaction.OperationPointerTarget;
import io.github.fastformer.client.render.mesh.GhostMeshBuilder;
import io.github.fastformer.client.render.model.*;
import io.github.fastformer.client.render.shell.ShapeShellRenderer;
import io.github.fastformer.client.session.preview.ClientPreviewState;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.GeometryAction;
import io.github.fastformer.fastplace.geometry.GeometryHit;
import io.github.fastformer.fastplace.geometry.GeometryMode;
import io.github.fastformer.fastplace.geometry.GeometryNumbers;
import io.github.fastformer.fastplace.geometry.GeometryPreviewPlan;
import io.github.fastformer.fastplace.geometry.GeometryRayVisibility;
import io.github.fastformer.client.render.type.HaloLineRenderType;
import io.github.fastformer.fastplace.geometry.GuideLine;
import io.github.fastformer.fastplace.geometry.GuidePlane;
import io.github.fastformer.fastplace.geometry.OperationGeometry;
import io.github.fastformer.fastplace.geometry.SelectionPrism;
import io.github.fastformer.fastplace.geometry.TransformFrame;
import io.github.fastformer.fastplace.geometry.cone.ConePrismStage;
import io.github.fastformer.fastplace.geometry.controlpoint.ControlPoint;
import io.github.fastformer.fastplace.geometry.generation.ConePrismGenerator;
import io.github.fastformer.fastplace.geometry.generation.ConePrismGeometry;
import io.github.fastformer.fastplace.geometry.generation.ConePrismParameters;
import io.github.fastformer.fastplace.geometry.generation.FastPlaceGeometry;
import io.github.fastformer.fastplace.geometry.interaction.GeometryInteractionHit;
import io.github.fastformer.fastplace.geometry.interaction.GeometryInteractionTarget;
import io.github.fastformer.fastplace.geometry.raycast.LongRangeBlockRaycast;
import io.github.fastformer.fastplace.geometry.text.GizmoTextComponent;
import io.github.fastformer.fastplace.geometry.text.GizmoTextContext;
import io.github.fastformer.fastplace.placement.context.PlaceableItems;
import io.github.fastformer.fastplace.placement.context.PlacementContextSnapshot;
import io.github.fastformer.fastplace.placement.effect.ResolvedPlacementEffect;
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
import io.github.fastformer.fastplace.workflow.GeometryWorkflowView;
import io.github.fastformer.fastplace.workflow.GeometryWorkflows;
import io.github.fastformer.protocol.s2c.geometry.GeometryPreviewPayload;
import io.github.fastformer.protocol.s2c.operation.OperationPreviewPayload;
import io.github.fastformer.protocol.s2c.preview.ActivityStatePayload;
import io.github.fastformer.protocol.s2c.preview.BuildingPreviewEffectPayload;
import io.github.fastformer.protocol.s2c.preview.BuildingPreviewParametersPayload;
import io.github.fastformer.common.preview.quickshape.BuildingPreviewSnapshot;
import io.github.fastformer.protocol.s2c.preview.BuildingPreviewSessionPayload;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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
import net.minecraft.client.renderer.ShaderInstance;
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
import org.joml.Matrix4f;
import org.slf4j.Logger;

@EventBusSubscriber(
   modid = "fastformer",
   value = {Dist.CLIENT}
)
public final class FastPlaceClientPreviewCore {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final double PREVIEW_REACH = LongRangeBlockRaycast.MAX_REACH;
   private static final int CONFIRMED_FACE_BLOCK_LIMIT = 16000;
   static final float GHOST_FACE_ALPHA_MIN = 0.10F;
   static final float GHOST_FACE_ALPHA_MAX = PreviewStyle.FACE_ALPHA;
   private static final long FACE_NORMAL_INTERPOLATION_NANOS = 100_000_000L;
   private static final long PREVIEW_FAILURE_LOG_INTERVAL_NANOS = 5_000_000_000L;
   static final float SELECTION_HIGHLIGHT_ALPHA = PreviewStyle.FACE_HOVER_ALPHA;
   static final float GHOST_OUTLINE_ALPHA_MIN = 0.70F;
   static final float GHOST_OUTLINE_ALPHA_MAX = PreviewStyle.OUTLINE_ALPHA;
   private static final float PENDING_GRID_ALPHA = 0.78F;

   /** Drops cached meshes that baked the previous theme's colors. */
   public static void onThemeChanged() {
      PreviewRenderResources.clearShells();
      PreviewRenderResources.clearMeshes();
   }

   /** Theme ink; every ink pass is preceded by a {@link GeometryPalette#halo()} pass. */
   static float inkRed() { return GeometryPalette.ink().red(); }
   static float inkGreen() { return GeometryPalette.ink().green(); }
   static float inkBlue() { return GeometryPalette.ink().blue(); }
   private static final double PENDING_DASH_SPEED = PreviewStyle.DASH_SPEED;
   private static final double SELECTION_DASH_LENGTH = PreviewStyle.DASH_LENGTH;
   private static final double SELECTION_DASH_PERIOD = PreviewStyle.DASH_PERIOD;
   private static final long GHOST_BREATH_PERIOD_NANOS = 2_400_000_000L;
   private static final double GHOST_FACE_OFFSET = 0.002;
   static final double SELECTION_FACE_INFLATE = OperationSelectionVolume.RAYCAST_INFLATE;
   private static final double GHOST_OUTLINE_CAMERA_BIAS = 0.012;
   private static final ResourceLocation CROSSHAIR_SPRITE = ResourceLocation.withDefaultNamespace("hud/crosshair");
   static final double EPSILON = 1.0E-7;
   private static ClientPreviewState previews() {
      return ClientSessionSystem.instance().previews();
   }
   /** Owns transient interaction feedback and its HUD rendering. */
   private static final PreviewFeedbackHud FEEDBACK = new PreviewFeedbackHud(FastPlaceClientPreviewCore::gizmoAxisHudColor);
   static final OperationFaceHitInterpolator OPERATION_FACE_INTERPOLATOR = new OperationFaceHitInterpolator(FACE_NORMAL_INTERPOLATION_NANOS);
   static final OperationFaceHitInterpolator WORKSPACE_FACE_INTERPOLATOR = new OperationFaceHitInterpolator(FACE_NORMAL_INTERPOLATION_NANOS);
   private static TransformStatus geometryTransformBaseline;
   private static long lastPreviewFailureLogAt;
   private static final BuildingPreviewCache BUILDING_CACHE = new BuildingPreviewCache(
      previews(), () -> PointerDragSnapshotView.modifierHeld(FastPlaceClientInput.currentSession()),
      FastPlaceClientPreviewCore::logPreviewFailure
   );
   private static GeometryPlanKey cachedGeometryPlanKey;
   private static GeometryPreviewPlan cachedGeometryPlan;
   private static GeometryPreviewPlan cachedGeometryRenderSource;
   private static GeometryRenderLayers cachedGeometryRenderLayers = GeometryRenderLayers.empty();
   private static boolean smoothReticleFrame;

   private FastPlaceClientPreviewCore() {
   }

   public static void applyBuilding(BuildingPreviewSnapshot payload) {
      previews().applyBuilding(payload);
      BUILDING_CACHE.resetBuildingPreviewCaches();
   }

   public static java.util.Optional<io.github.fastformer.client.quickshape.QuickShapeSubmissionSnapshot> buildingSubmission() {
      return previews().buildingSubmission();
   }

   public static java.util.Optional<BuildingPreviewSessionPayload> idleBuildingSession() {
      return previews().idleBuildingSession();
   }

   public static net.minecraft.resources.ResourceLocation buildingProfile() {
      return previews().buildingProfile();
   }

   public static GeometryPreviewPayload geometrySnapshot() {
      return previews().geometry();
   }

   public static void applyQuickShapeSubmissionParameters(
      io.github.fastformer.protocol.s2c.preview.QuickShapeSubmissionParametersPayload payload
   ) {
      previews().applyQuickShapeSubmissionParameters(payload);
   }

   public static java.util.Optional<io.github.fastformer.protocol.s2c.preview.QuickShapeSubmissionParametersPayload> submissionParameters(
      io.github.fastformer.client.quickshape.QuickShapeSubmissionSnapshot snapshot
   ) {
      return previews().submissionParameters(snapshot);
   }

   public static void applyBuildingSession(BuildingPreviewSessionPayload payload) {
      if (previews().holdReconnectBuildingSession(payload)) {
         return;
      }
      if (previews().applyBuildingSession(payload)) {
         BUILDING_CACHE.resetBuildingPreviewCaches();
      }
   }

   public static void applyBuildingParameters(BuildingPreviewParametersPayload payload) {
      if (previews().holdReconnectBuildingParameters(payload)) {
         return;
      }
      if (previews().applyBuildingParameters(payload)) {
         BUILDING_CACHE.resetBuildingPreviewCaches();
      }
   }

   public static void applyBuildingEffect(BuildingPreviewEffectPayload payload) {
      if (previews().holdReconnectBuildingEffect(payload)) {
         return;
      }
      if (previews().applyBuildingEffect(payload)) {
         BUILDING_CACHE.resetBuildingPreviewCaches();
      }
   }

   public static void applyOperation(OperationPreviewPayload payload) {
      if (ClientSessionSystem.instance().operations().synchronize(payload)) {
         previews().applyOperation(payload);
      }
   }

   public static void applyGeometry(GeometryPreviewPayload payload) {
      if (previews().holdReconnectGeometry(payload)) {
         return;
      }
      GeometryPreviewPayload previous = previews().geometry();
      if (!previews().applyGeometry(payload)) {
         return;
      }
      if (!continuesGeometryTransform(previous, payload)) {
         geometryTransformBaseline = null;
      }
      cachedGeometryPlanKey = null;
      cachedGeometryPlan = null;
      cachedGeometryRenderSource = null;
      cachedGeometryRenderLayers = GeometryRenderLayers.empty();
   }

   public static void applyActivity(ActivityStatePayload payload) {
      if (previews().applyActivity(payload)) {
         ClientSessionSystem.instance().operations().submissions().observeActivity(payload.activity());
      }
   }

   /** Ends a reconnect boundary once the snapshot that followed it has settled. */
   public static void onClientTick() {
      previews().tickReconnectBoundary();
      Minecraft minecraft = Minecraft.getInstance();
      // Hover acceleration and transient HUD feedback belong to the focused
      // world view. Do not carry either across a menu, pause screen, or world
      // boundary where the crosshair is no longer selecting a block.
      if (minecraft.player == null || minecraft.level == null || minecraft.screen != null) FEEDBACK.clear();
      if (minecraft.player != null && minecraft.level != null && minecraft.screen == null && minecraft.isWindowActive()) {
         BUILDING_CACHE.applyBuildingPreviewResults();
      }
   }

   /** Changes whenever a server preview or activity snapshot is applied. */
   public static long stateRevision() {
      return previews().revision();
   }

   public static void noteScrollFeedback() {
      long now = System.nanoTime();
      FEEDBACK.noteScroll(previews().building(), now);
   }

   /** Clears HUD and hover feedback when the input state cancels a session. */
   public static void clearTransientFeedback() {
      FEEDBACK.clear();
   }

   public static void noteGizmoFeedback(
      Axis axis, AxisGizmo.Operation operation, int steps, double baseValue
   ) {
      FEEDBACK.noteGizmo(axis, operation, steps,
         GeometryNumbers.finiteOr(baseValue, operation == AxisGizmo.Operation.SCALE ? 1.0 : 0.0), System.nanoTime());
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

   public static boolean buildingMiddleClickIgnored() {
      BuildingPreviewSnapshot snapshot = previews().building();
      return io.github.fastformer.fastplace.quickshape.QuickShapeInputRules.ignoresMiddleClick(
         snapshot.faceMode(), snapshot.points().size(), snapshot.polygonClosed()
      );
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

   public static boolean operationNeedsSecond() {
      return previews().operation().active() && !previews().operation().hasSecond();
   }

   public static boolean operationNeedsFirst() {
      return previews().operation().active() && !previews().operation().hasFirst();
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

   public static int operationPointCount() {
      return previews().operation().active() ? previews().operation().points().size() : 0;
   }

   public static boolean operationCuboid() {
      return ClientSessionSystem.instance().operations().operationCuboid();
   }

   public static boolean operationPrism() {
      return ClientSessionSystem.instance().operations().operationPrism();
   }

   public static boolean operationPrismBaseOpen() {
      return previews().operation().active()
         && previews().operation().operationSelectionMode() == OperationSelectionMode.PRISM
         && previews().operation().operationPrismBasePointCount() == 0;
   }

   public static boolean operationPointSelected() {
      return previews().operation().active()
         && previews().operation().operationSelectedPointIndex() >= 0
         && previews().operation().operationSelectedPointIndex() < previews().operation().points().size();
   }

   public static int operationSelectedPointIndex() {
      return operationPointSelected() ? previews().operation().operationSelectedPointIndex() : -1;
   }

   public static Vec3 operationPointCenter(int index) {
      return previews().operation().active() && index >= 0 && index < previews().operation().points().size()
         ? Vec3.atCenterOf(previews().operation().points().get(index))
         : null;
   }

   public static int operationPointUnderCrosshairIndex() {
      if (!previews().operation().active() || operationSelectionConfirmed() || previews().operation().points().isEmpty()) {
         return -1;
      }
      BlockPos point = pointUnderCrosshair(previews().operation().points(), true);
      return point == null ? -1 : previews().operation().points().indexOf(point);
   }

   public static SelectionPrism.EdgeInsertion operationPrismEdgeInsertion() {
      Minecraft minecraft = Minecraft.getInstance();
      LocalPlayer player = minecraft.player;
      if (!operationPrism() || operationSelectionConfirmed() || PointerDragSnapshotView.modifierHeld(FastPlaceClientInput.currentSession()) || player == null || previews().operation().points().size() < 2
         || operationPointUnderCrosshairIndex() >= 0) {
         return null;
      }
      var ray = blockPointer(player);
      return SelectionPrism.resolveEdgeInsertion(
         previews().operation().points(),
         previews().operation().operationPrismBasePointCount(),
         player.getEyePosition(),
         player.getViewVector(1.0F),
         ray.distance()
      );
   }

   public static SelectionPrism.GridPlane operationPointGridPlane(int pointIndex) {
      if (!operationPrism() || pointIndex < 0 || pointIndex >= previews().operation().points().size()) {
         return null;
      }
      int baseCount = previews().operation().operationPrismBasePointCount() >= 3
         ? previews().operation().operationPrismBasePointCount()
         : previews().operation().points().size();
      return pointIndex < baseCount && baseCount >= 3
         ? SelectionPrism.gridPlane(previews().operation().points().subList(0, baseCount))
         : null;
   }

   public static SelectionPrism.GridLine operationPointGridLine(int pointIndex) {
      if (!operationPrism() || pointIndex < 0 || pointIndex >= previews().operation().points().size()) {
         return null;
      }
      int closedBaseCount = previews().operation().operationPrismBasePointCount();
      int baseCount = closedBaseCount >= 3 ? closedBaseCount : previews().operation().points().size();
      if (baseCount < 3 || pointIndex != 0 && (closedBaseCount < 3 || pointIndex < baseCount)) {
         return null;
      }
      return SelectionPrism.heightGridLine(previews().operation().points().subList(0, baseCount));
   }

   public static BlockPos operationCandidatePoint() {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.player == null || operationSelectionConfirmed()) {
         return null;
      }
      var ray = blockPointer(minecraft.player);
      BlockHitResult hit = ray.hit();
      if (operationPrismBaseOpen() && previews().operation().points().size() >= 3) {
         return SelectionPrism.resolveBasePlanePoint(
            previews().operation().points(),
            minecraft.player.getEyePosition(),
            minecraft.player.getViewVector(1.0F),
            ray.distance()
         );
      }
      if (operationWaitingForPrismHeight()) {
         int baseCount = previews().operation().operationPrismBasePointCount();
         return SelectionPrism.resolveHeightPoint(
            previews().operation().points().subList(0, baseCount),
            minecraft.player.getEyePosition(),
            minecraft.player.getViewVector(1.0F),
            ray.distance()
         );
      }
      return hit.getType() == Type.BLOCK ? hit.getBlockPos() : null;
   }

   public static OperationPointerTarget operationPointerTarget() {
      Minecraft minecraft = Minecraft.getInstance();
      LocalPlayer player = minecraft.player;
      if (player == null) {
         return OperationPointerTarget.none();
      }
      BlockHitResult worldHit = raycastBlocks(player);
      OperationGeometry.RayHit faceHit = null;
      if (operationCuboid() && operationSelectionReady()) {
         OperationSelectionVolume selection = operationSelection();
         if (selection != null) {
            faceHit = selection.raycast(player.getEyePosition(), player.getViewVector(1.0F), visiblePreviewReach(player));
         }
      }
      double worldDistance = worldHit.getType() == Type.BLOCK
         ? player.getEyePosition().distanceTo(worldHit.getLocation())
         : Double.POSITIVE_INFINITY;
      if (worldHit.getType() == Type.BLOCK && (faceHit == null || worldDistance < faceHit.distance())) {
         return OperationPointerTarget.world(worldHit.getBlockPos(), worldDistance);
      }
      return faceHit == null ? OperationPointerTarget.none() : OperationPointerTarget.face(faceHit);
   }

   private static boolean operationWaitingForPrismHeight() {
      return previews().operation().active()
         && previews().operation().operationSelectionMode() == OperationSelectionMode.PRISM
         && previews().operation().operationPrismBasePointCount() >= 3
         && previews().operation().points().size() == previews().operation().operationPrismBasePointCount();
   }

   public static AxisGizmo operationGizmo() {
      if (ClientSessionSystem.instance().operations().active()) {
         return null;
      }
      OperationSelectionVolume selection = operationSelection();
      Minecraft minecraft = Minecraft.getInstance();
      if (selection == null
         || !operationSelectionReady()
         || minecraft.player == null) {
         return null;
      }
      AxisGizmo.Operation[] operations = OperationGizmoPresentation.operations(previews().operation().operationStageMode())
         .toArray(AxisGizmo.Operation[]::new);
      if (operations.length == 0) {
         return null;
      }
      AABB bounds = selection.bounds();
      Vec3 center = bounds.getCenter();
      BlockPos minDisplacement = OperationGeometry.stackDisplacement(bounds, previews().operation().operationStackMin());
      BlockPos maxDisplacement = OperationGeometry.stackDisplacement(bounds, previews().operation().operationStackMax());
      center = center.add(
         (minDisplacement.getX() + maxDisplacement.getX()) * 0.5,
         (minDisplacement.getY() + maxDisplacement.getY()) * 0.5,
         (minDisplacement.getZ() + maxDisplacement.getZ()) * 0.5
      ).add(Vec3.atLowerCornerOf(previews().operation().operationTranslation()));
      Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
      GizmoViewScale scale = GizmoViewScale.fromDistance(camera.distanceTo(center));
      TransformFrame frame = TransformFrame.world(center);
      AxisGizmo gizmo = AxisGizmo.inFrame(
         frame,
         scale.axisLength(),
         scale.handleRadius(),
         operations
      ).withTextComponent(GizmoTextComponent.pointLevel());
      AxisGizmo.Hit hit = gizmo.hitTest(
         minecraft.player.getEyePosition(), minecraft.player.getViewVector(1.0F), PREVIEW_REACH
      );
      AxisGizmo.HandleKey hovered = hit == null ? null : hit.handle().key();
      return gizmo.withState(hovered, PointerDragSnapshotView.operationKey(FastPlaceClientInput.currentSession()));
   }

   public static double operationGizmoValue(Axis axis, AxisGizmo.Operation operation) {
      if (!operationSelectionReady() || axis == null || operation == null) {
         return 0.0;
      }
      return switch (operation) {
         case MOVE -> axisComponent(Vec3.atLowerCornerOf(previews().operation().operationTranslation()), axis);
         case SCALE -> axisComponent(Vec3.atLowerCornerOf(previews().operation().operationStackVector()), axis);
         case ROTATE -> Math.toDegrees(axisComponent(previews().operation().operationRotation(), axis));
      };
   }

   public static AxisGizmo.Hit operationGizmoHit() {
      OperationInteractionIntent.Gizmo workspaceTarget = operationWorkspaceGizmoHit();
      if (ClientSessionSystem.instance().operations().active()) {
         return workspaceTarget == null ? null : workspaceTarget.hit();
      }
      AxisGizmo gizmo = operationGizmo();
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

   public static OperationGeometry.RayHit operationFaceHit() {
      if (!operationCuboid() || operationSelectionConfirmed() || PointerDragSnapshotView.modifierHeld(FastPlaceClientInput.currentSession())) {
         return null;
      }
      OperationPointerTarget target = operationPointerTarget();
      return target.kind() == OperationPointerKind.FACE ? target.face() : null;
   }

   static OperationGeometry.RayHit operationFaceTarget(OperationSelectionVolume selection) {
      OperationGeometry.RayHit dragged = PointerDragSnapshotView.operationFaceHit(FastPlaceClientInput.currentSession());
      if (dragged == null) {
         return operationFaceHit();
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

   public static boolean geometryAwaitingFirstPoint() {
      return previews().geometry().active() && previews().geometry().points().isEmpty();
   }

   public static boolean geometryPointSelected() {
      return previews().geometry().active() && previews().geometry().selectedPointIndex() >= 0;
   }

   public static boolean buildingRaycastSubmodeAvailable() {
      if (!previews().building().enabled()) {
         return false;
      }
      if (!previews().building().active()) {
         Minecraft minecraft = Minecraft.getInstance();
         if (minecraft.player == null || !PlaceableItems.isPlaceable(minecraft.player.getMainHandItem())) {
            return false;
         }
      }
      return buildingRaycastSubmode();
   }

   public static GeometryInteractionHit geometryInteractionHit() {
      Minecraft minecraft = Minecraft.getInstance();
      LocalPlayer player = minecraft.player;
      if (!previews().geometry().active() || player == null || InteractionContext.vanillaOwnsPointer(minecraft)) {
         return null;
      }
      Vec3 eye = player.getEyePosition();
      Vec3 view = player.getViewVector(1.0F);
      GeometryWorkflowView workflowView = geometryWorkflowView(view);
      List<GeometryInteractionTarget> targets = GeometryWorkflows.get(previews().geometry().mode()).interactionTargets(workflowView);
      return GeometryInteractionHit.nearest(eye, view, visiblePreviewReach(player), targets);
   }

   public static boolean embeddedModifierReticle() {
      if (io.github.fastformer.client.session.ClientSessionSystem.instance().modeView().idleActionsAllowed()) {
         Minecraft minecraft = Minecraft.getInstance();
         return minecraft.player != null && minecraft.player.isCreative() && enabled() && !QuickReplaceMode.active()
            && minecraft.getCameraEntity() == minecraft.player
            && PlaceableItems.isPlaceable(minecraft.player.getMainHandItem())
            && PointerDragSnapshotView.modifierHeld(FastPlaceClientInput.currentSession());
      }
      if (PointerDragSnapshotView.modifierHeld(FastPlaceClientInput.currentSession()) && previews().geometry().active()) {
         return previews().geometry().mode() == GeometryMode.WALL;
      }
      return !previews().geometry().active()
         && !previews().operation().active()
         && buildingRaycastSubmode()
         && PointerDragSnapshotView.modifierHeld(FastPlaceClientInput.currentSession());
   }

   public static boolean halfGridModifierReticle() {
      if (buildingRaycastSubmode()) {
         return false;
      }
      if (!previews().geometry().active()) {
         return false;
      }
      return switch (previews().geometry().mode()) {
         case POLYHEDRON -> !previews().geometry().closed();
         case CONE_PRISM -> previews().geometry().conePlaneMode().stageFor(previews().geometry().points().size()) != ConePrismStage.ADJUST;
         case CONVEX_POLYHEDRON -> true;
         default -> false;
      };
   }

   public static AxisGizmo.Hit geometryGizmoHit() {
      Minecraft minecraft = Minecraft.getInstance();
      LocalPlayer player = minecraft.player;
      if (!previews().geometry().active() || player == null || InteractionContext.vanillaOwnsPointer(minecraft)) {
         return null;
      }
      Vec3 view = player.getViewVector(1.0F);
      AxisGizmo gizmo = geometryGizmo(view);
      return gizmo == null ? null : gizmo.hitTest(player.getEyePosition(), view, visiblePreviewReach(player));
   }

   public static AxisGizmo geometryGizmo() {
      Minecraft minecraft = Minecraft.getInstance();
      LocalPlayer player = minecraft.player;
      Vec3 view = player == null ? new Vec3(0.0, 0.0, 1.0) : player.getViewVector(1.0F);
      return geometryGizmo(view);
   }

   public static double geometryGizmoValue(Axis axis, AxisGizmo.Operation operation) {
      Minecraft minecraft = Minecraft.getInstance();
      LocalPlayer player = minecraft.player;
      if (axis == null || operation == null || player == null || !previews().geometry().active()) {
         return operation == AxisGizmo.Operation.SCALE ? 1.0 : 0.0;
      }
      GeometryPreviewPlan plan = geometryPreviewPlan(player.getViewVector(1.0F));
      return geometryGizmoValue(plan, axis, operation);
   }

   private static AxisGizmo geometryGizmo(Vec3 view) {
      Minecraft minecraft = Minecraft.getInstance();
      if (!previews().geometry().active() || minecraft.player == null || InteractionContext.vanillaOwnsPointer(minecraft)) {
         return null;
      }
      return geometryPreviewPlan(view).gizmo();
   }

   public static BlockPos geometryPointUnderCrosshair() {
      if (!previews().geometry().active()
         || previews().geometry().mode() != GeometryMode.WALL
         || previews().geometry().closed()
         || previews().geometry().points().isEmpty()) {
         return null;
      }
      return pointUnderCrosshair(List.of(previews().geometry().points().getFirst()));
   }

   public static boolean closePathAtHoveredStart() {
      List<BlockPos> points = closablePathPoints(3);
      return !points.isEmpty() && points.getFirst().equals(pointUnderCrosshair(List.of(points.getFirst())));
   }

   public static boolean canDoubleClickClosePath() {
      return !closablePathPoints(2).isEmpty();
   }

   public static BlockPos pathCandidatePoint() {
      Minecraft minecraft = Minecraft.getInstance();
      LocalPlayer player = minecraft.player;
      if (player == null) {
         return null;
      }
      if (previews().geometry().active()) {
         return geometryCandidatePoint(minecraft, player);
      }
      if (previews().operation().active()) {
         return operationCandidatePoint();
      }
      return previews().building().active() ? buildingCandidatePoint(previews().building(), player) : null;
   }

   private static List<BlockPos> closablePathPoints(int minimumPoints) {
      if (operationPrismBaseOpen() && previews().operation().points().size() >= minimumPoints) {
         return previews().operation().points();
      }
      if (previews().geometry().active()
         && previews().geometry().mode() == GeometryMode.WALL
         && !previews().geometry().closed()
         && previews().geometry().points().size() >= minimumPoints) {
         return previews().geometry().points();
      }
      if (previews().building().active()
         && previews().building().faceMode() == FaceMode.POLYGON
         && !previews().building().polygonClosed()
         && previews().building().points().size() >= minimumPoints) {
         return previews().building().points();
      }
      return List.of();
   }

   private static BlockPos pointUnderCrosshair(List<BlockPos> points) {
      return pointUnderCrosshair(points, false);
   }

   private static BlockPos pointUnderCrosshair(List<BlockPos> points, boolean throughBlocks) {
      Minecraft minecraft = Minecraft.getInstance();
      LocalPlayer player = minecraft.player;
      if (player == null || points.isEmpty()) {
         return null;
      }
      Vec3 eye = player.getEyePosition();
      Vec3 direction = player.getViewVector(1.0F).normalize();
      double maxDistance = PREVIEW_REACH;
      if (!throughBlocks) {
         BlockHitResult worldHit = raycastBlocks(player);
         if (worldHit.getType() == Type.BLOCK) {
            maxDistance = Math.min(maxDistance, eye.distanceTo(worldHit.getLocation()) + 1.0E-4);
         }
      }
      Vec3 end = eye.add(direction.scale(maxDistance));
      BlockPos closest = null;
      double bestRayDistance = Double.POSITIVE_INFINITY;
      for (BlockPos point : points) {
         java.util.Optional<Vec3> hit = new AABB(point).clip(eye, end);
         if (hit.isPresent()) {
            double rayDistance = eye.distanceToSqr(hit.orElseThrow());
            if (rayDistance >= bestRayDistance) {
               continue;
            }
            bestRayDistance = rayDistance;
            closest = point;
         }
      }
      return closest;
   }

   public static BlockPos lineModeCandidate() {
      Minecraft minecraft = Minecraft.getInstance();
      LocalPlayer player = minecraft.player;
      BuildingPreviewSnapshot snapshot = previews().building();
      if (player == null || minecraft.level == null || !snapshot.active() || QuickShapeStage.fromPointCount(snapshot.points().size()) != QuickShapeStage.LINE) {
         return null;
      }

      return buildingCandidatePoint(snapshot, player);
   }

   public static AABB operationBounds() {
      OperationSelectionVolume selection = operationSelection();
      return selection == null ? null : selection.bounds();
   }

   public static OperationSelectionVolume operationSelection() {
      OperationPreviewPayload snapshot = previews().operation();
      if (!snapshot.active()) {
         return null;
      }
      if (snapshot.operationSelectionMode() == io.github.fastformer.fastplace.selection.OperationSelectionMode.CUBOID
         && snapshot.selectionMin() != null && snapshot.selectionMax() != null) {
         return OperationSelectionVolume.cuboid(
            snapshot.selectionMin(), snapshot.selectionMax(),
            snapshot.points().isEmpty() ? null : snapshot.points().getFirst(),
            snapshot.points().size() < 2 ? null : snapshot.points().get(1)
         );
      }
      return OperationSelectionVolume.create(
            snapshot.operationSelectionMode(),
            snapshot.points(),
            snapshot.operationPrismBasePointCount(),
            snapshot.operationMinOffset(),
            snapshot.operationMaxOffset(),
            snapshot.operationHullInflation()
         );
   }

   public static boolean usesAngleDistance() {
      if (!previews().building().enabled()) {
         return false;
      } else {
         QuickShapeStage stage = previews().building().active() ? QuickShapeStage.fromPointCount(previews().building().points().size()) : QuickShapeStage.POINT;
         return stage == QuickShapeStage.LINE && previews().building().lineMode() == LineMode.FREE_SCROLL;
      }
   }

   public static boolean usesScrollContext() {
      if (previews().geometry().active()) {
         return geometryAllows(GeometryAction.SCALAR_ADJUST);
      } else if (previews().operation().active()) {
         return false;
      } else if (!previews().building().active()) {
         return false;
      } else {
         QuickShapeStage stage = effectiveStage(previews().building());
         return usesAngleDistance()
            || stage == QuickShapeStage.FACE && previews().building().faceMode() == FaceMode.PARALLELOGRAM_BASE_PLANE
            || stage == QuickShapeStage.VOLUME && FastPlaceGeometry.usesVolumeOffset(previews().building().modes());
      }
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
      boolean persistentFreeScroll = FEEDBACK.refreshFreeScrollSession(previews().building(), System.nanoTime());
      boolean reconnectRestorePending = ClientSessionSystem.instance().operations().reconnectRestorePending()
         || reconnectPreviewRestorePending();
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
      if (!io.github.fastformer.client.session.ClientSessionSystem.instance().modeView().idleActionsAllowed()) graphics.drawString(minecraft.font, fill, 8, 8, -1, true);

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
      return previews().geometry().active()
         && GeometryWorkflows.allows(geometryWorkflowView(new Vec3(0.0, 0.0, 1.0)), action);
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

   private static TransformStatus transformStatus(GeometryPreviewPlan geometryPlan) {
      TransformStatus current = rawTransformStatus(geometryPlan);
      if (current == null) {
         return null;
      }
      if (geometryTransformBaseline == null) {
         geometryTransformBaseline = current;
      }
      TransformStatus baseline = geometryTransformBaseline;
      return current.relativeTo(baseline);
   }

   private static TransformStatus rawTransformStatus(GeometryPreviewPlan geometryPlan) {
      if (!geometryAdjusting(previews().geometry())) {
         return null;
      }
      if (previews().geometry().mode() == GeometryMode.CONE_PRISM) {
         ConePrismGeometry geometry = coneGeometry(previews().geometry());
         if (geometry == null || !geometry.heightReady()) {
            return null;
         }
         Vec3 center = geometry.base().center().add(geometry.topCenter()).scale(0.5);
         return new TransformStatus(
            center,
            new Vec3(geometry.scaleX(), Math.max(0.5, Math.abs(geometry.height())), geometry.scaleZ()),
            new Vec3(0.0, Math.toDegrees(previews().geometry().coneRotationRadians()), 0.0)
         );
      }
      if (previews().geometry().mode() == GeometryMode.POLYHEDRON) {
         Vec3 center = transformCenter(geometryPlan);
         if (center == null) {
            return null;
         }
         Vec3 scale = previews().geometry().polyhedronGizmoLocal()
            ? previews().geometry().polyhedronLocalScale()
            : previews().geometry().polyhedronWorldScale();
         return new TransformStatus(center, scale, TransformStatus.eulerDegrees(previews().geometry().rotation()));
      }
      return null;
   }

   private static Vec3 transformCenter(GeometryPreviewPlan geometryPlan) {
      if (geometryPlan != null && geometryPlan.gizmo() != null) {
         return geometryPlan.gizmo().center();
      }
      return previews().geometry().pointLocations().isEmpty() ? null : previews().geometry().pointLocations().getFirst();
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
      TransformStatus transform = transformStatus(geometryPlan);
      if (transform == null) {
         return operation == AxisGizmo.Operation.SCALE ? 1.0 : 0.0;
      }
      return switch (operation) {
         case MOVE -> {
            AxisGizmo gizmo = geometryPlan == null ? null : geometryPlan.gizmo();
            Vec3 direction = gizmo == null ? axisVector(axis) : gizmo.axisVector(axis);
            yield transform.position().dot(direction);
         }
         case SCALE -> axisComponent(transform.scale(), axis);
         case ROTATE -> axisComponent(transform.rotationDegrees(), axis);
      };
   }

   private static Vec3 axisVector(Axis axis) {
      return switch (axis) {
         case X -> new Vec3(1.0, 0.0, 0.0);
         case Y -> new Vec3(0.0, 1.0, 0.0);
         case Z -> new Vec3(0.0, 0.0, 1.0);
      };
   }

   private static boolean continuesGeometryTransform(
      GeometryPreviewPayload previous, GeometryPreviewPayload next
   ) {
      return previous != null
         && next != null
         && previous.active()
         && next.active()
         && previous.mode() == next.mode()
         && geometryAdjusting(previous)
         && geometryAdjusting(next);
   }

   private static boolean geometryAdjusting(GeometryPreviewPayload payload) {
      if (payload == null || !payload.active()) {
         return false;
      }
      return switch (payload.mode()) {
         case POLYHEDRON -> payload.closed();
         case CONE_PRISM -> payload.conePlaneMode().stageFor(payload.points().size()) == ConePrismStage.ADJUST;
         default -> false;
      };
   }

   private static ConePrismGeometry coneGeometry(GeometryPreviewPayload payload) {
      int facePointCount = Math.min(payload.pointLocations().size(), payload.conePlaneMode().facePointCount());
      List<Vec3> facePoints = List.copyOf(payload.pointLocations().subList(0, facePointCount));
      Optional<Vec3> heightPoint = payload.pointLocations().size() > facePointCount
         ? Optional.of(payload.pointLocations().get(facePointCount))
         : Optional.empty();
      return ConePrismGeometry.from(new ConePrismParameters(
         facePoints,
         heightPoint,
         payload.coneShapeVariant(),
         payload.conePlaneMode(),
         payload.coneRadius(),
         payload.coneScaleX(),
         payload.coneScaleZ(),
         payload.coneTopScaleOffset(),
         payload.coneTopOffset(),
         payload.coneRotationRadians()
      ));
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
      return switch (effectiveStage(previews().building())) {
         case POINT -> previews().building().pointMode() == PointMode.RAYCAST;
         case LINE -> previews().building().lineMode() == LineMode.RAYCAST;
         default -> false;
      };
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
      GeometryInteractionHit interactionHit = geometryInteractionHit();
      // This text offers a pointer action. The locked workspace refuses that
      // action, so draw the text only while the workspace accepts new actions.
      if (interactionHit != null && geometryGizmoHit() == null
         && WorkspacePointerPrompt.acceptsNewAction(workspaceLocked)) {
         graphics.drawString(minecraft.font, interactionHit.hoverText(), x, y, GeometryPalette.text().argb(), true);
         y += 10;
      }
      OperationInteractionIntent operationTarget = operationInteractionIntent().orElse(null);
      if (operationGizmoHit() == null
         && operationPointUnderCrosshairIndex() < 0
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
            double speed = FEEDBACK.gizmoDwellMultiplier(axis.name() + ":" + operation.name(), now);
            gizmoAlpha = FEEDBACK.gizmoAlpha(now, axis.name() + ":" + operation.name(), speed);
            retainedGizmo = gizmoAlpha > 0 && axis == FEEDBACK.lastGizmoAxis();
         } else {
            long now = System.nanoTime();
            gizmoAlpha = FEEDBACK.gizmoAlpha(now, null, 1.0);
            if (gizmoAlpha > 0) {
               axis = FEEDBACK.lastGizmoAxis();
               operation = FEEDBACK.lastGizmoOperation();
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
               : FEEDBACK.lastGizmoBaseValue();
            double currentValue = gizmoHudValue(hudInput, geometryPlan, axis, operation);
            int steps = dragAxis != null ? hudInput.dragSteps() : FEEDBACK.lastGizmoSteps();
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
                  : FEEDBACK.lastGizmoBaseValue();
               double currentValue = gizmoHudValue(hudInput, geometryPlan, axis, operation);
               String value = GizmoHudTextFormatter.formatValue(
                  dragAxis != null ? hudInput.dragOperation() : FEEDBACK.lastGizmoOperation(),
                  baseValue,
                  currentValue,
                  dragAxis != null ? hudInput.dragSteps() : FEEDBACK.lastGizmoSteps()
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
            operationGizmo(),
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
         return operationGizmoValue(axis, operation);
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
      FEEDBACK.renderScrollFeedback(
         graphics, minecraft, scrollFeedbackData(), persistentFreeScroll, System.nanoTime(), bottomLayout
      );
   }

   private static ScrollFeedbackData scrollFeedbackData() {
      if (previews().operation().active() && operationSelectionReady()) {
      BlockPos value = operationSelectionConfirmed() && PointerDragSnapshotView.modifierHeld(FastPlaceClientInput.currentSession())
            ? previews().operation().stackVector()
            : previews().operation().translation();
         return FEEDBACK.coordinates(value);
      }
      if (previews().geometry().active() && previews().geometry().mode() == GeometryMode.CONE_PRISM) {
         Vec3 offset = previews().geometry().coneTopOffset();
         return new ScrollFeedbackData(
            List.of(
               new AxisFeedback("X", GeometryNumbers.fixed(offset.x, 2), FEEDBACK.axisColor(Axis.X)),
               new AxisFeedback("Z", GeometryNumbers.fixed(offset.z, 2), FEEDBACK.axisColor(Axis.Z))
            ),
            ""
         );
      }
      if (previews().building().active()) {
         BuildingPreviewSnapshot building = previews().building();
         // Keep the free-scroll payload aligned with persistent visibility.
         // effectiveStage() can briefly describe the previous server snapshot
         // while the active line session already owns the scroll offset.
         if (FEEDBACK.freeScrollSession() && building.points().size() <= 1) {
            BlockPos offset = building.points().size() == 1
               ? building.freeScrollOffset() : FEEDBACK.freeScrollOffset();
            ScrollFeedbackData data = FEEDBACK.coordinates(offset);
            FEEDBACK.rememberFreeScrollData(data);
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
         if (stage == QuickShapeStage.VOLUME && FastPlaceGeometry.usesVolumeOffset(BUILDING_CACHE.effectiveBuildingModes(previews().building()))) {
            return previews().building().volumeMode() == VolumeMode.FREE
               ? FEEDBACK.coordinates(previews().building().volumeBaseOffset())
               : new ScrollFeedbackData(List.of(), formatScalar(previews().building().volumeBaseOffset()));
         }
      }
      // A wheel request can produce one or more inactive snapshots while the
      // server applies the new offset. Keep rendering the latched value for
      // that gap; otherwise the HUD disappears even though the session is
      // still valid and the feedback component keeps it visible.
      if (FEEDBACK.freeScrollSession()) {
         ScrollFeedbackData data = FEEDBACK.freeScrollData();
         return data != null ? data : FEEDBACK.coordinates(FEEDBACK.freeScrollOffset());
      }
      return null;
   }

   private static int withAlpha(int color, int alpha) {
      return (Math.clamp(alpha, 0, 255) << 24) | (color & 0x00FFFFFF);
   }

   static void logPreviewFailure(String message, Throwable failure) {
      long now = System.nanoTime();
      if (lastPreviewFailureLogAt == 0L
         || now < lastPreviewFailureLogAt
         || now - lastPreviewFailureLogAt >= PREVIEW_FAILURE_LOG_INTERVAL_NANOS) {
         lastPreviewFailureLogAt = now;
         LOGGER.warn(message, failure);
      }
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
      if (io.github.fastformer.client.session.ClientSessionSystem.instance().modeView().idleActionsAllowed()) {
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
          if (io.github.fastformer.client.session.ClientSessionSystem.instance().modeView().idleActionsAllowed()) {
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
            Vec3 eye = player.getEyePosition();
            Vec3 view = player.getViewVector(1.0F);
            BlockPos candidate = buildingCandidatePoint(snapshot, player);
            BlockPos hoveredPoint = snapshot.points().isEmpty()
               ? null
               : pointUnderCrosshair(List.of(snapshot.points().getFirst()));
            List<BlockPos> previewPoints = new ArrayList<>(snapshot.points());
            boolean closing = ControlPointPresentation.closingCandidate(snapshot, hoveredPoint);
            if (!snapshot.polygonHeightConfirmed()
               && (candidate != null || closing)) {
               BlockPos previewPoint = closing ? snapshot.points().getFirst() : candidate;
               if (previewPoints.isEmpty() || !previewPoint.equals(previewPoints.getLast())) {
                  previewPoints.add(previewPoint);
               }
            }

             boolean polygonHeightConfirmed = snapshot.polygonHeightConfirmed()
                || snapshot.polygonClosed() && previewPoints.size() > snapshot.points().size();
             FastPlaceGeometry.Modes buildingModes = BUILDING_CACHE.effectiveBuildingModes(snapshot);
             BlockState previewState = PlaceableItems.placementState(
               player.getMainHandItem(), player, previewPlacementContext(snapshot, player)
            ).orElse(null);
             ResolvedPlacementEffect previewEffect = PlacementEffectPreview.resolve(
                snapshot,
                player,
                previewState,
                previewPoints,
                polygonHeightConfirmed,
                buildingModes,
                previewPlacementContext(snapshot, player)
             );
             Set<BlockPos> previewBlocks = PlacementEffectPreview.applyToTargets(
                previewEffect, BUILDING_CACHE.buildingPreviewBlocksCached(snapshot, previewPoints, polygonHeightConfirmed)
             );
             Set<BlockPos> candidateBlocks = ControlPointPresentation.candidateBlocks(snapshot, candidate, hoveredPoint);
             BuildingRenderFrame renderFrame = BUILDING_CACHE.buildingRenderFrame(
                snapshot, previewEffect, previewBlocks, candidateBlocks, candidate, hoveredPoint
             );
             BuildingRenderLayers layers = renderFrame.layers();
             List<GuidePlane> planes = FastPlaceGeometry.guidePlanes(
               snapshot.points(),
               snapshot.polygonClosed(),
               candidate,
                layers.allBlocks(),
               snapshot.faceBaseOffset(),
               snapshot.volumeBaseOffset(),
               snapshot.perpendicularAnchor(),
               eye,
               view,
               BUILDING_CACHE.effectiveBuildingModes(snapshot)
            );
            BufferSource buffers = minecraft.renderBuffers().bufferSource();
            PoseStack poseStack = event.getPoseStack();
            Vec3 camera = event.getCamera().getPosition();
            List<ControlPoint> buildingPoints = renderFrame.controlPoints();
            Map<BlockPos, BuildingSpecialBlock> specialBlockStyles = renderFrame.specialBlockStyles();
            Map<BlockPos, BlockState> previewStateOverrides = renderFrame.stateOverrides();
            boolean confirmedLightweight = PreviewAsyncPolicy.useOutlineOnly(snapshot.points(),
               BUILDING_CACHE.buildingPreviewWorkload(snapshot, snapshot.points(), snapshot.polygonHeightConfirmed()));
            boolean pendingLightweight = PreviewAsyncPolicy.useOutlineOnly(previewPoints,
               BUILDING_CACHE.buildingPreviewWorkload(snapshot, previewPoints, polygonHeightConfirmed));
            confirmedLightweight |= PreviewAsyncPolicy.useLightweightShell(layers.confirmedRenderBlocks().size());
            pendingLightweight |= PreviewAsyncPolicy.useLightweightShell(layers.pendingRenderBlocks().size());
            // The per-block result is the preview. The geometric boundary only stands in
            // when a layer is too large for a shell; a lone center line is never drawn.
            List<GuideLine> confirmedOutlineEdges = PreviewGeometrySupport.outlineGeometryEdges(
               snapshot.points(), buildingModes.faceMode(), snapshot.polygonClosed(),
               snapshot.polygonHeightConfirmed(), snapshot.polygonVolumeShape(), buildingModes.volumeMode()
            );
            List<GuideLine> pendingOutlineEdges = PreviewGeometrySupport.outlineGeometryEdges(
               previewPoints, buildingModes.faceMode(), snapshot.polygonClosed(),
               polygonHeightConfirmed, snapshot.polygonVolumeShape(), buildingModes.volumeMode()
            );
            if (pendingOutlineEdges.equals(confirmedOutlineEdges)) {
               pendingOutlineEdges = List.of();
            }
            confirmedOutlineEdges = boundaryFallback(confirmedOutlineEdges, confirmedLightweight);
            pendingOutlineEdges = boundaryFallback(pendingOutlineEdges, pendingLightweight);
            BuildingRenderLayers shellBlocks = renderFrame.shellLayers();
            BuildingShellVisibility shellVisibility = BuildingShellRenderer.render(
               player,
               poseStack,
               buffers,
               camera,
               previewState,
               previewStateOverrides,
               shellBlocks.confirmedRenderBlocks(),
               shellBlocks.pendingRenderBlocks(),
               specialBlockStyles,
               1.0F, ghostBreathPulse(),
               confirmedLightweight,
               pendingLightweight
            );
            // Keep the geometric boundary authoritative when the async shell is
            // unavailable. The fallback also draws the first point face, while
            // outline edges remain in the same world-space coordinate path.
            if (!confirmedOutlineEdges.isEmpty() || !pendingOutlineEdges.isEmpty()) {
               ShapeShellRenderer.renderOutlineEdges(
                  poseStack,
                  buffers.getBuffer(PENDING_LINES),
                  camera,
                  confirmedOutlineEdges,
                  List.of()
               );
               buffers.endBatch(PENDING_LINES);
               ShapeShellRenderer.renderOutlineEdges(poseStack, buffers.getBuffer(DYNAMIC_LINES), camera,
                  List.of(), pendingOutlineEdges);
               buffers.endBatch(DYNAMIC_LINES);
            }
            BuildingPointPreviewRenderer.render(poseStack, buffers, camera, buildingPoints,
               previewState, previewStateOverrides, 1.0F);
            renderBuildingGuidePlaneGrid(poseStack, buffers.getBuffer(PENDING_LINES), camera, planes);
            buffers.endBatch(PENDING_LINES);
         }
      }
   }

   private static void renderGeometryWall(RenderLevelStageEvent event, Minecraft minecraft) {
      Vec3 view = minecraft.player == null ? new Vec3(0.0, 0.0, 1.0) : minecraft.player.getViewVector(1.0F);
      GeometryPreviewPlan plan = geometryPreviewPlan(view);
      BufferSource buffers = minecraft.renderBuffers().bufferSource();
      PoseStack poseStack = event.getPoseStack();
      Vec3 camera = event.getCamera().getPosition();
      GeometryRenderLayers layers = geometryRenderLayers();
      renderConfirmedBlocks(poseStack, buffers, camera, layers.confirmed());
      renderPendingBlocks(
         poseStack,
         buffers,
         camera,
         event.getModelViewMatrix(),
         event.getProjectionMatrix(),
         layers.pending()
      );
      renderGeometryControlPoints(poseStack, buffers, camera, plan);
      renderGuidePlaneGrid(poseStack, buffers.getBuffer(PENDING_LINES), camera, plan.guidePlanes());
      renderGuideLines(poseStack, buffers.getBuffer(PENDING_LINES), camera, plan.guideLines());
      buffers.endBatch(PENDING_LINES);
      if (plan.gizmo() != null) {
         new GizmoRenderer(1.0F).renderGeometryGizmo(poseStack, buffers, camera, plan.gizmo());
      }
   }

   private static GeometryWorkflowView geometryWorkflowView(Vec3 view) {
      return new GeometryWorkflowView(
         previews().geometry().mode(),
         previews().geometry().points().size(),
         previews().geometry().pointLocations(),
         previews().geometry().pointRoles(),
         previews().geometry().closed(),
         PointerDragSnapshotView.modifierHeld(FastPlaceClientInput.currentSession()),
         previews().geometry().polyhedronShapeVariant(),
         previews().geometry().coneShapeVariant(),
         previews().geometry().compoundShapeVariant(),
         previews().geometry().polyhedronSizeMode(),
         previews().geometry().extrusion(),
         previews().geometry().conePlaneMode(),
         previews().geometry().coneRadius(),
         previews().geometry().coneScaleX(),
         previews().geometry().coneScaleZ(),
         previews().geometry().coneTopScaleOffset(),
         previews().geometry().coneTopOffset(),
         previews().geometry().coneRotationRadians(),
         previews().geometry().coneGizmoLocal(),
         previews().geometry().rotation(),
         previews().geometry().polyhedronLocalScale(),
         previews().geometry().polyhedronWorldScale(),
         previews().geometry().polyhedronGizmoLocal(),
         previews().geometry().fillMode(),
         view,
         previews().geometry().selectedPointIndex()
      );
   }

   private static BlockPos buildingCandidatePoint(BuildingPreviewSnapshot snapshot, LocalPlayer player) {
      if (!snapshot.active() && snapshot.points().isEmpty()) {
         BlockHitResult firstHit = raycastBlocks(player);
         if (firstHit.getType() != Type.BLOCK) {
            return null;
         }
         if (player.getMainHandItem().isEmpty()
            || PointerDragSnapshotView.modifierHeld(FastPlaceClientInput.currentSession())) return firstHit.getBlockPos();
         return PlacementContextSnapshot.capture(player.level(), player, player.getMainHandItem(), firstHit, false).placementPosition();
      }
      if (snapshot.polygonHeightConfirmed()) {
         return snapshot.points().isEmpty() ? null : snapshot.points().getLast();
      }
      Vec3 eye = player.getEyePosition();
      Vec3 view = player.getViewVector(1.0F);
      BlockHitResult hit = raycastBlocks(player);
      BlockPos hitBlock;
      BlockPos surfaceBlock;
      if (hit.getType() == Type.BLOCK) {
         hitBlock = hit.getBlockPos();
         surfaceBlock = hit.getBlockPos().relative(hit.getDirection());
      } else {
         BlockPos offset = snapshot.freeScrollOffset();
         BlockPos base = snapshot.points().isEmpty() ? BlockPos.ZERO : snapshot.points().getFirst();
         BlockPos anchor = base.offset(offset);
         hitBlock = anchor;
         surfaceBlock = anchor;
      }
      return FastPlaceGeometry.resolveCandidate(
         snapshot.points(),
         snapshot.polygonClosed(),
         hitBlock,
         surfaceBlock,
         snapshot.faceBaseOffset(),
         snapshot.volumeBaseOffset(),
         snapshot.perpendicularAnchor(),
         eye,
         view,
         snapshot.freeScrollOffset(),
          BUILDING_CACHE.effectiveBuildingModes(snapshot)
      );
   }

   private static PlacementContextSnapshot previewPlacementContext(
      BuildingPreviewSnapshot snapshot, LocalPlayer player
   ) {
      if (snapshot.placementContext() != null || !snapshot.points().isEmpty()) {
         return snapshot.placementContext();
      }
      BlockHitResult hit = raycastBlocks(player);
      if (hit.getType() != Type.BLOCK) {
         return null;
      }
      FastPlaceGeometry.Modes modes = BUILDING_CACHE.effectiveBuildingModes(snapshot);
      boolean embedded = snapshot.pointMode() == PointMode.RAYCAST
         && modes.raycastPlacement() == RaycastPlacement.EMBEDDED;
      return PlacementContextSnapshot.capture(
         player.level(), player, player.getMainHandItem(), hit, embedded
      );
   }

   private static GeometryPreviewPlan geometryPreviewPlan(Vec3 view) {
      Minecraft minecraft = Minecraft.getInstance();
      LocalPlayer player = minecraft.player;
      GeometryWorkflowView workflowView = geometryWorkflowView(view);
      GeometryHit candidate = GeometryWorkflows.allows(workflowView, GeometryAction.CANDIDATE_INPUT)
         ? geometryCandidateHit(minecraft, player)
         : null;
      Vec3 eye = player == null ? Vec3.ZERO : player.getEyePosition();
      BlockPos hoveredPoint = geometryPointUnderCrosshair();
      GeometryPlanKey key = new GeometryPlanKey(
         previews().geometryVersion(),
         PointerDragSnapshotView.modifierHeld(FastPlaceClientInput.currentSession()),
         eye,
         view,
         candidate,
         hoveredPoint
      );
      if (!key.equals(cachedGeometryPlanKey)) {
         cachedGeometryPlanKey = key;
         try {
            cachedGeometryPlan = GeometryWorkflows.previewPlan(
               workflowView, previews().geometry().points(), hoveredPoint, candidate, eye
            );
         } catch (RuntimeException exception) {
            logPreviewFailure("Unable to generate FastFormer geometry preview plan; using control points", exception);
            cachedGeometryPlan = GeometryPreviewPlan.controlPoints(previews().geometry().points(), hoveredPoint);
         }
      }
      GeometryPreviewPlan plan = cachedGeometryPlan;
      AxisGizmo gizmo = hoveredGeometryGizmo(plan.gizmo(), view);
      return gizmo == plan.gizmo() ? plan : plan.withGizmo(gizmo);
   }

   private static GeometryRenderLayers geometryRenderLayers() {
      GeometryPreviewPlan source = cachedGeometryPlan;
      if (source == null) {
         return GeometryRenderLayers.empty();
      }
      if (cachedGeometryRenderSource != source) {
         Set<BlockPos> controlPoints = source.controlPoints()
            .stream()
            .map(ControlPoint::pos)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
         Set<BlockPos> pendingBlocks = source.pendingBlocks();
         if (pendingBlocks.isEmpty()
            && previews().geometry().mode() == GeometryMode.CONE_PRISM
            && previews().geometry().conePlaneMode().stageFor(previews().geometry().points().size()) == ConePrismStage.BODY
            && !PointerDragSnapshotView.modifierHeld(FastPlaceClientInput.currentSession())) {
            pendingBlocks = coneBasePreviewFallback();
         }
         cachedGeometryRenderSource = source;
         cachedGeometryRenderLayers = new GeometryRenderLayers(
            PreviewGeometrySupport.withoutBlocks(source.ghostBlocks(), controlPoints),
            PreviewGeometrySupport.withoutBlocks(pendingBlocks, controlPoints)
         );
      }
      return cachedGeometryRenderLayers;
   }

   private static Set<BlockPos> coneBasePreviewFallback() {
      int facePointCount = Math.min(previews().geometry().pointLocations().size(), previews().geometry().conePlaneMode().facePointCount());
      if (facePointCount < previews().geometry().conePlaneMode().facePointCount()) {
         return Set.of();
      }
      List<Vec3> facePoints = List.copyOf(previews().geometry().pointLocations().subList(0, facePointCount));
      ConePrismParameters parameters = new ConePrismParameters(
         facePoints,
         Optional.empty(),
         previews().geometry().coneShapeVariant(),
         previews().geometry().conePlaneMode(),
         previews().geometry().coneRadius(),
         previews().geometry().coneScaleX(),
         previews().geometry().coneScaleZ(),
         previews().geometry().coneTopScaleOffset(),
         previews().geometry().coneTopOffset(),
         previews().geometry().coneRotationRadians()
      );
      return ConePrismGenerator.baseOutline(parameters, 16_384);
   }

   private static BlockPos geometryCandidatePoint(Minecraft minecraft, LocalPlayer player) {
      GeometryHit hit = geometryCandidateHit(minecraft, player);
      return hit == null ? null : hit.point();
   }

   private static GeometryHit geometryCandidateHit(Minecraft minecraft, LocalPlayer player) {
      if (!previews().geometry().active() || player == null || InteractionContext.vanillaOwnsPointer(minecraft)) {
         return null;
      }
      BlockHitResult hit = raycastBlocks(player);
      if (hit.getType() == Type.BLOCK) {
         return GeometryHit.from(hit);
      }
      List<BlockPos> points = previews().geometry().points();
      BlockPos fallback = points.isEmpty() ? BlockPos.containing(player.getEyePosition().add(player.getViewVector(1.0F).scale(8.0))) : points.getLast();
      return GeometryHit.point(fallback);
   }

   private static AxisGizmo hoveredGeometryGizmo(AxisGizmo gizmo, Vec3 view) {
      Minecraft minecraft = Minecraft.getInstance();
      LocalPlayer player = minecraft.player;
      if (gizmo == null || player == null) {
         return gizmo;
      }

      gizmo = cameraScaledGizmo(gizmo, minecraft);
      if (InteractionContext.vanillaOwnsPointer(minecraft)) {
         return gizmo;
      }
      AxisGizmo.Hit hit = gizmo.hitTest(player.getEyePosition(), view, visiblePreviewReach(player));
      AxisGizmo.HandleKey hoveredKey = hit == null ? null : hit.handle().key();
      return gizmo.withState(hoveredKey, PointerDragSnapshotView.geometryKey(FastPlaceClientInput.currentSession()));
   }

   private static AxisGizmo cameraScaledGizmo(AxisGizmo gizmo, Minecraft minecraft) {
      Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
      GizmoViewScale scale = GizmoViewScale.fromDistance(camera.distanceTo(gizmo.center()));
      return new AxisGizmo(
         gizmo.center(),
         scale.axisLength(),
         scale.handleRadius(),
         gizmo.frame(),
         gizmo.handles(),
         gizmo.textComponent()
      );
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

   private static void renderGeometryControlPoints(PoseStack poseStack, BufferSource buffers, Vec3 camera, GeometryPreviewPlan plan) {
      renderControlPoints(poseStack, buffers, camera, plan.controlPoints());
   }

   static void renderControlPoints(PoseStack poseStack, BufferSource buffers, Vec3 camera, List<ControlPoint> points) {
      new io.github.fastformer.client.controlpoint.ControlPointRenderer(
         1.0F, pendingGridDashOffset(), SELECTION_DASH_LENGTH
      ).render(poseStack, buffers, camera, points);
   }

   private static long renderedThemeRevision = -1;

   private static void renderAlternatingDashedLine(
      PoseStack poseStack, VertexConsumer consumer, Vec3 from, Vec3 to, float alpha, double offset
   ) {
      GuideRenderer.renderAlternatingDashedLine(
         poseStack, consumer, from, to, alpha, offset, SELECTION_DASH_LENGTH, 1.0F
      );
   }

   public static void clearWorldRenderState() {
      geometryTransformBaseline = null;
      OPERATION_FACE_INTERPOLATOR.reset();
      BUILDING_CACHE.resetBuildingPreviewCaches();
      cachedGeometryPlanKey = null;
      cachedGeometryPlan = null;
      cachedGeometryRenderSource = null;
      cachedGeometryRenderLayers = GeometryRenderLayers.empty();
      PreviewRenderResources.clearMeshes();
      SmoothReticlePostEffect.reset();
      smoothReticleFrame = false;
   }

   public static boolean reconnectPreviewRestorePending() {
      return previews().reconnectRestorePending();
   }

   /** Applies the server previews held at the reconnect boundary after the player confirms. */
   public static boolean confirmReconnectPreviewRestore() {
      ClientPreviewState.HeldReconnectPreviews held = previews().takeHeldReconnectPreviews();
      if (held == null) {
         return false;
      }
      if (held.buildingSession() != null) applyBuildingSession(held.buildingSession());
      if (held.buildingParameters() != null) applyBuildingParameters(held.buildingParameters());
      if (held.buildingEffect() != null) applyBuildingEffect(held.buildingEffect());
      if (held.geometry() != null) applyGeometry(held.geometry());
      return true;
   }

   public static void dismissReconnectPreviewRestore() {
      previews().dismissReconnectRestore();
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
      PreviewRenderOwner owner = PreviewRenderOwner.select(
         previews().building().active(),
         previews().operation().active(),
         ClientSessionSystem.instance().operations().active(),
         previews().geometry().active()
      );
      boolean placement = owner == PreviewRenderOwner.BUILDING || owner == PreviewRenderOwner.NONE
         && !ClientSessionSystem.instance().operations().selectionSessionActive() && !ClientSessionSystem.instance().operations().selectionEdits().selectionDraftActive()
         && io.github.fastformer.fastplace.placement.context.PlaceableItems.isPlaceable(player.getMainHandItem());
      boolean selection = owner == PreviewRenderOwner.OPERATION || ClientSessionSystem.instance().operations().selectionSessionActive()
         || ClientSessionSystem.instance().operations().selectionEdits().selectionDraftActive()
         || owner == PreviewRenderOwner.NONE && player.getMainHandItem().isEmpty();
      var pointer = ClientSessionSystem.instance().inputSession().pointer();
      return selection ? pointer.selection(player, FastPlaceClientInput.modifierHeld())
         : placement ? pointer.placement(player) : pointer.outline(player);
   }

   private static void renderConfirmedBlocks(PoseStack poseStack, BufferSource buffers, Vec3 camera, Set<BlockPos> blocks) {
      if (blocks.isEmpty()) {
         return;
      }
      boolean renderFaces = blocks.size() <= CONFIRMED_FACE_BLOCK_LIMIT;
      GhostMesh mesh = renderFaces ? CONFIRMED_GHOST_CACHE.mesh(blocks) : CONFIRMED_OUTLINE_CACHE.mesh(blocks);
      float faceAlpha = PreviewStyle.FACE_ALPHA;
      float outlineAlpha = PreviewStyle.OUTLINE_ALPHA;
      if (renderFaces) {
         renderGhostFaces(poseStack, buffers.getBuffer(GHOST_FACES), camera, mesh.faces(), inkRed(), inkGreen(), inkBlue(), faceAlpha);
         buffers.endBatch(GHOST_FACES);
      }
      renderGhostOutline(poseStack, buffers.getBuffer(GHOST_OUTLINE_LINES), camera, mesh.edges(), GeometryPalette.confirmedOutline().red(), GeometryPalette.confirmedOutline().green(), GeometryPalette.confirmedOutline().blue(), outlineAlpha);
      buffers.endBatch(GHOST_OUTLINE_LINES);
   }

   private static void renderPendingBlocks(
      PoseStack poseStack,
      BufferSource buffers,
      Vec3 camera,
      Matrix4f eventModelView,
      Matrix4f projectionMatrix,
      Set<BlockPos> blocks
   ) {
      if (blocks.isEmpty()) {
         PENDING_GHOST_CACHE.clearPreview();
         PENDING_GHOST_BUFFER_CACHE.clear();
         return;
      }
      PendingGhostMesh mesh = PENDING_GHOST_CACHE.mesh(blocks);
      if (GeometryPalette.humanist()) {
         renderPendingFallback(poseStack, buffers, camera, mesh);
         return;
      }
      VertexBuffer buffer = PENDING_GHOST_BUFFER_CACHE.buffer(mesh);
      if (buffer == null) return;
      if (FastPlaceClientShaders.pendingDashedLines() == null) {
         renderPendingFallback(poseStack, buffers, camera, mesh);
         return;
      }
      Matrix4f modelView = new Matrix4f(eventModelView).translate((float)-camera.x, (float)-camera.y, (float)-camera.z);
      float candidateAlpha = io.github.fastformer.client.render.theme.VisualThemes.value("candidate_alpha", 0.8F);
      renderPendingBuffer(buffer, PENDING_DASHED_XRAY_LINES, modelView, projectionMatrix, candidateAlpha);
      renderPendingBuffer(buffer, PENDING_DASHED_LINES, modelView, projectionMatrix, candidateAlpha);
   }

   private static void renderPendingFallback(
      PoseStack poseStack, BufferSource buffers, Vec3 camera, PendingGhostMesh mesh
   ) {
      double offset = pendingGridDashOffset();
      poseStack.pushPose();
      poseStack.translate(-camera.x, -camera.y, -camera.z);
      VertexConsumer xray = buffers.getBuffer(DYNAMIC_XRAY_LINES);
      for (PendingPreviewGrid.Segment edge : mesh.gridEdges()) {
         Vec3 from = new Vec3(edge.from().x(), edge.from().y(), edge.from().z());
         Vec3 to = new Vec3(edge.to().x(), edge.to().y(), edge.to().z());
         renderAlternatingDashedLine(poseStack, xray, from, to, PENDING_GRID_ALPHA, offset);
      }
      buffers.endBatch(DYNAMIC_XRAY_LINES);
      VertexConsumer visible = buffers.getBuffer(DYNAMIC_LINES);
      for (PendingPreviewGrid.Segment edge : mesh.gridEdges()) {
         Vec3 from = new Vec3(edge.from().x(), edge.from().y(), edge.from().z());
         Vec3 to = new Vec3(edge.to().x(), edge.to().y(), edge.to().z());
         renderAlternatingDashedLine(poseStack, visible, from, to, PENDING_GRID_ALPHA, offset);
      }
      poseStack.popPose();
      buffers.endBatch(DYNAMIC_LINES);
   }

   private static void renderPendingBuffer(
      VertexBuffer buffer,
      RenderType renderType,
      Matrix4f modelView,
      Matrix4f projectionMatrix,
      float alpha
   ) {
      ShaderInstance shader = FastPlaceClientShaders.pendingDashedLines();
      if (shader == null) {
         return;
      }
      renderType.setupRenderState();
      try {
         FastPlaceClientShaders.setPendingDashOffset((float)pendingGridDashOffset());
         RenderSystem.setShaderColor(inkRed(), inkGreen(), inkBlue(), alpha);
         buffer.bind();
         HaloLineRenderType.drawUnder(renderType, buffer, modelView, projectionMatrix);
         buffer.drawWithShader(modelView, projectionMatrix, shader);
         VertexBuffer.unbind();
      } finally {
         RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
         renderType.clearRenderState();
      }
   }

   static double pendingGridDashOffset() {
      return io.github.fastformer.client.preview.frame.ClientPreviewFrameSystem.animation()
         .dashOffset(PENDING_DASH_SPEED, SELECTION_DASH_PERIOD);
   }

   static float ghostBreathPulse() {
      return io.github.fastformer.client.preview.frame.ClientPreviewFrameSystem.animation()
         .breathPulse(GHOST_BREATH_PERIOD_NANOS);
   }

   private static void renderGhostOutline(
      PoseStack poseStack, VertexConsumer consumer, Vec3 camera, List<GhostEdge> edges, float red, float green, float blue, float alpha
   ) {
      poseStack.pushPose();
      poseStack.translate(-camera.x, -camera.y, -camera.z);
      for (GhostEdge edge : edges) {
         io.github.fastformer.client.render.geometry.PencilStroke.draw(
            poseStack,
            consumer,
            GhostOutlineDepthBias.towardCamera(edge.from().vec3(), camera, GHOST_OUTLINE_CAMERA_BIAS),
            GhostOutlineDepthBias.towardCamera(edge.to().vec3(), camera, GHOST_OUTLINE_CAMERA_BIAS),
            red,
            green,
            blue,
            alpha, false
         );
      }
      poseStack.popPose();
   }

   private static void renderGhostFaces(
      PoseStack poseStack, VertexConsumer consumer, Vec3 camera, List<GhostQuad> faces, float red, float green, float blue, float alpha
   ) {
      poseStack.pushPose();
      poseStack.translate(-camera.x, -camera.y, -camera.z);
      for (GhostQuad face : faces) {
         renderGhostFace(poseStack, consumer, face, red, green, blue, alpha);
      }
      poseStack.popPose();
   }

   private static void renderGhostFace(
      PoseStack poseStack, VertexConsumer consumer, GhostQuad face, float red, float green, float blue, float alpha
   ) {
      Vec3 normal = Vec3.atLowerCornerOf(face.direction().getNormal()).scale(GHOST_FACE_OFFSET);
      double x0 = face.x0() + normal.x;
      double y0 = face.y0() + normal.y;
      double z0 = face.z0() + normal.z;
      double x1 = face.x1() + normal.x;
      double y1 = face.y1() + normal.y;
      double z1 = face.z1() + normal.z;
      switch (face.direction()) {
         case DOWN, UP -> io.github.fastformer.client.render.geometry.PreviewQuads.write(poseStack, consumer, new Vec3(x0, y0, z0), new Vec3(x1, y0, z0), new Vec3(x1, y0, z1), new Vec3(x0, y0, z1), red, green, blue, alpha);
         case NORTH, SOUTH -> io.github.fastformer.client.render.geometry.PreviewQuads.write(poseStack, consumer, new Vec3(x0, y0, z0), new Vec3(x1, y0, z0), new Vec3(x1, y1, z0), new Vec3(x0, y1, z0), red, green, blue, alpha);
         case WEST, EAST -> io.github.fastformer.client.render.geometry.PreviewQuads.write(poseStack, consumer, new Vec3(x0, y0, z0), new Vec3(x0, y0, z1), new Vec3(x0, y1, z1), new Vec3(x0, y1, z0), red, green, blue, alpha);
      }
   }

   static void renderGuidePlaneGrid(PoseStack poseStack, VertexConsumer lineConsumer, Vec3 camera, List<GuidePlane> planes) {
      GuideRenderer.renderGeometryPlanes(poseStack, lineConsumer, camera, planes, 1.0F);
   }

   private static void renderBuildingGuidePlaneGrid(
      PoseStack poseStack, VertexConsumer lineConsumer, Vec3 camera, List<GuidePlane> planes
   ) {
      GuideRenderer.renderBuildingPlanes(poseStack, lineConsumer, camera, planes, 1.0F);
   }

   static void renderGuideLines(PoseStack poseStack, VertexConsumer consumer, Vec3 camera, List<GuideLine> lines) {
      GuideRenderer.renderGeometryLines(poseStack, consumer, camera, lines, 1.0F);
   }

   /** Boundary edges for a layer too large to shell; a single center-to-center line is dropped. */
   private static List<GuideLine> boundaryFallback(List<GuideLine> edges, boolean lightweight) {
      return lightweight && edges.size() > 1 ? edges : List.of();
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

   static VertexBuffer uploadPendingGrid(List<PendingPreviewGrid.Segment> edges) {
      long estimatedBytes = (long)edges.size() * 2L * DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL.getVertexSize();
      int initialCapacity = (int)Math.clamp(estimatedBytes, 256L, 16L * 1024L * 1024L);
      try (ByteBufferBuilder bytes = new ByteBufferBuilder(initialCapacity)) {
         BufferBuilder builder = new BufferBuilder(
            bytes,
            VertexFormat.Mode.LINES,
            DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL
         );
         for (PendingPreviewGrid.Segment edge : edges) {
            PendingPreviewGrid.Point from = edge.from();
            PendingPreviewGrid.Point to = edge.to();
            float dx = to.x() - from.x();
            float dy = to.y() - from.y();
            float dz = to.z() - from.z();
            float length = Math.abs(dx) + Math.abs(dy) + Math.abs(dz);
            if (length <= 0.0F) {
               continue;
            }
            float inverseLength = 1.0F / length;
            float nx = dx * inverseLength;
            float ny = dy * inverseLength;
            float nz = dz * inverseLength;
            builder.addVertex(from.x(), from.y(), from.z())
               .setUv(0.0F, 0.0F)
               .setColor(1.0F, 1.0F, 1.0F, 1.0F)
               .setNormal(nx, ny, nz);
            builder.addVertex(to.x(), to.y(), to.z())
               .setUv(length, 0.0F)
               .setColor(1.0F, 1.0F, 1.0F, 1.0F)
               .setNormal(nx, ny, nz);
         }
         MeshData data = builder.buildOrThrow();
         VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
         try {
            buffer.bind();
            buffer.upload(data);
            VertexBuffer.unbind();
            return buffer;
         } catch (RuntimeException | Error exception) {
            VertexBuffer.unbind();
            buffer.close();
            throw exception;
         }
      }
   }

   private static String keyName(KeyMapping mapping) {
      return mapping.getTranslatedKeyMessage().getString();
   }

}
