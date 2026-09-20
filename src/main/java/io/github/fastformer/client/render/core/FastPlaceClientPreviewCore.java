package io.github.fastformer.client.render.core;

import static io.github.fastformer.client.render.type.PreviewRenderTypes.*;
import static io.github.fastformer.client.gizmo.GizmoRenderer.operationGizmoAlpha;
import io.github.fastformer.client.gizmo.GizmoRenderer;

import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.PoseStack.Pose;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import javax.annotation.Nullable;
import io.github.fastformer.fastplace.quickshape.FaceMode;
import io.github.fastformer.fastplace.FastPlaceGeometry;
import io.github.fastformer.fastplace.FastPlaceActivity;
import io.github.fastformer.fastplace.quickshape.FastPlaceMode;
import io.github.fastformer.fastplace.quickshape.FastPlaceStage;
import io.github.fastformer.fastplace.FastPlaceStateMachine;
import io.github.fastformer.fastplace.FillMode;
import io.github.fastformer.fastplace.TranslatableText;
import io.github.fastformer.fastplace.quickshape.LineMode;
import io.github.fastformer.fastplace.LongRangeBlockRaycast;
import io.github.fastformer.fastplace.ConePlaneMode;
import io.github.fastformer.fastplace.ConePrismStage;
import io.github.fastformer.fastplace.GeometryHit;
import io.github.fastformer.fastplace.GeometryMode;
import io.github.fastformer.fastplace.geometry.OperationGeometry;
import io.github.fastformer.fastplace.geometry.PlaneAxes;
import io.github.fastformer.fastplace.selection.OperationMode;
import io.github.fastformer.fastplace.selection.OperationStageMode;
import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.input.FastPlaceClientInput;
import io.github.fastformer.client.input.InteractionContext;
import io.github.fastformer.client.input.ModifierReticleMode;
import io.github.fastformer.client.input.OperationInteractionIntent;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.selection.ClientSelectionState;
import io.github.fastformer.client.operation.selection.OccupiedBlockBounds;
import io.github.fastformer.client.operation.preview.WorkspacePreviewComposer;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.client.placement.QuickReplaceMode;
import io.github.fastformer.client.placement.effect.PlacementEffectPreview;
import io.github.fastformer.client.render.FastPlaceClientShaders;
import io.github.fastformer.client.render.PreviewBlockOcclusion;
import io.github.fastformer.client.render.WorkspacePointerPrompt;
import io.github.fastformer.client.render.WorkspacePreviewRenderer;
import io.github.fastformer.client.render.WorkspaceSubmissionHud;
import io.github.fastformer.client.render.guide.GuideRenderer;
import io.github.fastformer.client.render.hud.GeometryTextBlockRenderer;
import io.github.fastformer.client.render.hud.GizmoHudTextFormatter;
import io.github.fastformer.client.render.hud.PreviewFeedbackHud;
import io.github.fastformer.client.render.interaction.OperationPointerKind;
import io.github.fastformer.client.render.interaction.OperationPointerTarget;
import io.github.fastformer.client.render.state.ClientPreviewState;
import io.github.fastformer.client.render.model.*;
import io.github.fastformer.client.render.cache.BuildingShellCache;
import io.github.fastformer.client.render.cache.BuildingShellEdgeBuffer;
import io.github.fastformer.client.render.cache.BuildingShellFaceBuffer;
import io.github.fastformer.client.render.cache.BuildingShellBlocksCache;
import io.github.fastformer.client.render.cache.GhostMeshCache;
import io.github.fastformer.client.render.cache.PendingGhostBufferCache;
import io.github.fastformer.client.render.cache.PendingGhostMeshCache;
import io.github.fastformer.client.gizmo.GizmoViewScale;
import io.github.fastformer.client.render.GhostOutlineDepthBias;
import io.github.fastformer.client.render.OperationFaceHitInterpolator;
import io.github.fastformer.client.gizmo.OperationGizmoPresentation;
import io.github.fastformer.client.render.PendingPreviewGrid;
import io.github.fastformer.client.render.PreviewAsyncPolicy;
import io.github.fastformer.client.render.ShapeShellMesh;
import io.github.fastformer.client.render.SmoothReticlePostEffect;
import io.github.fastformer.client.render.shell.ShapeShellRenderer;
import io.github.fastformer.client.render.mesh.GhostMeshBuilder;
import io.github.fastformer.client.render.geometry.PreviewGeometrySupport;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.fastplace.selection.OperationSelectionStage;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import io.github.fastformer.fastplace.geometry.ControlPoint;
import io.github.fastformer.client.controlpoint.ControlPointPresentation;
import io.github.fastformer.fastplace.geometry.ControlPointStyle;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.GeometryAction;
import io.github.fastformer.fastplace.geometry.GeometryInteractionHit;
import io.github.fastformer.fastplace.geometry.GeometryInteractionTarget;
import io.github.fastformer.fastplace.geometry.GeometryNumbers;
import io.github.fastformer.fastplace.geometry.GeometryPreviewBlocks;
import io.github.fastformer.fastplace.geometry.GeometryRayVisibility;
import io.github.fastformer.fastplace.geometry.TransformFrame;
import io.github.fastformer.fastplace.workflow.GeometryWorkflowView;
import io.github.fastformer.fastplace.workflow.GeometryWorkflows;
import io.github.fastformer.fastplace.geometry.GizmoTextComponent;
import io.github.fastformer.fastplace.geometry.GizmoTextContext;
import io.github.fastformer.fastplace.geometry.generation.WallGenerator;
import io.github.fastformer.fastplace.geometry.generation.ConePrismGeometry;
import io.github.fastformer.fastplace.geometry.generation.ConePrismParameters;
import io.github.fastformer.fastplace.geometry.generation.ConePrismGenerator;
import io.github.fastformer.fastplace.geometry.generation.ProgressiveBlockGeneration;
import io.github.fastformer.fastplace.geometry.generation.GenerationFailed;
import io.github.fastformer.fastplace.geometry.generation.GenerationLimitExceeded;
import io.github.fastformer.fastplace.geometry.generation.LineTieBias;
import io.github.fastformer.fastplace.geometry.generation.LineGenerator;
import io.github.fastformer.fastplace.geometry.GeometryPreviewPlan;
import io.github.fastformer.fastplace.geometry.GuideLine;
import io.github.fastformer.fastplace.geometry.GuidePlane;
import io.github.fastformer.fastplace.geometry.SelectionPrism;
import io.github.fastformer.fastplace.quickshape.PointMode;
import io.github.fastformer.fastplace.quickshape.PolygonVolumeShape;
import io.github.fastformer.fastplace.PlaceableItems;
import io.github.fastformer.fastplace.SmartWoodFrame;
import io.github.fastformer.fastplace.PlacementContextSnapshot;
import io.github.fastformer.fastplace.placement.effect.ResolvedPlacementEffect;
import io.github.fastformer.fastplace.quickshape.RaycastPlacement;
import io.github.fastformer.fastplace.quickshape.VolumeMode;
import io.github.fastformer.network.payload.preview.BuildingPreviewPayload;
import io.github.fastformer.network.payload.preview.BuildingPreviewEffectPayload;
import io.github.fastformer.network.payload.preview.BuildingPreviewParametersPayload;
import io.github.fastformer.network.payload.preview.BuildingPreviewSessionPayload;
import io.github.fastformer.network.payload.operation.OperationPreviewPayload;
import io.github.fastformer.network.payload.geometry.GeometryPreviewPayload;
import io.github.fastformer.network.payload.preview.ActivityStatePayload;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.OptionalDouble;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult.Type;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.BlockGetter;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut;
import net.neoforged.neoforge.client.event.RenderGuiEvent.Post;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.joml.Matrix4f;
import org.slf4j.Logger;

@EventBusSubscriber(
   modid = "fastformer",
   value = {Dist.CLIENT}
)
public class FastPlaceClientPreviewCore {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final double PREVIEW_REACH = LongRangeBlockRaycast.MAX_REACH;
   private static final int CONFIRMED_FACE_BLOCK_LIMIT = 16000;
   static final float GHOST_RED = 1.0F;
   static final float GHOST_GREEN = 1.0F;
   static final float GHOST_BLUE = 1.0F;
   private static final float PENDING_RED = 1.0F;
   private static final float PENDING_GREEN = 1.0F;
   private static final float PENDING_BLUE = 1.0F;
   static final float GHOST_FACE_ALPHA_MIN = 0.10F;
   static final float GHOST_FACE_ALPHA_MAX = 0.20F;
   private static final long FACE_NORMAL_INTERPOLATION_NANOS = 50_000_000L;
   private static final long PREVIEW_FAILURE_LOG_INTERVAL_NANOS = 5_000_000_000L;
   static final float SELECTION_HIGHLIGHT_ALPHA = 0.42F;
   static final float GHOST_OUTLINE_ALPHA_MIN = 0.45F;
   static final float GHOST_OUTLINE_ALPHA_MAX = 0.90F;
   private static final float PENDING_GRID_ALPHA = 0.52F;
   private static final float PENDING_XRAY_ALPHA = 0.10F;
   static final float SELECTION_XRAY_ALPHA = 0.34F;
   private static final double PENDING_DASH_UNIT = 1.0 / 16.0;
   private static final double PENDING_DASH_LENGTH = PENDING_DASH_UNIT * 4.0;
   private static final double PENDING_DASH_GAP = PENDING_DASH_UNIT * 2.0;
   private static final double PENDING_DASH_SPEED = 1.0;
   private static final double SELECTION_DASH_LENGTH = PENDING_DASH_UNIT * 4.0;
   private static final double SELECTION_DASH_PERIOD = SELECTION_DASH_LENGTH * 2.0;
   private static final long GHOST_BREATH_PERIOD_NANOS = 2_400_000_000L;
   private static final double GHOST_FACE_OFFSET = 0.002;
   static final double SELECTION_FACE_INFLATE = OperationSelectionVolume.RAYCAST_INFLATE;
   private static final double GHOST_OUTLINE_CAMERA_BIAS = 0.012;
   private static final ResourceLocation CROSSHAIR_SPRITE = ResourceLocation.withDefaultNamespace("hud/crosshair");
   static final double EPSILON = 1.0E-7;
   private static final ClientPreviewState PREVIEW_STATE = new ClientPreviewState();
   /** Owns transient interaction feedback and its HUD rendering. */
   private static final PreviewFeedbackHud FEEDBACK = new PreviewFeedbackHud(FastPlaceClientPreviewCore::gizmoAxisHudColor);
   static final OperationFaceHitInterpolator OPERATION_FACE_INTERPOLATOR = new OperationFaceHitInterpolator(FACE_NORMAL_INTERPOLATION_NANOS);
   static final OperationFaceHitInterpolator WORKSPACE_FACE_INTERPOLATOR = new OperationFaceHitInterpolator(FACE_NORMAL_INTERPOLATION_NANOS);
   static float worldPreviewOpacity = 1.0F;
   private static LongRangeBlockRaycast.Result raycastDebug;
   private static LongRangeBlockRaycast.Result cachedRaycast;
   private static Vec3 cachedRaycastStart;
   private static Vec3 cachedRaycastDirection;
   private static long cachedRaycastAt;
   private static boolean cachedRaycastForPlacement;
   private static TransformStatus geometryTransformBaseline;
   private static long lastPreviewFailureLogAt;
   private static final GhostMeshCache CONFIRMED_GHOST_CACHE = new GhostMeshCache(
      blocks -> GhostMeshBuilder.build(blocks, true, true, true)
   );
   private static final GhostMeshCache CONFIRMED_OUTLINE_CACHE = new GhostMeshCache(
      blocks -> GhostMeshBuilder.build(blocks, false, true, true)
   );
   private static final BuildingShellCache CONFIRMED_BUILDING_SHELL_CACHE = BuildingShellCache.forImmutableSnapshots(false);
   private static final BuildingShellEdgeBuffer CONFIRMED_SHELL_EDGES = new BuildingShellEdgeBuffer();
   private static final BuildingShellEdgeBuffer PENDING_SHELL_EDGES = new BuildingShellEdgeBuffer();
   private static final BuildingShellFaceBuffer CONFIRMED_SHELL_FACES = new BuildingShellFaceBuffer();
   private static final BuildingShellFaceBuffer PENDING_SHELL_FACES = new BuildingShellFaceBuffer();
   private static final BuildingShellBlocksCache BUILDING_SHELL_BLOCKS_CACHE = new BuildingShellBlocksCache();
   private static final BuildingShellCache PENDING_BUILDING_SHELL_CACHE = BuildingShellCache.forImmutableSnapshots(true);
   private static final ThreadPoolExecutor PREVIEW_MESH_EXECUTOR = new ThreadPoolExecutor(
      1,
      1,
      0L,
      TimeUnit.MILLISECONDS,
      new LinkedBlockingQueue<>(),
      runnable -> {
         Thread thread = new Thread(runnable, "FastFormer preview mesh");
         thread.setDaemon(true);
         return thread;
      }
   );
   private static final ThreadPoolExecutor PREVIEW_GENERATION_EXECUTOR = new ThreadPoolExecutor(
      1,
      1,
      0L,
      TimeUnit.MILLISECONDS,
      new LinkedBlockingQueue<>(),
      runnable -> {
         Thread thread = new Thread(runnable, "FastFormer preview generation");
         thread.setDaemon(true);
         return thread;
      }
   );
   private static final PendingGhostMeshCache PENDING_GHOST_CACHE = new PendingGhostMeshCache(
      PREVIEW_MESH_EXECUTOR,
      GhostMeshBuilder::buildPending,
      failure -> logPreviewFailure("Unable to build FastFormer preview mesh; suppressing pending mesh", failure)
   );
   private static final PendingGhostBufferCache PENDING_GHOST_BUFFER_CACHE = new PendingGhostBufferCache(
      FastPlaceClientPreviewCore::uploadPendingGrid
   );
   private static BuildingPreviewPayload cachedConfirmedBuildingState;
   private static LineTieBias cachedConfirmedBuildingBias = LineTieBias.DEFAULT;
   private static Set<BlockPos> cachedConfirmedBuildingBlocks = Set.of();
   private static BuildingPreviewKey cachedBuildingPreviewKey;
   private static Set<BlockPos> cachedBuildingPreviewBlocks = Set.of();
   private static Set<BlockPos> cachedBuildingFallbackBlocks = Set.of();
   private static final BuildingPreviewGenerationOwner BUILDING_PREVIEW_GENERATION =
      new BuildingPreviewGenerationOwner();
   private static ProgressiveBlockGeneration cachedBuildingPreviewProgress;
   private static boolean cachedBuildingPreviewAtLimit;
   private static long cachedBuildingPreviewResultVersion;
   private static BuildingRenderKey cachedBuildingRenderKey;
   private static BuildingRenderFrame cachedBuildingRenderFrame = BuildingRenderFrame.empty();
   private static GeometryPlanKey cachedGeometryPlanKey;
   private static GeometryPreviewPlan cachedGeometryPlan;
   private static GeometryPreviewPlan cachedGeometryRenderSource;
   private static GeometryRenderLayers cachedGeometryRenderLayers = GeometryRenderLayers.empty();
   private static boolean smoothReticleFrame;
   private static final PreviewSessionLifecycle WORLD_SESSION_LIFECYCLE = new PreviewSessionLifecycle(
      new PreviewSessionLifecycle.StateOwner() {
         @Override
         public void endInputSession() {
            FastPlaceClientInput.endWorldSession();
         }

         @Override
         public void clearSourceMask() {
            ClientOperationController.sourceMask().clear();
         }

         @Override
         public void resetPreviewSession() {
            PREVIEW_STATE.resetConnection();
         }

         @Override
         public void disconnectOperation() {
            ClientOperationController.onDisconnected();
         }

         @Override
         public void clearInteractionCache() {
            WorkspaceInteractionResolver.clearCache();
         }

         @Override
         public void clearFeedback() {
            FEEDBACK.clear();
         }

         @Override
         public void clearWorldRenderState() {
            FastPlaceClientPreviewCore.clearWorldRenderState();
         }
      }
   );

   protected FastPlaceClientPreviewCore() {
   }

   public static void applyBuilding(BuildingPreviewPayload payload) {
      PREVIEW_STATE.applyBuilding(payload);
      resetBuildingPreviewCaches();
   }

   public static java.util.Optional<io.github.fastformer.client.quickshape.QuickShapeSubmissionSnapshot> buildingSubmission() {
      return PREVIEW_STATE.buildingSubmission();
   }

   public static void applyQuickShapeSubmissionParameters(
      io.github.fastformer.network.payload.preview.QuickShapeSubmissionParametersPayload payload
   ) {
      PREVIEW_STATE.applyQuickShapeSubmissionParameters(payload);
   }

   public static java.util.Optional<io.github.fastformer.network.payload.preview.QuickShapeSubmissionParametersPayload> submissionParameters(
      io.github.fastformer.client.quickshape.QuickShapeSubmissionSnapshot snapshot
   ) {
      return PREVIEW_STATE.submissionParameters(snapshot);
   }

   public static void applyBuildingSession(BuildingPreviewSessionPayload payload) {
      if (PREVIEW_STATE.holdReconnectBuildingSession(payload)) {
         return;
      }
      if (PREVIEW_STATE.applyBuildingSession(payload)) {
         resetBuildingPreviewCaches();
      }
   }

   public static void applyBuildingParameters(BuildingPreviewParametersPayload payload) {
      if (PREVIEW_STATE.holdReconnectBuildingParameters(payload)) {
         return;
      }
      if (PREVIEW_STATE.applyBuildingParameters(payload)) {
         resetBuildingPreviewCaches();
      }
   }

   public static void applyBuildingEffect(BuildingPreviewEffectPayload payload) {
      if (PREVIEW_STATE.holdReconnectBuildingEffect(payload)) {
         return;
      }
      if (PREVIEW_STATE.applyBuildingEffect(payload)) {
         resetBuildingPreviewCaches();
      }
   }

   private static void resetBuildingPreviewCaches() {
      cachedConfirmedBuildingState = null;
      cachedConfirmedBuildingBias = LineTieBias.DEFAULT;
      cachedConfirmedBuildingBlocks = Set.of();
      cachedBuildingPreviewKey = null;
      cachedBuildingPreviewBlocks = Set.of();
      cancelBuildingPreviewGeneration();
      cachedBuildingRenderKey = null;
      cachedBuildingRenderFrame = BuildingRenderFrame.empty();
      clearBuildingShellCaches();
   }

   private static void clearBuildingShellCaches() {
      CONFIRMED_BUILDING_SHELL_CACHE.clear();
      PENDING_BUILDING_SHELL_CACHE.clear();
      BUILDING_SHELL_BLOCKS_CACHE.clear();
      CONFIRMED_SHELL_FACES.clear();
      PENDING_SHELL_FACES.clear();
      CONFIRMED_SHELL_EDGES.clear();
      PENDING_SHELL_EDGES.clear();
   }

   public static void applyOperation(OperationPreviewPayload payload) {
      if (ClientOperationController.synchronize(payload)) {
         PREVIEW_STATE.applyOperation(payload);
      }
   }

   public static void applyGeometry(GeometryPreviewPayload payload) {
      if (PREVIEW_STATE.holdReconnectGeometry(payload)) {
         return;
      }
      GeometryPreviewPayload previous = PREVIEW_STATE.geometry();
      if (!PREVIEW_STATE.applyGeometry(payload)) {
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
      if (PREVIEW_STATE.applyActivity(payload)) {
         ClientOperationController.observeServerActivity(payload.activity());
      }
   }

   /** Ends a reconnect boundary once the snapshot that followed it has settled. */
   public static void onClientTick() {
      PREVIEW_STATE.tickReconnectBoundary();
      Minecraft minecraft = Minecraft.getInstance();
      // Hover acceleration and transient HUD feedback belong to the focused
      // world view. Do not carry either across a menu, pause screen, or world
      // boundary where the crosshair is no longer selecting a block.
      WORLD_SESSION_LIFECYCLE.clearFeedbackWithoutFocusedWorld(
         minecraft.player != null && minecraft.level != null && minecraft.screen == null
      );
   }

   /** Changes whenever a server preview or activity snapshot is applied. */
   public static long stateRevision() {
      return PREVIEW_STATE.revision();
   }

   public static void noteScrollFeedback() {
      long now = System.nanoTime();
      FEEDBACK.noteScroll(PREVIEW_STATE.building(), now);
   }

   /** Clears HUD and hover feedback when the input state cancels a session. */
   public static void clearTransientFeedback() {
      FEEDBACK.clear();
   }

   public static void noteGizmoFeedback(
      AxisGizmo.Axis axis, AxisGizmo.Operation operation, int steps, double baseValue
   ) {
      FEEDBACK.noteGizmo(axis, operation, steps,
         GeometryNumbers.finiteOr(baseValue, operation == AxisGizmo.Operation.SCALE ? 1.0 : 0.0), System.nanoTime());
   }

   public static boolean active() {
      return PREVIEW_STATE.building().active();
   }

   public static boolean enabled() {
      return PREVIEW_STATE.building().enabled();
   }

   public static boolean middleConfirmEnabled() {
      return PREVIEW_STATE.building().middleConfirmEnabled();
   }

   public static boolean buildingMiddleClickIgnored() {
      BuildingPreviewPayload snapshot = PREVIEW_STATE.building();
      return io.github.fastformer.fastplace.quickshape.QuickShapeInputRules.ignoresMiddleClick(
         snapshot.faceMode(), snapshot.points().size(), snapshot.polygonClosed()
      );
   }

   public static boolean taskActive() {
      return PREVIEW_STATE.activity().task();
   }

   public static FastPlaceActivity activity() {
      return PREVIEW_STATE.activity();
   }

   public static boolean activityCancellable() {
      return PREVIEW_STATE.activity().cancellable();
   }

   public static boolean operationActive() {
      return PREVIEW_STATE.operation().active()
         || ClientOperationController.active()
         || ClientOperationController.selectionSessionActive();
   }

   public static boolean operationNeedsSecond() {
      return PREVIEW_STATE.operation().active() && !PREVIEW_STATE.operation().hasSecond();
   }

   public static boolean operationNeedsFirst() {
      return PREVIEW_STATE.operation().active() && !PREVIEW_STATE.operation().hasFirst();
   }

   public static boolean operationSelectionReady() {
      return ClientOperationController.operationSelectionReady();
   }

   public static boolean operationSelectionConfirmed() {
      return ClientOperationController.operationSelectionConfirmed();
   }

   public static boolean operationAdjustmentStarted() {
      return ClientOperationController.operationAdjustmentStarted();
   }

   public static int operationPointCount() {
      return PREVIEW_STATE.operation().active() ? PREVIEW_STATE.operation().points().size() : 0;
   }

   public static boolean operationCuboid() {
      return ClientOperationController.operationCuboid();
   }

   public static boolean operationPrism() {
      return ClientOperationController.operationPrism();
   }

   public static boolean operationPrismBaseOpen() {
      return PREVIEW_STATE.operation().active()
         && PREVIEW_STATE.operation().operationSelectionMode() == OperationSelectionMode.PRISM
         && PREVIEW_STATE.operation().operationPrismBasePointCount() == 0;
   }

   public static boolean operationPointSelected() {
      return PREVIEW_STATE.operation().active()
         && PREVIEW_STATE.operation().operationSelectedPointIndex() >= 0
         && PREVIEW_STATE.operation().operationSelectedPointIndex() < PREVIEW_STATE.operation().points().size();
   }

   public static int operationSelectedPointIndex() {
      return operationPointSelected() ? PREVIEW_STATE.operation().operationSelectedPointIndex() : -1;
   }

   public static Vec3 operationPointCenter(int index) {
      return PREVIEW_STATE.operation().active() && index >= 0 && index < PREVIEW_STATE.operation().points().size()
         ? Vec3.atCenterOf(PREVIEW_STATE.operation().points().get(index))
         : null;
   }

   public static int operationPointUnderCrosshairIndex() {
      if (!PREVIEW_STATE.operation().active() || operationSelectionConfirmed() || PREVIEW_STATE.operation().points().isEmpty()) {
         return -1;
      }
      BlockPos point = pointUnderCrosshair(PREVIEW_STATE.operation().points(), true);
      return point == null ? -1 : PREVIEW_STATE.operation().points().indexOf(point);
   }

   public static SelectionPrism.EdgeInsertion operationPrismEdgeInsertion() {
      Minecraft minecraft = Minecraft.getInstance();
      LocalPlayer player = minecraft.player;
      if (!operationPrism() || operationSelectionConfirmed() || FastPlaceClientInput.modifierHeld() || player == null || PREVIEW_STATE.operation().points().size() < 2
         || operationPointUnderCrosshairIndex() >= 0) {
         return null;
      }
      raycastBlocks(player);
      return SelectionPrism.resolveEdgeInsertion(
         PREVIEW_STATE.operation().points(),
         PREVIEW_STATE.operation().operationPrismBasePointCount(),
         player.getEyePosition(),
         player.getViewVector(1.0F),
         raycastDebug == null ? 0.0 : raycastDebug.distance()
      );
   }

   public static SelectionPrism.GridPlane operationPointGridPlane(int pointIndex) {
      if (!operationPrism() || pointIndex < 0 || pointIndex >= PREVIEW_STATE.operation().points().size()) {
         return null;
      }
      int baseCount = PREVIEW_STATE.operation().operationPrismBasePointCount() >= 3
         ? PREVIEW_STATE.operation().operationPrismBasePointCount()
         : PREVIEW_STATE.operation().points().size();
      return pointIndex < baseCount && baseCount >= 3
         ? SelectionPrism.gridPlane(PREVIEW_STATE.operation().points().subList(0, baseCount))
         : null;
   }

   public static SelectionPrism.GridLine operationPointGridLine(int pointIndex) {
      if (!operationPrism() || pointIndex < 0 || pointIndex >= PREVIEW_STATE.operation().points().size()) {
         return null;
      }
      int closedBaseCount = PREVIEW_STATE.operation().operationPrismBasePointCount();
      int baseCount = closedBaseCount >= 3 ? closedBaseCount : PREVIEW_STATE.operation().points().size();
      if (baseCount < 3 || pointIndex != 0 && (closedBaseCount < 3 || pointIndex < baseCount)) {
         return null;
      }
      return SelectionPrism.heightGridLine(PREVIEW_STATE.operation().points().subList(0, baseCount));
   }

   public static BlockPos operationCandidatePoint() {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.player == null || operationSelectionConfirmed() || FastPlaceClientInput.modifierHeld()) {
         return null;
      }
      BlockHitResult hit = raycastBlocks(minecraft.player);
      if (operationPrismBaseOpen() && PREVIEW_STATE.operation().points().size() >= 3) {
         return SelectionPrism.resolveBasePlanePoint(
            PREVIEW_STATE.operation().points(),
            minecraft.player.getEyePosition(),
            minecraft.player.getViewVector(1.0F),
            raycastDebug == null ? 0.0 : raycastDebug.distance()
         );
      }
      if (operationWaitingForPrismHeight()) {
         int baseCount = PREVIEW_STATE.operation().operationPrismBasePointCount();
         return SelectionPrism.resolveHeightPoint(
            PREVIEW_STATE.operation().points().subList(0, baseCount),
            minecraft.player.getEyePosition(),
            minecraft.player.getViewVector(1.0F),
            raycastDebug == null ? 0.0 : raycastDebug.distance()
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
      return PREVIEW_STATE.operation().active()
         && PREVIEW_STATE.operation().operationSelectionMode() == OperationSelectionMode.PRISM
         && PREVIEW_STATE.operation().operationPrismBasePointCount() >= 3
         && PREVIEW_STATE.operation().points().size() == PREVIEW_STATE.operation().operationPrismBasePointCount();
   }

   public static AxisGizmo operationGizmo() {
      if (ClientOperationController.active()) {
         return null;
      }
      OperationSelectionVolume selection = operationSelection();
      Minecraft minecraft = Minecraft.getInstance();
      if (selection == null
         || !operationSelectionReady()
         || minecraft.player == null) {
         return null;
      }
      AxisGizmo.Operation[] operations = OperationGizmoPresentation.operations(PREVIEW_STATE.operation().operationStageMode())
         .toArray(AxisGizmo.Operation[]::new);
      if (operations.length == 0) {
         return null;
      }
      AABB bounds = selection.bounds();
      Vec3 center = bounds.getCenter();
      BlockPos minDisplacement = OperationGeometry.stackDisplacement(bounds, PREVIEW_STATE.operation().operationStackMin());
      BlockPos maxDisplacement = OperationGeometry.stackDisplacement(bounds, PREVIEW_STATE.operation().operationStackMax());
      center = center.add(
         (minDisplacement.getX() + maxDisplacement.getX()) * 0.5,
         (minDisplacement.getY() + maxDisplacement.getY()) * 0.5,
         (minDisplacement.getZ() + maxDisplacement.getZ()) * 0.5
      ).add(Vec3.atLowerCornerOf(PREVIEW_STATE.operation().operationTranslation()));
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
      return gizmo.withState(hovered, FastPlaceClientInput.operationGizmoDragKey());
   }

   public static double operationGizmoValue(AxisGizmo.Axis axis, AxisGizmo.Operation operation) {
      if (!operationSelectionReady() || axis == null || operation == null) {
         return 0.0;
      }
      return switch (operation) {
         case MOVE -> axisComponent(Vec3.atLowerCornerOf(PREVIEW_STATE.operation().operationTranslation()), axis);
         case SCALE -> axisComponent(Vec3.atLowerCornerOf(PREVIEW_STATE.operation().operationStackVector()), axis);
         case ROTATE -> Math.toDegrees(axisComponent(PREVIEW_STATE.operation().operationRotation(), axis));
      };
   }

   public static AxisGizmo.Hit operationGizmoHit() {
      OperationInteractionIntent.Gizmo workspaceTarget = operationWorkspaceGizmoHit();
      if (ClientOperationController.active()) {
         return workspaceTarget == null ? null : workspaceTarget.hit();
      }
      AxisGizmo gizmo = operationGizmo();
      Minecraft minecraft = Minecraft.getInstance();
      return gizmo == null || minecraft.player == null
         ? null
         : gizmo.hitTest(minecraft.player.getEyePosition(), minecraft.player.getViewVector(1.0F), PREVIEW_REACH);
   }

   public static Optional<OperationInteractionIntent> operationInteractionIntent() {
      return WorkspaceInteractionResolver.resolveCurrentIntent();
   }

   public static OperationInteractionIntent.Gizmo operationWorkspaceGizmoHit() {
      return WorkspaceInteractionResolver.currentGizmo();
   }

   public static OperationGeometry.RayHit operationFaceHit() {
      if (!operationCuboid() || operationSelectionConfirmed() || FastPlaceClientInput.modifierHeld()) {
         return null;
      }
      OperationPointerTarget target = operationPointerTarget();
      return target.kind() == OperationPointerKind.FACE ? target.face() : null;
   }

   static OperationGeometry.RayHit operationFaceTarget(OperationSelectionVolume selection) {
      OperationGeometry.RayHit dragged = FastPlaceClientInput.operationFaceDragHit();
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
      return PREVIEW_STATE.geometry().active();
   }

   public static boolean geometryAwaitingFirstPoint() {
      return PREVIEW_STATE.geometry().active() && PREVIEW_STATE.geometry().points().isEmpty();
   }

   public static boolean geometryPointSelected() {
      return PREVIEW_STATE.geometry().active() && PREVIEW_STATE.geometry().selectedPointIndex() >= 0;
   }

   public static boolean buildingRaycastSubmodeAvailable() {
      if (!PREVIEW_STATE.building().enabled()) {
         return false;
      }
      if (!PREVIEW_STATE.building().active()) {
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
      if (!PREVIEW_STATE.geometry().active() || player == null || InteractionContext.nearVanillaBlock(minecraft)) {
         return null;
      }
      Vec3 eye = player.getEyePosition();
      Vec3 view = player.getViewVector(1.0F);
      GeometryWorkflowView workflowView = geometryWorkflowView(view);
      List<GeometryInteractionTarget> targets = GeometryWorkflows.get(PREVIEW_STATE.geometry().mode()).interactionTargets(workflowView);
      return GeometryInteractionHit.nearest(eye, view, visiblePreviewReach(player), targets);
   }

   public static boolean embeddedModifierReticle() {
      if (FastPlaceClientInput.modifierHeld() && PREVIEW_STATE.geometry().active()) {
         return PREVIEW_STATE.geometry().mode() == GeometryMode.WALL;
      }
      return !PREVIEW_STATE.geometry().active()
         && !PREVIEW_STATE.operation().active()
         && buildingRaycastSubmode()
         && FastPlaceClientInput.modifierHeld();
   }

   public static boolean halfGridModifierReticle() {
      if (buildingRaycastSubmode()) {
         return false;
      }
      if (!PREVIEW_STATE.geometry().active()) {
         return false;
      }
      return switch (PREVIEW_STATE.geometry().mode()) {
         case POLYHEDRON -> !PREVIEW_STATE.geometry().closed();
         case CONE_PRISM -> PREVIEW_STATE.geometry().conePlaneMode().stageFor(PREVIEW_STATE.geometry().points().size()) != ConePrismStage.ADJUST;
         case CONVEX_POLYHEDRON -> true;
         default -> false;
      };
   }

   public static AxisGizmo.Hit geometryGizmoHit() {
      Minecraft minecraft = Minecraft.getInstance();
      LocalPlayer player = minecraft.player;
      if (!PREVIEW_STATE.geometry().active() || player == null || InteractionContext.nearVanillaBlock(minecraft)) {
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

   public static double geometryGizmoValue(AxisGizmo.Axis axis, AxisGizmo.Operation operation) {
      Minecraft minecraft = Minecraft.getInstance();
      LocalPlayer player = minecraft.player;
      if (axis == null || operation == null || player == null || !PREVIEW_STATE.geometry().active()) {
         return operation == AxisGizmo.Operation.SCALE ? 1.0 : 0.0;
      }
      GeometryPreviewPlan plan = geometryPreviewPlan(player.getViewVector(1.0F));
      return geometryGizmoValue(plan, axis, operation);
   }

   private static AxisGizmo geometryGizmo(Vec3 view) {
      Minecraft minecraft = Minecraft.getInstance();
      if (!PREVIEW_STATE.geometry().active() || minecraft.player == null || InteractionContext.nearVanillaBlock(minecraft)) {
         return null;
      }
      return geometryPreviewPlan(view).gizmo();
   }

   public static BlockPos geometryPointUnderCrosshair() {
      if (!PREVIEW_STATE.geometry().active()
         || PREVIEW_STATE.geometry().mode() != GeometryMode.WALL
         || PREVIEW_STATE.geometry().closed()
         || PREVIEW_STATE.geometry().points().isEmpty()) {
         return null;
      }
      return pointUnderCrosshair(List.of(PREVIEW_STATE.geometry().points().getFirst()));
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
      if (PREVIEW_STATE.geometry().active()) {
         return geometryCandidatePoint(minecraft, player);
      }
      if (PREVIEW_STATE.operation().active()) {
         return operationCandidatePoint();
      }
      return PREVIEW_STATE.building().active() ? buildingCandidatePoint(PREVIEW_STATE.building(), player) : null;
   }

   private static List<BlockPos> closablePathPoints(int minimumPoints) {
      if (operationPrismBaseOpen() && PREVIEW_STATE.operation().points().size() >= minimumPoints) {
         return PREVIEW_STATE.operation().points();
      }
      if (PREVIEW_STATE.geometry().active()
         && PREVIEW_STATE.geometry().mode() == GeometryMode.WALL
         && !PREVIEW_STATE.geometry().closed()
         && PREVIEW_STATE.geometry().points().size() >= minimumPoints) {
         return PREVIEW_STATE.geometry().points();
      }
      if (PREVIEW_STATE.building().active()
         && PREVIEW_STATE.building().faceMode() == FaceMode.POLYGON
         && !PREVIEW_STATE.building().polygonClosed()
         && PREVIEW_STATE.building().points().size() >= minimumPoints) {
         return PREVIEW_STATE.building().points();
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
      BuildingPreviewPayload snapshot = PREVIEW_STATE.building();
      if (player == null || minecraft.level == null || !snapshot.active() || FastPlaceGeometry.stageFor(snapshot.points()) != FastPlaceStage.LINE) {
         return null;
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
          effectiveBuildingModes(snapshot)
      );
   }

   public static AABB operationBounds() {
      OperationSelectionVolume selection = operationSelection();
      return selection == null ? null : selection.bounds();
   }

   public static OperationSelectionVolume operationSelection() {
      OperationPreviewPayload snapshot = PREVIEW_STATE.operation();
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
      if (!PREVIEW_STATE.building().enabled()) {
         return false;
      } else {
         FastPlaceStage stage = PREVIEW_STATE.building().active() ? FastPlaceGeometry.stageFor(PREVIEW_STATE.building().points()) : FastPlaceStage.POINT;
         return stage == FastPlaceStage.LINE && PREVIEW_STATE.building().lineMode() == LineMode.FREE_SCROLL;
      }
   }

   public static boolean usesScrollContext() {
      if (PREVIEW_STATE.geometry().active()) {
         return geometryAllows(GeometryAction.SCALAR_ADJUST);
      } else if (PREVIEW_STATE.operation().active()) {
         return false;
      } else if (!PREVIEW_STATE.building().active()) {
         return false;
      } else {
         FastPlaceStage stage = effectiveStage(PREVIEW_STATE.building());
         return usesAngleDistance()
            || stage == FastPlaceStage.FACE && PREVIEW_STATE.building().faceMode() == FaceMode.PARALLELOGRAM_BASE_PLANE
            || stage == FastPlaceStage.VOLUME && FastPlaceGeometry.usesVolumeOffset(PREVIEW_STATE.building().modes());
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
      if (InteractionContext.nearVanillaBlock(minecraft) || !reticleNeeded) {
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
      BuildingPreviewPayload snapshot = PREVIEW_STATE.building();
      boolean persistentFreeScroll = FEEDBACK.refreshFreeScrollSession(PREVIEW_STATE.building(), System.nanoTime());
      boolean reconnectRestorePending = ClientOperationController.reconnectRestorePending()
         || reconnectPreviewRestorePending();
      // A local submission locks the workspace before the server reports its task.
      // The HUD must stay alive in that window, or the player sees no reason for
      // the refused clicks. See WorkspaceSubmissionHud.
      boolean workspaceSubmissionPending = ClientOperationController.workspaceSubmissionPending();
      WorkspaceSubmissionHud.Plan submissionPlan = WorkspaceSubmissionHud.plan(
         workspaceSubmissionPending,
         PREVIEW_STATE.activity().task(),
         QuickReplaceMode.active(),
         reconnectRestorePending
      );
      if (!WorkspaceSubmissionHud.hudActive(
         reconnectRestorePending,
         snapshot.enabled(),
         persistentFreeScroll,
         PREVIEW_STATE.geometry().active(),
         operationActive(),
         PREVIEW_STATE.activity().task(),
         QuickReplaceMode.active(),
         workspaceSubmissionPending
      )) {
         restoreVanillaCrosshairIfNeeded(graphics);
         smoothReticleFrame = false;
         return;
      }

      Vec3 view = minecraft.player == null ? new Vec3(0.0, 0.0, 1.0) : minecraft.player.getViewVector(1.0F);
      RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
      try {

      Component fillModeName = Component.translatable(
         (PREVIEW_STATE.geometry().active() ? PREVIEW_STATE.geometry().fillMode() : snapshot.fillMode()).translationKey()
      );
      // The lock blocks the block key, so a pending frame names the fill mode
      // only. The status text keeps the fill information and promises no key.
      String fillKey = WorkspaceSubmissionHud.fillModeKey(workspaceSubmissionPending);
      MutableComponent fill = (WorkspaceSubmissionHud.FILL_MODE_STATUS_KEY.equals(fillKey)
            ? Component.translatable(fillKey, fillModeName)
            : Component.translatable(fillKey, fillModeName, keyName(minecraft.options.keySwapOffhand)))
         .withStyle(ChatFormatting.AQUA);
      graphics.drawString(minecraft.font, fill, 8, 8, -1, true);
      if (QuickReplaceMode.active()) {
         graphics.drawString(minecraft.font, Component.translatable("fastformer.quick_replace.active"), 8, 20, 0xFF80E8FF, true);
      }

      MutableComponent embeddedHint = buildingRaycastHint();
      if (embeddedHint != null && minecraft.screen == null
         && !PREVIEW_STATE.activity().task() && !submissionPlan.suppressActionPrompts()) {
         int x = Math.max(8, graphics.guiWidth() - minecraft.font.width(embeddedHint) - 8);
         graphics.drawString(minecraft.font, embeddedHint, x, 8, 0xFFAAAAAA, true);
      }
      if (PREVIEW_STATE.activity().task()) {
         MutableComponent task = Component.translatable(PREVIEW_STATE.activity().translationKey()).withStyle(ChatFormatting.GREEN);
         // The cancel key reports the pending state instead of cancelling, so an
         // exit hint on this line would be false while the submission waits.
         if (WorkspaceSubmissionHud.showsCancelHint(PREVIEW_STATE.activity(), workspaceSubmissionPending)) {
            String hintKey = "fastformer.activity.cancel_hint";
            task.append(Component.literal(" ").append(Component.translatable(hintKey)).withStyle(ChatFormatting.GRAY));
         }
         graphics.drawString(minecraft.font, task, 8, 20, -1, true);
      } else if (submissionPlan.showsWaitingLine()) {
         // The locked workspace refuses every build and selection action. Explain
         // the wait instead of leaving the player without a reason.
         graphics.drawString(minecraft.font,
            Component.translatable(submissionPlan.waitingKey()).withStyle(ChatFormatting.YELLOW),
            8, submissionPlan.waitingLineY(), -1, true);
      }
      if (reconnectRestorePending) {
         graphics.drawString(minecraft.font,
            Component.translatable("fastformer.message.reconnect_restore_prompt"), 8, 32, 0xFFFFD166, true);
      }

      GeometryPreviewPlan geometryPlan = null;
      if (!PREVIEW_STATE.activity().task()) {
         geometryPlan = renderSessionHud(graphics, minecraft, view, persistentFreeScroll,
            submissionPlan.suppressActionPrompts());
      } else if (persistentFreeScroll) {
         // Wheel updates can briefly publish a task snapshot. Keep the latched
         // free-scroll coordinates visible while the session HUD is suppressed.
         renderScrollFeedbackBottom(graphics, minecraft, persistentFreeScroll);
      }
      renderCrosshairHud(graphics, minecraft, geometryPlan);
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
      return PREVIEW_STATE.geometry().active()
         && GeometryWorkflows.allows(geometryWorkflowView(new Vec3(0.0, 0.0, 1.0)), action);
   }

   private static GeometryPreviewPlan renderSessionHud(
      GuiGraphics graphics, Minecraft minecraft, Vec3 view, boolean persistentFreeScroll,
      boolean suppressActionPrompts
   ) {
      if (PREVIEW_STATE.geometry().active()) {
         GeometryPreviewPlan geometryPlan = geometryPreviewPlan(view);
         GeometryTextBlockRenderer.render(graphics, minecraft, geometryPlan);
         renderScrollFeedbackBottom(graphics, minecraft, persistentFreeScroll);
         return geometryPlan;
      }

      // A locked workspace refuses the confirm key and the pointer actions, so the
      // next-step hint must stop. The mode line stays: it reports which stage and
      // mode the workspace is in, which the player still owns during the wait.
      MutableComponent primary;
      MutableComponent secondary = null;
      if (operationActive()) {
         primary = operationBottomStatus();
      } else {
         primary = buildingBottomStatus();
         secondary = suppressActionPrompts ? null : buildingBottomHint();
      }
      renderScrollFeedbackBottom(graphics, minecraft, persistentFreeScroll);
      if (primary != null && !primary.getString().isBlank()) {
         int bottomOffset = operationActive() ? 48 : 64;
         graphics.drawCenteredString(
            minecraft.font, primary, graphics.guiWidth() / 2, graphics.guiHeight() - bottomOffset, -1
         );
      }
      if (secondary != null && !secondary.getString().isBlank()) {
         graphics.drawCenteredString(minecraft.font, secondary, graphics.guiWidth() / 2, graphics.guiHeight() - 50, -1);
      }
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
      if (!geometryAdjusting(PREVIEW_STATE.geometry())) {
         return null;
      }
      if (PREVIEW_STATE.geometry().mode() == GeometryMode.CONE_PRISM) {
         ConePrismGeometry geometry = coneGeometry(PREVIEW_STATE.geometry());
         if (geometry == null || !geometry.heightReady()) {
            return null;
         }
         Vec3 center = geometry.base().center().add(geometry.topCenter()).scale(0.5);
         return new TransformStatus(
            center,
            new Vec3(geometry.scaleX(), Math.max(0.5, Math.abs(geometry.height())), geometry.scaleZ()),
            new Vec3(0.0, Math.toDegrees(PREVIEW_STATE.geometry().coneRotationRadians()), 0.0)
         );
      }
      if (PREVIEW_STATE.geometry().mode() == GeometryMode.POLYHEDRON) {
         Vec3 center = transformCenter(geometryPlan);
         if (center == null) {
            return null;
         }
         Vec3 scale = PREVIEW_STATE.geometry().polyhedronGizmoLocal()
            ? PREVIEW_STATE.geometry().polyhedronLocalScale()
            : PREVIEW_STATE.geometry().polyhedronWorldScale();
         return new TransformStatus(center, scale, TransformStatus.eulerDegrees(PREVIEW_STATE.geometry().rotation()));
      }
      return null;
   }

   private static Vec3 transformCenter(GeometryPreviewPlan geometryPlan) {
      if (geometryPlan != null && geometryPlan.gizmo() != null) {
         return geometryPlan.gizmo().center();
      }
      return PREVIEW_STATE.geometry().pointLocations().isEmpty() ? null : PREVIEW_STATE.geometry().pointLocations().getFirst();
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

   private static double axisComponent(Vec3 value, AxisGizmo.Axis axis) {
      return switch (axis) {
         case X -> value.x;
         case Y -> value.y;
         case Z -> value.z;
      };
   }

   private static double geometryGizmoValue(
      GeometryPreviewPlan geometryPlan, AxisGizmo.Axis axis, AxisGizmo.Operation operation
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

   private static Vec3 axisVector(AxisGizmo.Axis axis) {
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
      if (!PREVIEW_STATE.building().active()) {
         return null;
      }

      FastPlaceStage stage = effectiveStage(PREVIEW_STATE.building());
      MutableComponent status = Component.translatable(stage.translationKey()).withStyle(ChatFormatting.WHITE);
      status.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY));
      if (PREVIEW_STATE.building().polygonClosed()) {
         PolygonVolumeShape selected = PREVIEW_STATE.building().polygonVolumeShape();
         PolygonVolumeShape[] modes = PolygonVolumeShape.values();
         for (int index = 0; index < modes.length; index++) {
            if (index > 0) {
                status.append(Component.literal(" / ").withStyle(ChatFormatting.DARK_GRAY));
            }
            appendBuildingMode(status, modes[index], modes[index] == selected);
         }
      } else {
         List<? extends FastPlaceMode> modes = FastPlaceStateMachine.allowedModes(
            stage,
            PREVIEW_STATE.building().lineMode(),
            PREVIEW_STATE.building().faceMode()
         );
         FastPlaceMode selected = selectedMode(PREVIEW_STATE.building(), stage);
         for (int index = 0; index < modes.size(); index++) {
            if (index > 0) {
                status.append(Component.literal(" / ").withStyle(ChatFormatting.DARK_GRAY));
            }
            FastPlaceMode mode = modes.get(index);
            appendBuildingMode(status, mode, mode == selected);
         }
      }
      return status;
   }

   private static void appendBuildingMode(MutableComponent status, TranslatableText mode, boolean selected) {
      status.append(Component.translatable(mode.translationKey()).withStyle(selected ? ChatFormatting.GREEN : ChatFormatting.GRAY));
   }

   private static MutableComponent buildingRaycastHint() {
      if (PREVIEW_STATE.geometry().active()
         || PREVIEW_STATE.operation().active()
         || !buildingRaycastSubmodeAvailable()
         || !FastPlaceClientInput.modifierHeld()
         || PREVIEW_STATE.building().raycastPlacement() == RaycastPlacement.EMBEDDED) {
         return null;
      }
      return Component.translatable("fastformer.hud.raycast_embedded_hint");
   }

   private static boolean buildingRaycastSubmode() {
      if (PREVIEW_STATE.geometry().active() || PREVIEW_STATE.operation().active()) {
         return false;
      }
      return switch (effectiveStage(PREVIEW_STATE.building())) {
         case POINT -> PREVIEW_STATE.building().pointMode() == PointMode.RAYCAST;
         case LINE -> PREVIEW_STATE.building().lineMode() == LineMode.RAYCAST;
         default -> false;
      };
   }

   private static MutableComponent buildingBottomHint() {
      if (!PREVIEW_STATE.building().active()) {
         return null;
      }
      FastPlaceStage stage = effectiveStage(PREVIEW_STATE.building());
      if (stage == FastPlaceStage.FACE && PREVIEW_STATE.building().faceMode() == FaceMode.POLYGON && !PREVIEW_STATE.building().polygonClosed()) {
         return Component.translatable("fastformer.message.polygon_close_hint").withStyle(ChatFormatting.GRAY);
      }
      return Component.translatable(
         stage == FastPlaceStage.VOLUME
            ? PREVIEW_STATE.building().polygonClosed()
               ? "fastformer.message.building_hint_height"
               : "fastformer.message.building_hint_points"
            : "fastformer.message.building_hint_points"
      ).withStyle(ChatFormatting.GRAY);
   }

   private static MutableComponent operationBottomStatus() {
      if (ClientOperationController.active()) {
         // Local workspaces expose handles directly, without a server stage mode.
         return Component.translatable(ClientOperationController.selectionDraftActive()
            ? "fastformer.hud.selection_label" : "fastformer.hud.operation_label")
            .withStyle(ChatFormatting.WHITE);
      }
      boolean confirmed = operationSelectionReady();
      MutableComponent status = Component.translatable(
         confirmed ? "fastformer.hud.operation_label" : "fastformer.hud.selection_label"
      ).withStyle(ChatFormatting.WHITE);
      status.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY));
      if (confirmed) {
         OperationStageMode[] stageModes = OperationStageMode.values();
         for (int index = 0; index < stageModes.length; index++) {
            if (index > 0) {
               status.append(Component.literal(" / ").withStyle(ChatFormatting.DARK_GRAY));
            }
            OperationStageMode mode = stageModes[index];
            status.append(Component.translatable(mode.translationKey()).withStyle(
               mode == PREVIEW_STATE.operation().operationStageMode() ? ChatFormatting.GREEN : ChatFormatting.GRAY
            ));
         }
         return status;
      }
      OperationSelectionMode[] modes = {OperationSelectionMode.CUBOID, OperationSelectionMode.PRISM};
      for (int index = 0; index < modes.length; index++) {
         if (index > 0) {
            status.append(Component.literal(" / ").withStyle(ChatFormatting.DARK_GRAY));
         }
         OperationSelectionMode mode = modes[index];
         status.append(Component.translatable(mode.translationKey()).withStyle(
            mode == PREVIEW_STATE.operation().operationSelectionMode() ? ChatFormatting.GREEN : ChatFormatting.GRAY
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
      boolean workspaceLocked = ClientOperationController.active() && ClientOperationController.workspace().locked();
      GeometryInteractionHit interactionHit = geometryInteractionHit();
      // This text offers a pointer action. The locked workspace refuses that
      // action, so draw the text only while the workspace accepts new actions.
      if (interactionHit != null && geometryGizmoHit() == null
         && WorkspacePointerPrompt.acceptsNewAction(workspaceLocked)) {
         graphics.drawString(minecraft.font, interactionHit.hoverText(), x, y, 0xFFFFFFFF, true);
         y += 10;
      }
      OperationInteractionIntent operationTarget = operationInteractionIntent().orElse(null);
      if (operationGizmoHit() == null
         && operationPointUnderCrosshairIndex() < 0
         && operationTarget instanceof OperationInteractionIntent.Face face) {
         for (Component line : WorkspacePointerPrompt.crosshairActionLines(face, workspaceLocked)) {
            graphics.drawString(minecraft.font, line, x, y, 0xFFFFFFFF, true);
            y += 10;
         }
         return;
      }
      GizmoHudInput hudInput = gizmoHudInput(geometryPlan);
      AxisGizmo.Axis dragAxis = hudInput.dragAxis();
      AxisGizmo.Axis axis = dragAxis;
      AxisGizmo.Operation operation = dragAxis == null ? null : hudInput.dragOperation();
      AxisGizmo displayedGizmo = hudInput.gizmo();
      boolean retainedGizmo = false;
      boolean hoveredGizmo = false;
      int gizmoAlpha = 255;
      if (axis == null) {
         AxisGizmo gizmo = displayedGizmo;
         AxisGizmo.Hit hit = gizmo == null || !hudInput.allowNearBlock() && InteractionContext.nearVanillaBlock(minecraft)
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
      if (ClientOperationController.active()) {
         OperationInteractionIntent.Gizmo target = operationWorkspaceGizmoHit();
         return new GizmoHudInput(
            target == null ? null : target.gizmo(),
            FastPlaceClientInput.operationGizmoDragAxis(),
            FastPlaceClientInput.operationGizmoDragOperation(),
            FastPlaceClientInput.operationGizmoDragBaseValue(),
            FastPlaceClientInput.operationGizmoDragSteps(),
            true,
            false
         );
      }
      if (PREVIEW_STATE.operation().active()) {
         return new GizmoHudInput(
            operationGizmo(),
            FastPlaceClientInput.operationGizmoDragAxis(),
            FastPlaceClientInput.operationGizmoDragOperation(),
            FastPlaceClientInput.operationGizmoDragBaseValue(),
            FastPlaceClientInput.operationGizmoDragSteps(),
            true,
            true
         );
      }
      return new GizmoHudInput(
         geometryPlan == null ? null : geometryPlan.gizmo(),
         FastPlaceClientInput.geometryGizmoDragAxis(),
         FastPlaceClientInput.geometryGizmoDragOperation(),
         FastPlaceClientInput.geometryGizmoDragBaseValue(),
         FastPlaceClientInput.geometryGizmoDragSteps(),
         false,
         false
      );
   }

   private static double gizmoHudValue(
      GizmoHudInput input,
      GeometryPreviewPlan geometryPlan,
      AxisGizmo.Axis axis,
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

   private static int gizmoAxisHudColor(AxisGizmo.Axis axis) {
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
      AxisGizmo.Axis axis,
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
      AxisGizmo.Axis axis,
      int x,
      int y,
      int color
   ) {
      graphics.drawString(minecraft.font, axis.name(), x, y, color, true);
      return x + minecraft.font.width(axis.name());
   }

   private static void renderScrollFeedbackBottom(
      GuiGraphics graphics, Minecraft minecraft, boolean persistentFreeScroll
   ) {
      FEEDBACK.renderScrollFeedback(
         graphics, minecraft, scrollFeedbackData(), persistentFreeScroll, System.nanoTime()
      );
   }

   private static ScrollFeedbackData scrollFeedbackData() {
      if (PREVIEW_STATE.operation().active() && operationSelectionReady()) {
         BlockPos value = operationSelectionConfirmed() && FastPlaceClientInput.modifierHeld()
            ? PREVIEW_STATE.operation().stackVector()
            : PREVIEW_STATE.operation().translation();
         return FEEDBACK.coordinates(value);
      }
      if (PREVIEW_STATE.geometry().active() && PREVIEW_STATE.geometry().mode() == GeometryMode.CONE_PRISM) {
         Vec3 offset = PREVIEW_STATE.geometry().coneTopOffset();
         return new ScrollFeedbackData(
            List.of(
               new AxisFeedback("X", GeometryNumbers.fixed(offset.x, 2), FEEDBACK.axisColor(AxisGizmo.Axis.X)),
               new AxisFeedback("Z", GeometryNumbers.fixed(offset.z, 2), FEEDBACK.axisColor(AxisGizmo.Axis.Z))
            ),
            ""
         );
      }
      if (PREVIEW_STATE.building().active()) {
         BuildingPreviewPayload building = PREVIEW_STATE.building();
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
         FastPlaceStage stage = effectiveStage(building);
         if (stage == FastPlaceStage.FACE && PREVIEW_STATE.building().faceMode() == FaceMode.PARALLELOGRAM_BASE_PLANE) {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
               return new ScrollFeedbackData(
                  List.of(),
                  GeometryNumbers.fixed(
                     FastPlaceGeometry.faceBaseOffsetValue(PREVIEW_STATE.building().points(), PREVIEW_STATE.building().faceBaseOffset(), player.getViewVector(1.0F)),
                     0
                  )
               );
            }
         }
         if (stage == FastPlaceStage.VOLUME && FastPlaceGeometry.usesVolumeOffset(effectiveBuildingModes(PREVIEW_STATE.building()))) {
            return PREVIEW_STATE.building().volumeMode() == VolumeMode.FREE
               ? FEEDBACK.coordinates(PREVIEW_STATE.building().volumeBaseOffset())
               : new ScrollFeedbackData(List.of(), formatScalar(PREVIEW_STATE.building().volumeBaseOffset()));
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

   private static void logPreviewFailure(String message, Throwable failure) {
      long now = System.nanoTime();
      if (lastPreviewFailureLogAt == 0L
         || now < lastPreviewFailureLogAt
         || now - lastPreviewFailureLogAt >= PREVIEW_FAILURE_LOG_INTERVAL_NANOS) {
         lastPreviewFailureLogAt = now;
         LOGGER.warn(message, failure);
      }
   }

   private static void renderQuickReplaceGhost(RenderLevelStageEvent event, Minecraft minecraft, LocalPlayer player) {
      QuickReplaceMode.Preview preview = QuickReplaceMode.preview(minecraft);
      if (preview == null) return;
      PoseStack poseStack = event.getPoseStack();
      Vec3 camera = event.getCamera().getPosition();
      BufferSource buffers = minecraft.renderBuffers().bufferSource();
      poseStack.pushPose();
      poseStack.translate(preview.position().getX() - camera.x, preview.position().getY() - camera.y, preview.position().getZ() - camera.z);
      RenderSystem.enableBlend();
      RenderSystem.defaultBlendFunc();
      RenderSystem.setShaderColor(0.55F, 0.9F, 1.0F, 0.42F);
      minecraft.getBlockRenderer().renderSingleBlock(preview.state(), poseStack, buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
      RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
      RenderSystem.disableBlend();
      poseStack.popPose();
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
   public static void onRenderLevelStage(RenderLevelStageEvent event) {
      BuildingPreviewPayload snapshot = PREVIEW_STATE.building();
      if (event.getStage() == Stage.AFTER_PARTICLES) {
          Minecraft minecraft = Minecraft.getInstance();
          LocalPlayer player = minecraft.player;
          if (QuickReplaceMode.active() && QuickReplaceMode.canReplace(minecraft) && minecraft.level != null && player != null) {
             renderQuickReplaceGhost(event, minecraft, player);
          }
          boolean emptyBuildingPreview = snapshot.enabled()
             && player != null
             && PlaceableItems.isPlaceable(player.getMainHandItem());
          if (!(snapshot.active() || emptyBuildingPreview || PREVIEW_STATE.operation().active()
             || ClientOperationController.active() || PREVIEW_STATE.geometry().active() || QuickReplaceMode.active())) {
             return;
          }
          worldPreviewOpacity = updateWorldPreviewOpacity(minecraft, event.getPartialTick().getGameTimeDeltaTicks());
         if (emptyBuildingPreview && worldPreviewOpacity <= 0.01F) {
             return;
          }
         PreviewRenderOwner renderOwner = PreviewRenderOwner.select(
            snapshot.active(),
            PREVIEW_STATE.operation().active(),
            ClientOperationController.active(),
            PREVIEW_STATE.geometry().active()
         );
         if (minecraft.level != null && player != null && renderOwner == PreviewRenderOwner.GEOMETRY) {
            renderGeometryWall(event, minecraft);
         } else if (minecraft.level != null && player != null
            && renderOwner == PreviewRenderOwner.OPERATION) {
            OperationInteractionIntent pointerIntent = operationInteractionIntent().orElse(null);
            // The confirmed selection is retained as source data, but the workspace owns
            // its overlay and hit targets from this point on.
            if (OperationPreviewRenderer.shouldRenderServerSelection(
               PREVIEW_STATE.operation().active(),
               ClientOperationController.active(),
               ClientOperationController.selectionDraftActive()
            )) {
               renderOperationSelection(event, minecraft, player, PREVIEW_STATE.operation());
            }
            if (ClientOperationController.active()) {
               renderClientOperationWorkspace(event, minecraft, pointerIntent);
            }
            renderSelectionCreationCandidate(event, minecraft, pointerIntent);
         } else if (minecraft.level != null
            && player != null
            && (snapshot.active() || PlaceableItems.isPlaceable(player.getMainHandItem()))) {
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
             FastPlaceGeometry.Modes buildingModes = effectiveBuildingModes(snapshot);
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
                previewEffect, buildingPreviewBlocksCached(snapshot, previewPoints, polygonHeightConfirmed)
             );
             Set<BlockPos> candidateBlocks = ControlPointPresentation.candidateBlocks(snapshot, candidate, hoveredPoint);
             BuildingRenderFrame renderFrame = buildingRenderFrame(
                snapshot, previewEffect, previewBlocks, candidateBlocks, candidate, hoveredPoint,
                previewPoints, polygonHeightConfirmed, buildingModes
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
               effectiveBuildingModes(snapshot)
            );
            List<GuideLine> lines = FastPlaceGeometry.guideLines(
               snapshot.points(), snapshot.polygonClosed(), snapshot.faceBaseOffset(), snapshot.perpendicularAnchor(), eye, view, effectiveBuildingModes(snapshot)
            );
            BufferSource buffers = minecraft.renderBuffers().bufferSource();
            PoseStack poseStack = event.getPoseStack();
            Vec3 camera = event.getCamera().getPosition();
            List<ControlPoint> buildingPoints = renderFrame.controlPoints();
            Map<BlockPos, BuildingSpecialBlock> specialBlockStyles = renderFrame.specialBlockStyles();
            Map<BlockPos, BlockState> previewStateOverrides = renderFrame.stateOverrides();
            boolean confirmedLightweight = PreviewAsyncPolicy.useOutlineOnly(snapshot.points(),
               buildingPreviewWorkload(snapshot, snapshot.points(), snapshot.polygonHeightConfirmed()));
            boolean pendingLightweight = PreviewAsyncPolicy.useOutlineOnly(previewPoints,
               buildingPreviewWorkload(snapshot, previewPoints, polygonHeightConfirmed));
            confirmedLightweight |= PreviewAsyncPolicy.useLightweightShell(layers.confirmedRenderBlocks().size());
            pendingLightweight |= PreviewAsyncPolicy.useLightweightShell(layers.pendingRenderBlocks().size());
            // Keep a cheap, stable boundary visible in every fill mode. This gives
            // immediate feedback while the detailed shell is still being built.
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
            BuildingRenderLayers shellBlocks = renderFrame.shellLayers();
            BuildingShellVisibility shellVisibility = renderBuildingShells(
               player,
               poseStack,
               buffers,
               camera,
               previewState,
               previewStateOverrides,
               shellBlocks.confirmedRenderBlocks(),
               shellBlocks.pendingRenderBlocks(),
               specialBlockStyles,
               buildingModes,
               confirmedLightweight,
               pendingLightweight,
               !confirmedOutlineEdges.isEmpty() || !pendingOutlineEdges.isEmpty()
            );
            // Keep the geometric boundary authoritative when the async shell is
            // unavailable. The fallback also draws the first point face, while
            // outline edges remain in the same world-space coordinate path.
            renderBuildingEndpointFallback(
               poseStack, buffers, camera, snapshot.points().size(), previewPoints, shellVisibility
            );
            if (!confirmedOutlineEdges.isEmpty() || !pendingOutlineEdges.isEmpty()) {
               ShapeShellRenderer.renderOutlineEdges(
                  poseStack,
                  buffers.getBuffer(RenderType.lines()),
                  camera,
                  confirmedOutlineEdges,
                  pendingOutlineEdges
               );
            }
            renderBuildingFallbackPoints(
               poseStack, buffers, camera, buildingPoints, shellBlocks.allBlocks()
            );
            renderBuildingGuidePlaneGrid(poseStack, buffers.getBuffer(RenderType.lines()), camera, planes);
            renderBuildingGuideLines(poseStack, buffers.getBuffer(RenderType.lines()), camera, lines);
            buffers.endBatch(RenderType.lines());
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
      renderGuidePlaneGrid(poseStack, buffers.getBuffer(RenderType.lines()), camera, plan.guidePlanes());
      renderGuideLines(poseStack, buffers.getBuffer(RenderType.lines()), camera, plan.guideLines());
      buffers.endBatch(RenderType.lines());
      if (plan.gizmo() != null) {
         new GizmoRenderer(worldPreviewOpacity).renderGeometryGizmo(poseStack, buffers, camera, plan.gizmo());
      }
   }

   private static GeometryWorkflowView geometryWorkflowView(Vec3 view) {
      return new GeometryWorkflowView(
         PREVIEW_STATE.geometry().mode(),
         PREVIEW_STATE.geometry().points().size(),
         PREVIEW_STATE.geometry().pointLocations(),
         PREVIEW_STATE.geometry().pointRoles(),
         PREVIEW_STATE.geometry().closed(),
         FastPlaceClientInput.modifierHeld(),
         PREVIEW_STATE.geometry().polyhedronShapeVariant(),
         PREVIEW_STATE.geometry().coneShapeVariant(),
         PREVIEW_STATE.geometry().compoundShapeVariant(),
         PREVIEW_STATE.geometry().polyhedronSizeMode(),
         PREVIEW_STATE.geometry().extrusion(),
         PREVIEW_STATE.geometry().conePlaneMode(),
         PREVIEW_STATE.geometry().coneRadius(),
         PREVIEW_STATE.geometry().coneScaleX(),
         PREVIEW_STATE.geometry().coneScaleZ(),
         PREVIEW_STATE.geometry().coneTopScaleOffset(),
         PREVIEW_STATE.geometry().coneTopOffset(),
         PREVIEW_STATE.geometry().coneRotationRadians(),
         PREVIEW_STATE.geometry().coneGizmoLocal(),
         PREVIEW_STATE.geometry().rotation(),
         PREVIEW_STATE.geometry().polyhedronLocalScale(),
         PREVIEW_STATE.geometry().polyhedronWorldScale(),
         PREVIEW_STATE.geometry().polyhedronGizmoLocal(),
         PREVIEW_STATE.geometry().fillMode(),
         view,
         PREVIEW_STATE.geometry().selectedPointIndex()
      );
   }

   private static BlockPos buildingCandidatePoint(BuildingPreviewPayload snapshot, LocalPlayer player) {
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
          effectiveBuildingModes(snapshot)
      );
   }

   private static PlacementContextSnapshot previewPlacementContext(
      BuildingPreviewPayload snapshot, LocalPlayer player
   ) {
      if (snapshot.placementContext() != null || !snapshot.points().isEmpty()) {
         return snapshot.placementContext();
      }
      BlockHitResult hit = raycastBlocks(player);
      if (hit.getType() != Type.BLOCK) {
         return null;
      }
      FastPlaceGeometry.Modes modes = effectiveBuildingModes(snapshot);
      boolean embedded = snapshot.pointMode() == PointMode.RAYCAST
         && modes.raycastPlacement() == RaycastPlacement.EMBEDDED;
      return PlacementContextSnapshot.capture(
         player.level(), player, player.getMainHandItem(), hit, embedded
      );
   }

   private static Set<BlockPos> confirmedBuildingBlocks(BuildingPreviewPayload snapshot) {
      LineTieBias effectiveBias = effectiveBuildingModes(snapshot).faceTieBias();
      if (cachedConfirmedBuildingState != snapshot || cachedConfirmedBuildingBias != effectiveBias) {
         cachedConfirmedBuildingState = snapshot;
         cachedConfirmedBuildingBias = effectiveBias;
         FastPlaceGeometry.Modes modes = effectiveBuildingModes(snapshot);
         try {
            if (!PreviewAsyncPolicy.shouldRenderSolidFallback(snapshot.points(),
               buildingPreviewWorkload(snapshot, snapshot.points(), snapshot.polygonHeightConfirmed()))) {
               cachedConfirmedBuildingBlocks = buildingPreviewLimitFallbackSafely(
                  snapshot, snapshot.points(), snapshot.polygonHeightConfirmed(), modes
               );
               return cachedConfirmedBuildingBlocks;
            }
            Set<BlockPos> generated = buildingPreviewBlocks(
               snapshot,
               snapshot.points(),
               snapshot.polygonHeightConfirmed(),
               modes
            );
            cachedConfirmedBuildingBlocks = completedBuildingPreview(
               snapshot, snapshot.points(), snapshot.polygonHeightConfirmed(), modes, generated
            );
         } catch (RuntimeException exception) {
            logPreviewFailure("Unable to generate FastFormer confirmed building preview; using outline fallback", exception);
            cachedConfirmedBuildingBlocks = buildingPreviewLimitFallbackSafely(
               snapshot, snapshot.points(), snapshot.polygonHeightConfirmed(), modes
            );
         }
      }
      return cachedConfirmedBuildingBlocks;
   }

   private static Set<BlockPos> buildingPreviewBlocks(
      BuildingPreviewPayload snapshot, List<BlockPos> points, boolean polygonHeightConfirmed
   ) {
      return buildingPreviewBlocks(snapshot, points, polygonHeightConfirmed, effectiveBuildingModes(snapshot));
   }

   private static Set<BlockPos> buildingPreviewBlocks(
      BuildingPreviewPayload snapshot,
      List<BlockPos> points,
      boolean polygonHeightConfirmed,
      FastPlaceGeometry.Modes modes
   ) {
      return buildingPreviewBlocks(snapshot, points, polygonHeightConfirmed, modes, null);
   }

   private static Set<BlockPos> buildingPreviewBlocks(
      BuildingPreviewPayload snapshot,
      List<BlockPos> points,
      boolean polygonHeightConfirmed,
      FastPlaceGeometry.Modes modes,
      ProgressiveBlockGeneration progress
   ) {
      return buildingPreviewBlocks(snapshot, points, polygonHeightConfirmed, modes, progress, FastPlaceGeometry.PREVIEW_MAX_BLOCKS);
   }

   private static Set<BlockPos> buildingPreviewBlocks(
      BuildingPreviewPayload snapshot, List<BlockPos> points, boolean polygonHeightConfirmed,
      FastPlaceGeometry.Modes modes, ProgressiveBlockGeneration progress, int blockLimit
   ) {
      return snapshot.faceMode() == FaceMode.POLYGON && !snapshot.polygonClosed()
         ? WallGenerator.generate(
            points,
            false,
            BlockPos.ZERO,
            blockLimit,
            progress == null ? io.github.fastformer.fastplace.geometry.generation.BlockGenerationObserver.NONE : progress
         )
         : FastPlaceGeometry.blocks(
            points,
            modes,
            polygonHeightConfirmed,
            snapshot.polygonVolumeShape(),
            blockLimit,
            progress == null ? io.github.fastformer.fastplace.geometry.generation.BlockGenerationObserver.NONE : progress
          );
   }

   private static Set<BlockPos> buildingPreviewBlocksCached(
      BuildingPreviewPayload snapshot, List<BlockPos> points, boolean polygonHeightConfirmed
   ) {
      long buildingVersion = PREVIEW_STATE.buildingVersion();
      boolean modifierHeld = FastPlaceClientInput.modifierHeld();
      BuildingPreviewKey key = cachedBuildingPreviewKey;
      if (key == null
         || key.stateVersion() != buildingVersion
         || key.heightConfirmed() != polygonHeightConfirmed
         || key.modifierHeld() != modifierHeld
         || !key.points().equals(points)) {
         key = new BuildingPreviewKey(buildingVersion, points, polygonHeightConfirmed, modifierHeld);
      }
      if (!key.equals(cachedBuildingPreviewKey)) {
         cachedBuildingPreviewKey = key;
         cachedBuildingFallbackBlocks = Set.of();
         cachedBuildingPreviewAtLimit = false;
         cachedBuildingPreviewResultVersion++;
         if (cachedBuildingPreviewProgress != null) {
            cachedBuildingPreviewProgress.cancel();
            cachedBuildingPreviewProgress = null;
         }
         BUILDING_PREVIEW_GENERATION.cancel();
         FastPlaceGeometry.Modes modes = effectiveBuildingModes(snapshot);
         PreviewAsyncPolicy.Workload workload = buildingPreviewWorkload(snapshot, key.points(), polygonHeightConfirmed);
         if (!PreviewAsyncPolicy.shouldRenderSolidFallback(key.points(), workload)) {
            cachedBuildingPreviewBlocks = buildingPreviewLimitFallbackSafely(
               snapshot, key.points(), polygonHeightConfirmed, modes
            );
         } else if (PreviewAsyncPolicy.generateSynchronously(key.points(), workload)) {
            try {
               Set<BlockPos> generated = buildingPreviewBlocks(snapshot, key.points(), polygonHeightConfirmed, modes);
               cachedBuildingPreviewBlocks = completedBuildingPreview(
                  snapshot, key.points(), polygonHeightConfirmed, modes, generated
               );
            } catch (RuntimeException exception) {
               logPreviewFailure("Unable to generate FastFormer building preview; using outline fallback", exception);
               cachedBuildingPreviewBlocks = buildingPreviewLimitFallbackSafely(
                  snapshot, key.points(), polygonHeightConfirmed, modes
               );
            }
            cachedBuildingPreviewResultVersion++;
            BUILDING_PREVIEW_GENERATION.cancel();
         } else {
            cachedBuildingPreviewBlocks = Set.of();
            if ((workload == PreviewAsyncPolicy.Workload.PLANE || workload == PreviewAsyncPolicy.Workload.VOLUME)
               && modes.fillMode() != FillMode.OUTLINE) {
               cachedBuildingFallbackBlocks = buildingPreviewLimitFallbackSafely(
                  snapshot, key.points(), polygonHeightConfirmed, modes
               );
               cachedBuildingPreviewBlocks = cachedBuildingFallbackBlocks;
            }
            cachedBuildingPreviewProgress = new ProgressiveBlockGeneration(
               PreviewAsyncPolicy.estimateScanCells(key.points(), workload), false
            );
            ProgressiveBlockGeneration progress = cachedBuildingPreviewProgress;
            BuildingPreviewKey requestKey = key;
            BUILDING_PREVIEW_GENERATION.replace(requestKey, PREVIEW_GENERATION_EXECUTOR.submit(
               () -> {
                  Set<BlockPos> blocks = buildingPreviewBlocks(
                     snapshot, requestKey.points(), polygonHeightConfirmed, modes, progress
                  );
                  progress.complete();
                  return new BuildingBlockResult(requestKey, blocks);
               }
            ));
         }
      }
      if (BUILDING_PREVIEW_GENERATION.completed()) {
         try {
            Optional<BuildingBlockResult> completed = BUILDING_PREVIEW_GENERATION.takeCompleted();
            if (completed.isPresent() && completed.orElseThrow().key().equals(cachedBuildingPreviewKey)) {
               BuildingBlockResult result = completed.orElseThrow();
               FastPlaceGeometry.Modes modes = effectiveBuildingModes(snapshot);
               cachedBuildingPreviewBlocks = completedBuildingPreview(
                  snapshot, result.key().points(), polygonHeightConfirmed, modes, result.blocks()
               );
               cachedBuildingPreviewAtLimit = false;
               cachedBuildingPreviewResultVersion++;
            }
         } catch (CancellationException ignored) {
            // A newer snapped candidate superseded this generation.
         } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            cachedBuildingPreviewBlocks = buildingPreviewLimitFallbackSafely(
               snapshot, key.points(), polygonHeightConfirmed, effectiveBuildingModes(snapshot)
            );
            cachedBuildingPreviewAtLimit = false;
            cachedBuildingPreviewResultVersion++;
         } catch (java.util.concurrent.ExecutionException exception) {
            if (!(exception.getCause() instanceof CancellationException)) {
               logPreviewFailure(
                  "Unable to generate FastFormer building preview asynchronously; using outline fallback",
                  exception.getCause()
               );
               cachedBuildingPreviewBlocks = buildingPreviewLimitFallbackSafely(
                  snapshot, key.points(), polygonHeightConfirmed, effectiveBuildingModes(snapshot)
               );
               cachedBuildingPreviewAtLimit = false;
               cachedBuildingPreviewResultVersion++;
            }
         } finally {
            cachedBuildingPreviewProgress = null;
         }
      } else if (cachedBuildingPreviewProgress != null) {
         ProgressiveBlockGeneration.Snapshot progress = cachedBuildingPreviewProgress.snapshot();
         if (cachedBuildingPreviewAtLimit || progress.generated() >= FastPlaceGeometry.PREVIEW_MAX_BLOCKS) {
            holdBuildingPreviewAtFallback(snapshot, key.points(), polygonHeightConfirmed);
         }
      }
      return cachedBuildingPreviewBlocks;
   }

   private static Set<BlockPos> completedBuildingPreview(
      BuildingPreviewPayload snapshot,
      List<BlockPos> points,
      boolean polygonHeightConfirmed,
      FastPlaceGeometry.Modes modes,
      Set<BlockPos> generated
   ) {
      return GenerationLimitExceeded.is(generated)
         ? buildingPreviewLimitFallbackSafely(snapshot, points, polygonHeightConfirmed, modes)
         : generated;
   }

   private static void holdBuildingPreviewAtFallback(
      BuildingPreviewPayload snapshot, List<BlockPos> points, boolean polygonHeightConfirmed
   ) {
      cachedBuildingPreviewAtLimit = true;
      if (cachedBuildingPreviewProgress != null) {
         cachedBuildingPreviewProgress.cancel();
         cachedBuildingPreviewProgress = null;
      }
      BUILDING_PREVIEW_GENERATION.cancel();
      Set<BlockPos> fallback = cachedBuildingFallbackBlocks;
      if (fallback.isEmpty()) {
         fallback = buildingPreviewLimitFallbackSafely(
            snapshot, points, polygonHeightConfirmed, effectiveBuildingModes(snapshot)
         );
         cachedBuildingFallbackBlocks = fallback;
      }
      if (!cachedBuildingPreviewBlocks.equals(fallback)) {
         cachedBuildingPreviewBlocks = fallback;
         cachedBuildingPreviewResultVersion++;
      }
   }

   private static Set<BlockPos> buildingPreviewLimitFallback(
      BuildingPreviewPayload snapshot,
      List<BlockPos> points,
      boolean polygonHeightConfirmed,
      FastPlaceGeometry.Modes modes
   ) {
      if (modes.fillMode() != FillMode.OUTLINE) {
         Set<BlockPos> outline = buildingPreviewBlocks(
            snapshot,
            points,
            polygonHeightConfirmed,
            modes.withFillMode(FillMode.OUTLINE),
            null,
            PreviewAsyncPolicy.OUTLINE_BLOCK_LIMIT
         );
         if (!GenerationLimitExceeded.is(outline)) {
            return outline;
         }
      }
      return Set.copyOf(new HashSet<>(points));
   }

   private static Set<BlockPos> buildingPreviewLimitFallbackSafely(
      BuildingPreviewPayload snapshot,
      List<BlockPos> points,
      boolean polygonHeightConfirmed,
      FastPlaceGeometry.Modes modes
   ) {
      try {
         return buildingPreviewLimitFallback(snapshot, points, polygonHeightConfirmed, modes);
      } catch (RuntimeException exception) {
         logPreviewFailure("Unable to generate FastFormer outline preview; using control points", exception);
         return Set.copyOf(new HashSet<>(points));
      }
   }

   private static PreviewAsyncPolicy.Workload buildingPreviewWorkload(
      BuildingPreviewPayload snapshot, List<BlockPos> points, boolean polygonHeightConfirmed
   ) {
      if (points.size() <= 2) {
         return PreviewAsyncPolicy.Workload.LINE;
      }
      if (snapshot.faceMode() == FaceMode.POLYGON && !snapshot.polygonClosed()) {
         return PreviewAsyncPolicy.Workload.PATH;
      }
      if (points.size() == 3 || snapshot.faceMode() == FaceMode.POLYGON && !polygonHeightConfirmed) {
         return PreviewAsyncPolicy.Workload.PLANE;
      }
      return PreviewAsyncPolicy.Workload.VOLUME;
   }

   private static void cancelBuildingPreviewGeneration() {
      BUILDING_PREVIEW_GENERATION.cancel();
      if (cachedBuildingPreviewProgress != null) {
         cachedBuildingPreviewProgress.cancel();
         cachedBuildingPreviewProgress = null;
      }
      cachedBuildingPreviewAtLimit = false;
      cachedBuildingFallbackBlocks = Set.of();
   }

   private static BuildingRenderFrame buildingRenderFrame(
      BuildingPreviewPayload snapshot,
      ResolvedPlacementEffect previewEffect,
      Set<BlockPos> previewBlocks,
      Set<BlockPos> candidateBlocks,
      BlockPos candidate,
      BlockPos hoveredPoint,
      List<BlockPos> previewPoints,
      boolean polygonHeightConfirmed,
      FastPlaceGeometry.Modes modes
   ) {
      BuildingRenderKey key = new BuildingRenderKey(
         PREVIEW_STATE.buildingVersion(),
         cachedBuildingPreviewKey,
         cachedBuildingPreviewResultVersion,
         candidate,
         hoveredPoint
      );
      if (!key.equals(cachedBuildingRenderKey)) {
         GeometryPreviewBlocks.Layers split = GeometryPreviewBlocks.layersPreservingConfirmed(
            PlacementEffectPreview.applyToTargets(previewEffect, confirmedBuildingBlocks(snapshot)), previewBlocks
         );
         HashSet<BlockPos> pending = new HashSet<>(split.pending());
         pending.addAll(candidateBlocks);
         Set<BlockPos> all = PreviewGeometrySupport.unionBlocks(split.confirmed(), pending);
         BuildingRenderLayers layers = new BuildingRenderLayers(
            // Keep point cells in the shell. Removing them makes a one-block
            // first-point preview render only its control-point/outline lines.
            split.confirmed(),
            pending,
            all
         );
         List<ControlPoint> controlPoints = ControlPointPresentation.building(snapshot, candidate, hoveredPoint);
         Map<BlockPos, BuildingSpecialBlock> specialBlockStyles = buildingSpecialBlockStyles(
            controlPoints, layers.allBlocks()
         );
         HashSet<BlockPos> confirmedMarkers = new HashSet<>();
         specialBlockStyles.forEach((pos, special) -> {
            if (special.confirmed()) {
               confirmedMarkers.add(pos);
            }
         });
         HashSet<BlockPos> pendingMarkers = new HashSet<>();
         for (ControlPoint point : controlPoints) {
            if (!point.confirmed()) {
               pendingMarkers.add(BlockPos.containing(point.center()));
            }
         }
         cachedBuildingRenderKey = key;
         cachedBuildingRenderFrame = new BuildingRenderFrame(
            layers,
            BUILDING_SHELL_BLOCKS_CACHE.resolve(layers, confirmedMarkers, pendingMarkers),
            controlPoints,
            specialBlockStyles,
            previewEffectStates(previewEffect, snapshot, previewPoints, polygonHeightConfirmed, modes, layers.allBlocks())
         );
      }
      return cachedBuildingRenderFrame;
   }

   private static Map<BlockPos, BlockState> previewEffectStates(
      ResolvedPlacementEffect effect,
      BuildingPreviewPayload snapshot,
      List<BlockPos> points,
      boolean polygonHeightConfirmed,
      FastPlaceGeometry.Modes modes,
      Set<BlockPos> previewBlocks
   ) {
      if (effect == null || previewBlocks.isEmpty()) {
         return Map.of();
      }
      return PlacementEffectPreview.resolveStates(effect, previewBlocks);
   }

   private static FastPlaceGeometry.Modes effectiveBuildingModes(BuildingPreviewPayload snapshot) {
      FastPlaceGeometry.Modes modes = snapshot.modes().withModifierHeld(FastPlaceClientInput.modifierHeld());
      FastPlaceStage stage = effectiveStage(snapshot);
      if (snapshot.faceMode() != FaceMode.POLYGON
         && FastPlaceClientInput.modifierHeld()
         && (stage == FastPlaceStage.FACE || stage == FastPlaceStage.VOLUME)) {
         modes = modes.withFaceTieBias(LineTieBias.OPPOSITE);
      }
      boolean firstRaycastPoint = stage == FastPlaceStage.POINT
         && snapshot.pointMode() == PointMode.RAYCAST
         && snapshot.points().isEmpty();
      boolean raycastLine = stage == FastPlaceStage.LINE && snapshot.lineMode() == LineMode.RAYCAST;
      if (firstRaycastPoint || raycastLine) {
         return modes.withRaycastPlacement(
            FastPlaceClientInput.modifierHeld() ? RaycastPlacement.EMBEDDED : RaycastPlacement.SURFACE
         );
      }
      return modes;
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
         PREVIEW_STATE.geometryVersion(),
         FastPlaceClientInput.modifierHeld(),
         eye,
         view,
         candidate,
         hoveredPoint
      );
      if (!key.equals(cachedGeometryPlanKey)) {
         cachedGeometryPlanKey = key;
         try {
            cachedGeometryPlan = GeometryWorkflows.previewPlan(
               workflowView, PREVIEW_STATE.geometry().points(), hoveredPoint, candidate, eye
            );
         } catch (RuntimeException exception) {
            logPreviewFailure("Unable to generate FastFormer geometry preview plan; using control points", exception);
            cachedGeometryPlan = GeometryPreviewPlan.controlPoints(PREVIEW_STATE.geometry().points(), hoveredPoint);
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
            && PREVIEW_STATE.geometry().mode() == GeometryMode.CONE_PRISM
            && PREVIEW_STATE.geometry().conePlaneMode().stageFor(PREVIEW_STATE.geometry().points().size()) == ConePrismStage.BODY
            && !FastPlaceClientInput.modifierHeld()) {
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
      int facePointCount = Math.min(PREVIEW_STATE.geometry().pointLocations().size(), PREVIEW_STATE.geometry().conePlaneMode().facePointCount());
      if (facePointCount < PREVIEW_STATE.geometry().conePlaneMode().facePointCount()) {
         return Set.of();
      }
      List<Vec3> facePoints = List.copyOf(PREVIEW_STATE.geometry().pointLocations().subList(0, facePointCount));
      ConePrismParameters parameters = new ConePrismParameters(
         facePoints,
         Optional.empty(),
         PREVIEW_STATE.geometry().coneShapeVariant(),
         PREVIEW_STATE.geometry().conePlaneMode(),
         PREVIEW_STATE.geometry().coneRadius(),
         PREVIEW_STATE.geometry().coneScaleX(),
         PREVIEW_STATE.geometry().coneScaleZ(),
         PREVIEW_STATE.geometry().coneTopScaleOffset(),
         PREVIEW_STATE.geometry().coneTopOffset(),
         PREVIEW_STATE.geometry().coneRotationRadians()
      );
      return ConePrismGenerator.baseOutline(parameters, 16_384);
   }

   private static BlockPos geometryCandidatePoint(Minecraft minecraft, LocalPlayer player) {
      GeometryHit hit = geometryCandidateHit(minecraft, player);
      return hit == null ? null : hit.point();
   }

   private static GeometryHit geometryCandidateHit(Minecraft minecraft, LocalPlayer player) {
      if (!PREVIEW_STATE.geometry().active() || player == null || InteractionContext.nearVanillaBlock(minecraft)) {
         return null;
      }
      BlockHitResult hit = raycastBlocks(player);
      if (hit.getType() == Type.BLOCK) {
         return GeometryHit.from(hit);
      }
      List<BlockPos> points = PREVIEW_STATE.geometry().points();
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
      if (InteractionContext.nearVanillaBlock(minecraft)) {
         return gizmo;
      }
      AxisGizmo.Hit hit = gizmo.hitTest(player.getEyePosition(), view, visiblePreviewReach(player));
      AxisGizmo.HandleKey hoveredKey = hit == null ? null : hit.handle().key();
      return gizmo.withState(hoveredKey, FastPlaceClientInput.geometryGizmoDragKey());
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
      BlockHitResult hit = raycastBlocks(player);
      double limit = raycastDebug == null ? PREVIEW_REACH : raycastDebug.distance();
      return hit.getType() == Type.BLOCK
         ? GeometryRayVisibility.visibleReach(limit, player.getEyePosition(), hit)
         : limit;
   }

   private static float updateWorldPreviewOpacity(Minecraft minecraft, float partialTick) {
      return InteractionContext.previewVisibility(minecraft, partialTick);
   }

   private static void renderBuildingFallbackPoints(
      PoseStack poseStack,
      BufferSource buffers,
      Vec3 camera,
      List<ControlPoint> points,
      Set<BlockPos> shellBlocks
   ) {
      List<ControlPoint> fallback = points.stream()
         .filter(point -> !shellBlocks.contains(BlockPos.containing(point.center())))
         .toList();
      renderControlPoints(poseStack, buffers, camera, fallback);
   }

   private static Map<BlockPos, BuildingSpecialBlock> buildingSpecialBlockStyles(
      List<ControlPoint> controlPoints, Set<BlockPos> ghostBlocks
   ) {
      HashMap<BlockPos, BuildingSpecialBlock> result = new HashMap<>();
      for (ControlPoint point : controlPoints) {
         BlockPos pos = BlockPos.containing(point.center());
         if (point.confirmed() && ghostBlocks.contains(pos)) {
            result.put(
               pos.immutable(),
               new BuildingSpecialBlock(
                  point.feedback().style(point.role(), point.hovered()), point.confirmed()
               )
            );
         }
      }
      return Map.copyOf(result);
   }

   private static void renderGeometryControlPoints(PoseStack poseStack, BufferSource buffers, Vec3 camera, GeometryPreviewPlan plan) {
      renderControlPoints(poseStack, buffers, camera, plan.controlPoints());
   }

   static void renderControlPoints(PoseStack poseStack, BufferSource buffers, Vec3 camera, List<ControlPoint> points) {
      new io.github.fastformer.client.controlpoint.ControlPointRenderer(
         worldPreviewOpacity, pendingGridDashOffset(), SELECTION_DASH_LENGTH
      ).render(poseStack, buffers, camera, points);
   }

   static void renderFlowingDashedBox(
      PoseStack poseStack,
      VertexConsumer consumer,
      Vec3 center,
      Vec3 halfExtents,
      double offset,
      float alpha
   ) {
      renderFlowingDashedBox(poseStack, consumer, center, halfExtents, null, offset, alpha);
   }

   static void renderFlowingDashedBox(
      PoseStack poseStack,
      VertexConsumer consumer,
      Vec3 center,
      Vec3 halfExtents,
      Vec3 camera,
      double offset,
      float alpha
   ) {
      io.github.fastformer.client.render.geometry.DashedBoxRenderer.render(
         poseStack, consumer, center, halfExtents, camera, GHOST_OUTLINE_CAMERA_BIAS,
         offset, alpha, SELECTION_DASH_LENGTH, worldPreviewOpacity);
   }

   static void renderOperationOutlineLine(PoseStack poseStack, VertexConsumer consumer, Vec3 from, Vec3 to, float alpha) {
      renderAlternatingDashedLine(poseStack, consumer, from, to, alpha, selectionDashOffset());
   }

   static void renderStaticOperationGuideLine(
      PoseStack poseStack, VertexConsumer consumer, Vec3 from, Vec3 to, float alpha
   ) {
      renderAlternatingDashedLine(poseStack, consumer, from, to, alpha, 0.0);
   }

   private static void renderAlternatingDashedLine(
      PoseStack poseStack, VertexConsumer consumer, Vec3 from, Vec3 to, float alpha, double offset
   ) {
      GuideRenderer.renderAlternatingDashedLine(
         poseStack, consumer, from, to, alpha, offset, SELECTION_DASH_LENGTH, worldPreviewOpacity
      );
   }

   @SubscribeEvent
   public static void onLoggingOut(LoggingOut event) {
      endWorldSession();
   }

   @SubscribeEvent
   public static void onClientLevelUnload(net.neoforged.neoforge.event.level.LevelEvent.Unload event) {
      if (event.getLevel().isClientSide()) {
         endWorldSession();
      }
   }

   private static void endWorldSession() {
      // A replayed preview session must be confirmed before it becomes visible again.
      // A connection that replays nothing active cancels the pending restore instead.
      WORLD_SESSION_LIFECYCLE.endWorldSession();
   }

   private static void clearWorldRenderState() {
      geometryTransformBaseline = null;
      OPERATION_FACE_INTERPOLATOR.reset();
      worldPreviewOpacity = 1.0F;
      raycastDebug = null;
      cachedRaycast = null;
      cachedRaycastStart = null;
      cachedRaycastDirection = null;
      cachedRaycastAt = 0L;
      resetBuildingPreviewCaches();
      cachedGeometryPlanKey = null;
      cachedGeometryPlan = null;
      cachedGeometryRenderSource = null;
      cachedGeometryRenderLayers = GeometryRenderLayers.empty();
      CONFIRMED_GHOST_CACHE.clear();
      CONFIRMED_OUTLINE_CACHE.clear();
      PENDING_GHOST_CACHE.clear();
      PENDING_GHOST_BUFFER_CACHE.clear();
      SmoothReticlePostEffect.reset();
      smoothReticleFrame = false;
   }

   public static boolean reconnectPreviewRestorePending() {
      return PREVIEW_STATE.reconnectRestorePending();
   }

   /** Applies the server previews held at the reconnect boundary after the player confirms. */
   public static boolean confirmReconnectPreviewRestore() {
      ClientPreviewState.HeldReconnectPreviews held = PREVIEW_STATE.takeHeldReconnectPreviews();
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
      PREVIEW_STATE.dismissReconnectRestore();
   }

   /** Returns the current long-range block target used by preview placement. */
   public static BlockPos previewRaycastTarget(LocalPlayer player) {
      if (player == null || player.level() == null) {
         return null;
      }
      BlockHitResult hit = raycastBlocks(player);
      return hit.getType() == Type.BLOCK ? hit.getBlockPos().immutable() : null;
   }

   private static BlockHitResult raycastBlocks(LocalPlayer player) {
      Vec3 start = player.getEyePosition();
      Vec3 direction = player.getViewVector(1.0F);
      PreviewRenderOwner owner = PreviewRenderOwner.select(
         PREVIEW_STATE.building().active(),
         PREVIEW_STATE.operation().active(),
         ClientOperationController.active(),
         PREVIEW_STATE.geometry().active()
      );
      boolean placement = owner == PreviewRenderOwner.BUILDING || owner == PreviewRenderOwner.NONE;
      long now = System.nanoTime();
      if (cachedRaycast != null
         && cachedRaycastForPlacement == placement
         && start.equals(cachedRaycastStart)
         && direction.equals(cachedRaycastDirection)
         && now - cachedRaycastAt <= 16_000_000L) {
         return cachedRaycast.hit();
      }
      cachedRaycast = placement
         ? LongRangeBlockRaycast.clipForPlacement(player.level(), player, start, direction)
         : LongRangeBlockRaycast.clip(player.level(), player, start, direction);
      cachedRaycastForPlacement = placement;
      cachedRaycastStart = start;
      cachedRaycastDirection = direction;
      cachedRaycastAt = now;
      raycastDebug = cachedRaycast;
      return cachedRaycast.hit();
   }

   private static BuildingShellVisibility renderBuildingShells(
      LocalPlayer player,
      PoseStack poseStack,
      BufferSource buffers,
      Vec3 camera,
      BlockState state,
      Map<BlockPos, BlockState> stateOverrides,
      Set<BlockPos> confirmedBlocks,
      Set<BlockPos> pendingBlocks,
      Map<BlockPos, BuildingSpecialBlock> specialStyles,
      FastPlaceGeometry.Modes modes,
      boolean confirmedLightweight,
      boolean pendingLightweight,
      boolean cleanOutlineEdges
   ) {
      if (confirmedLightweight) {
         CONFIRMED_BUILDING_SHELL_CACHE.clear();
         CONFIRMED_SHELL_FACES.clear();
         CONFIRMED_SHELL_EDGES.clear();
      }
      if (pendingLightweight) {
         PENDING_BUILDING_SHELL_CACHE.clear();
         PENDING_SHELL_FACES.clear();
         PENDING_SHELL_EDGES.clear();
      }
      if (confirmedLightweight && pendingLightweight) {
         return BuildingShellVisibility.NONE;
      }
      BlockGetter confirmedPreviewLevel = state == null
         ? player.level()
         : PreviewBlockOcclusion.level(confirmedBlocks, state, stateOverrides);
      BlockGetter pendingPreviewLevel = state == null
         ? player.level()
         : PreviewBlockOcclusion.level(pendingBlocks, state, stateOverrides);
      net.minecraft.world.phys.shapes.CollisionContext collision = net.minecraft.world.phys.shapes.CollisionContext.of(player);
      ShapeShellMesh.Mesh confirmed = confirmedLightweight ? ShapeShellMesh.Mesh.empty() : CONFIRMED_BUILDING_SHELL_CACHE.mesh(
         confirmedPreviewLevel, state, stateOverrides, collision, confirmedBlocks, confirmedBlocks, specialStyles, player.isShiftKeyDown()
      );
      ShapeShellMesh.Mesh pending = pendingLightweight ? ShapeShellMesh.Mesh.empty() : PENDING_BUILDING_SHELL_CACHE.mesh(
         pendingPreviewLevel, state, stateOverrides, collision, pendingBlocks, pendingBlocks, Map.of(), player.isShiftKeyDown()
      );

      float pendingFaceAlpha = 0.30F + 0.20F * ghostBreathPulse();
      renderBuildingFaces(
         poseStack,
         buffers,
         camera,
         confirmed.faces(),
         pending.faces(),
         previewAlpha(0.80F, worldPreviewOpacity),
         previewAlpha(pendingFaceAlpha, worldPreviewOpacity)
      );
      BuildingShellVisibility visibility = new BuildingShellVisibility(
         !confirmed.faces().isEmpty(), !pending.faces().isEmpty()
      );

      if (!cleanOutlineEdges) {
         buffers.endBatch(PENDING_XRAY_LINES);
         buffers.endBatch(GHOST_OUTLINE_LINES);
         CONFIRMED_SHELL_EDGES.draw(poseStack, camera, confirmed.edges(), PENDING_XRAY_LINES, 0.16F * worldPreviewOpacity);
         PENDING_SHELL_EDGES.draw(poseStack, camera, pending.edges(), PENDING_XRAY_LINES, 0.12F * worldPreviewOpacity);
         CONFIRMED_SHELL_EDGES.draw(poseStack, camera, confirmed.edges(), GHOST_OUTLINE_LINES, 0.92F * worldPreviewOpacity);
         PENDING_SHELL_EDGES.draw(poseStack, camera, pending.edges(), GHOST_OUTLINE_LINES, 0.82F * worldPreviewOpacity);
      } else {
         CONFIRMED_SHELL_EDGES.clear();
         PENDING_SHELL_EDGES.clear();
      }
      return visibility;
   }

   /**
    * Draws both shell layers with alphas that already include the global preview
    * fade. Both paths below use the same alphas, so the layer count cannot step
    * the preview opacity. See InteractionContext.previewVisibility.
    */
   private static void renderBuildingFaces(
      PoseStack poseStack,
      BufferSource buffers,
      Vec3 camera,
      List<ShapeShellMesh.Face> confirmedFaces,
      List<ShapeShellMesh.Face> pendingFaces,
      float confirmedAlpha,
      float pendingAlpha
   ) {
      if (!confirmedFaces.isEmpty() && !pendingFaces.isEmpty()) {
         CONFIRMED_SHELL_FACES.clear();
         PENDING_SHELL_FACES.clear();
         ShapeShellRenderer.renderFaces(
            poseStack, buffers.getBuffer(GHOST_FACES), camera, confirmedFaces, confirmedAlpha
         );
         ShapeShellRenderer.renderFaces(
            poseStack, buffers.getBuffer(GHOST_FACES), camera, pendingFaces, pendingAlpha
         );
         buffers.endBatch(GHOST_FACES);
         return;
      }

      buffers.endBatch(GHOST_FACES);
      CONFIRMED_SHELL_FACES.draw(poseStack, camera, confirmedFaces, GHOST_FACES, confirmedAlpha);
      PENDING_SHELL_FACES.draw(poseStack, camera, pendingFaces, GHOST_FACES, pendingAlpha);
   }

   static float previewAlpha(float baseAlpha, float previewOpacity) {
      return Math.clamp(baseAlpha, 0.0F, 1.0F) * Math.clamp(previewOpacity, 0.0F, 1.0F);
   }

   private static void renderBuildingEndpointFallback(
      PoseStack poseStack,
      BufferSource buffers,
      Vec3 camera,
      int confirmedPointCount,
      List<BlockPos> previewPoints,
      BuildingShellVisibility shellVisibility
   ) {
      Set<BlockPos> fallbackBlocks = endpointFallbackBlocks(
         confirmedPointCount, previewPoints, shellVisibility
      );
      if (fallbackBlocks.isEmpty()) {
         return;
      }
      GhostMesh fallback = GhostMeshBuilder.build(fallbackBlocks, true, true, true);
      float pulse = ghostBreathPulse();
      float faceAlpha = GHOST_FACE_ALPHA_MIN + (GHOST_FACE_ALPHA_MAX - GHOST_FACE_ALPHA_MIN) * pulse;
      float outlineAlpha = GHOST_OUTLINE_ALPHA_MIN + (GHOST_OUTLINE_ALPHA_MAX - GHOST_OUTLINE_ALPHA_MIN) * pulse;
      renderGhostFaces(
         poseStack, buffers.getBuffer(GHOST_FACES), camera, fallback.faces(), PENDING_RED, PENDING_GREEN, PENDING_BLUE, faceAlpha
      );
      buffers.endBatch(GHOST_FACES);
      renderGhostOutline(
         poseStack, buffers.getBuffer(GHOST_OUTLINE_LINES), camera, fallback.edges(), PENDING_RED, PENDING_GREEN, PENDING_BLUE, outlineAlpha
      );
      buffers.endBatch(GHOST_OUTLINE_LINES);
   }

   static Set<BlockPos> endpointFallbackBlocks(
      int confirmedPointCount, List<BlockPos> previewPoints, BuildingShellVisibility shellVisibility
   ) {
      if (previewPoints.isEmpty()) {
         return Set.of();
      }
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
      int confirmedCount = Math.min(Math.max(confirmedPointCount, 0), previewPoints.size());
      if (confirmedCount > 0 && !shellVisibility.confirmedFacesVisible()) {
         result.add(previewPoints.getFirst());
         result.add(previewPoints.get(confirmedCount - 1));
      }
      if (previewPoints.size() > confirmedCount && !shellVisibility.pendingFacesVisible()) {
         result.add(previewPoints.getLast());
      }
      return Set.copyOf(result);
   }

   record BuildingShellVisibility(boolean confirmedFacesVisible, boolean pendingFacesVisible) {
      static final BuildingShellVisibility NONE = new BuildingShellVisibility(false, false);
   }

   private static void renderConfirmedBlocks(PoseStack poseStack, BufferSource buffers, Vec3 camera, Set<BlockPos> blocks) {
      if (blocks.isEmpty()) {
         return;
      }
      boolean renderFaces = blocks.size() <= CONFIRMED_FACE_BLOCK_LIMIT;
      GhostMesh mesh = renderFaces ? CONFIRMED_GHOST_CACHE.mesh(blocks) : CONFIRMED_OUTLINE_CACHE.mesh(blocks);
      float pulse = ghostBreathPulse();
      float faceAlpha = GHOST_FACE_ALPHA_MIN + (GHOST_FACE_ALPHA_MAX - GHOST_FACE_ALPHA_MIN) * pulse;
      float outlineAlpha = GHOST_OUTLINE_ALPHA_MIN + (GHOST_OUTLINE_ALPHA_MAX - GHOST_OUTLINE_ALPHA_MIN) * pulse;
      if (renderFaces) {
         renderGhostFaces(poseStack, buffers.getBuffer(GHOST_FACES), camera, mesh.faces(), GHOST_RED, GHOST_GREEN, GHOST_BLUE, faceAlpha);
         buffers.endBatch(GHOST_FACES);
      }
      renderGhostOutline(poseStack, buffers.getBuffer(GHOST_OUTLINE_LINES), camera, mesh.edges(), GHOST_RED, GHOST_GREEN, GHOST_BLUE, outlineAlpha);
      buffers.endBatch(GHOST_OUTLINE_LINES);
   }

   static void renderOperationVolume(
      PoseStack poseStack, VertexConsumer lines, OperationSelectionVolume selection, Vec3 offset, float alpha
   ) {
      if (selection.prism() != null) {
         for (GuideLine edge : selection.prism().move(offset).edges()) {
            renderOperationOutlineLine(poseStack, lines, edge.from(), edge.to(), alpha);
         }
      } else {
         renderOperationBoxOutline(
            poseStack,
            lines,
            selection.bounds().move(offset).inflate(SELECTION_FACE_INFLATE),
            alpha
         );
      }
   }

   private static void renderOperationBoxOutline(PoseStack poseStack, VertexConsumer lines, AABB box, float alpha) {
      Vec3 p000 = new Vec3(box.minX, box.minY, box.minZ);
      Vec3 p001 = new Vec3(box.minX, box.minY, box.maxZ);
      Vec3 p010 = new Vec3(box.minX, box.maxY, box.minZ);
      Vec3 p011 = new Vec3(box.minX, box.maxY, box.maxZ);
      Vec3 p100 = new Vec3(box.maxX, box.minY, box.minZ);
      Vec3 p101 = new Vec3(box.maxX, box.minY, box.maxZ);
      Vec3 p110 = new Vec3(box.maxX, box.maxY, box.minZ);
      Vec3 p111 = new Vec3(box.maxX, box.maxY, box.maxZ);
      renderOperationOutlineLine(poseStack, lines, p000, p001, alpha);
      renderOperationOutlineLine(poseStack, lines, p000, p010, alpha);
      renderOperationOutlineLine(poseStack, lines, p000, p100, alpha);
      renderOperationOutlineLine(poseStack, lines, p001, p011, alpha);
      renderOperationOutlineLine(poseStack, lines, p001, p101, alpha);
      renderOperationOutlineLine(poseStack, lines, p010, p011, alpha);
      renderOperationOutlineLine(poseStack, lines, p010, p110, alpha);
      renderOperationOutlineLine(poseStack, lines, p100, p101, alpha);
      renderOperationOutlineLine(poseStack, lines, p100, p110, alpha);
      renderOperationOutlineLine(poseStack, lines, p011, p111, alpha);
      renderOperationOutlineLine(poseStack, lines, p101, p111, alpha);
      renderOperationOutlineLine(poseStack, lines, p110, p111, alpha);
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
      VertexBuffer buffer = PENDING_GHOST_BUFFER_CACHE.buffer(mesh);
      if (buffer == null) {
         return;
      }
      if (FastPlaceClientShaders.pendingDashedLines() == null) {
         renderPendingFallback(poseStack, buffers, camera, mesh);
         return;
      }
      Matrix4f modelView = new Matrix4f(eventModelView).translate(
         (float)-camera.x,
         (float)-camera.y,
         (float)-camera.z
      );
      renderPendingBuffer(buffer, PENDING_DASHED_XRAY_LINES, modelView, projectionMatrix, PENDING_XRAY_ALPHA);
      renderPendingBuffer(buffer, PENDING_DASHED_LINES, modelView, projectionMatrix, PENDING_GRID_ALPHA);
   }

   private static void renderPendingFallback(
      PoseStack poseStack, BufferSource buffers, Vec3 camera, PendingGhostMesh mesh
   ) {
      double offset = pendingDashOffset();
      poseStack.pushPose();
      poseStack.translate(-camera.x, -camera.y, -camera.z);
      VertexConsumer xray = buffers.getBuffer(PENDING_XRAY_LINES);
      VertexConsumer visible = buffers.getBuffer(PENDING_LINES);
      for (PendingPreviewGrid.Segment edge : mesh.gridEdges()) {
         Vec3 from = new Vec3(edge.from().x(), edge.from().y(), edge.from().z());
         Vec3 to = new Vec3(edge.to().x(), edge.to().y(), edge.to().z());
         renderAlternatingDashedLine(poseStack, xray, from, to, PENDING_XRAY_ALPHA, offset);
         renderAlternatingDashedLine(poseStack, visible, from, to, PENDING_GRID_ALPHA, offset);
      }
      poseStack.popPose();
      buffers.endBatch(PENDING_XRAY_LINES);
      buffers.endBatch(PENDING_LINES);
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
         RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, alpha * worldPreviewOpacity);
         buffer.bind();
         buffer.drawWithShader(modelView, projectionMatrix, shader);
         VertexBuffer.unbind();
      } finally {
         RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
         renderType.clearRenderState();
      }
   }

   private static double pendingDashOffset() {
      double seconds = (System.nanoTime() % 10_000_000_000L) / 1_000_000_000.0;
      double distance = seconds * PENDING_DASH_SPEED;
      double snappedDistance = Math.floor(distance / PENDING_DASH_UNIT) * PENDING_DASH_UNIT;
      return snappedDistance % (PENDING_DASH_LENGTH + PENDING_DASH_GAP);
   }

   static double pendingGridDashOffset() {
      double seconds = (System.nanoTime() % 10_000_000_000L) / 1_000_000_000.0;
      return seconds * PENDING_DASH_SPEED % SELECTION_DASH_PERIOD;
   }

   private static double selectionDashOffset() {
      double seconds = (System.nanoTime() % 10_000_000_000L) / 1_000_000_000.0;
      double distance = seconds * PENDING_DASH_SPEED;
      double snappedDistance = Math.floor(distance / PENDING_DASH_UNIT) * PENDING_DASH_UNIT;
      return snappedDistance % SELECTION_DASH_PERIOD;
   }

   static float ghostBreathPulse() {
      double phase = (System.nanoTime() % GHOST_BREATH_PERIOD_NANOS) / (double)GHOST_BREATH_PERIOD_NANOS;
      return (float)(0.5 - Math.cos(phase * Math.PI * 2.0) * 0.5);
   }

   private static void renderGhostOutline(
      PoseStack poseStack, VertexConsumer consumer, Vec3 camera, List<GhostEdge> edges, float red, float green, float blue, float alpha
   ) {
      poseStack.pushPose();
      poseStack.translate(-camera.x, -camera.y, -camera.z);
      for (GhostEdge edge : edges) {
         renderLine(
            poseStack,
            consumer,
            GhostOutlineDepthBias.towardCamera(edge.from().vec3(), camera, GHOST_OUTLINE_CAMERA_BIAS),
            GhostOutlineDepthBias.towardCamera(edge.to().vec3(), camera, GHOST_OUTLINE_CAMERA_BIAS),
            red,
            green,
            blue,
            alpha
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
         case DOWN, UP -> addGhostQuad(poseStack, consumer, new Vec3(x0, y0, z0), new Vec3(x1, y0, z0), new Vec3(x1, y0, z1), new Vec3(x0, y0, z1), red, green, blue, alpha);
         case NORTH, SOUTH -> addGhostQuad(poseStack, consumer, new Vec3(x0, y0, z0), new Vec3(x1, y0, z0), new Vec3(x1, y1, z0), new Vec3(x0, y1, z0), red, green, blue, alpha);
         case WEST, EAST -> addGhostQuad(poseStack, consumer, new Vec3(x0, y0, z0), new Vec3(x0, y0, z1), new Vec3(x0, y1, z1), new Vec3(x0, y1, z0), red, green, blue, alpha);
      }
   }

   public static void addGhostQuad(
      PoseStack poseStack,
      VertexConsumer consumer,
      Vec3 a,
      Vec3 b,
      Vec3 c,
      Vec3 d,
      float red,
      float green,
      float blue,
      float alpha
   ) {
      addGhostQuadUnscaled(poseStack, consumer, a, b, c, d, red, green, blue, alpha * worldPreviewOpacity);
   }

   /** Writes cacheable vertices without baking the current preview opacity into them. */
   public static void addGhostQuadUnscaled(
      PoseStack poseStack,
      VertexConsumer consumer,
      Vec3 a,
      Vec3 b,
      Vec3 c,
      Vec3 d,
      float red,
      float green,
      float blue,
      float alpha
   ) {
      io.github.fastformer.client.render.geometry.PreviewQuads.write(
         poseStack, consumer, a, b, c, d, red, green, blue, alpha);
   }

   static void renderGuidePlaneGrid(PoseStack poseStack, VertexConsumer lineConsumer, Vec3 camera, List<GuidePlane> planes) {
      GuideRenderer.renderGeometryPlanes(poseStack, lineConsumer, camera, planes, worldPreviewOpacity);
   }

   private static void renderBuildingGuidePlaneGrid(
      PoseStack poseStack, VertexConsumer lineConsumer, Vec3 camera, List<GuidePlane> planes
   ) {
      GuideRenderer.renderBuildingPlanes(poseStack, lineConsumer, camera, planes, worldPreviewOpacity);
   }

   static void renderGuideLines(PoseStack poseStack, VertexConsumer consumer, Vec3 camera, List<GuideLine> lines) {
      GuideRenderer.renderGeometryLines(poseStack, consumer, camera, lines, worldPreviewOpacity);
   }

   private static void renderBuildingGuideLines(
      PoseStack poseStack, VertexConsumer consumer, Vec3 camera, List<GuideLine> lines
   ) {
      GuideRenderer.renderBuildingLines(
         poseStack, consumer, camera, lines, (float)SELECTION_DASH_LENGTH, worldPreviewOpacity
      );
   }

   public static void renderLine(PoseStack poseStack, VertexConsumer consumer, Vec3 from, Vec3 to) {
      GuideRenderer.renderLine(poseStack, consumer, from, to, worldPreviewOpacity);
   }

   public static void renderLine(PoseStack poseStack, VertexConsumer consumer, Vec3 from, Vec3 to, float red, float green, float blue, float alpha) {
      GuideRenderer.renderLine(poseStack, consumer, from, to, red, green, blue, alpha, worldPreviewOpacity);
   }

   private static Vec3 normalize(Vec3 vector) {
      double length = vector.length();
      return length < 1.0E-7 ? Vec3.ZERO : vector.scale(1.0 / length);
   }

   private static FastPlaceMode selectedMode(BuildingPreviewPayload snapshot, FastPlaceStage stage) {
      return (FastPlaceMode)(switch (stage) {
         case POINT -> snapshot.pointMode();
         case LINE -> snapshot.lineMode();
         case FACE -> snapshot.faceMode();
         case VOLUME -> snapshot.volumeMode();
      });
   }

   private static MutableComponent contextValue(BuildingPreviewPayload snapshot) {
      if (!snapshot.active()) {
         return null;
      }
      Minecraft minecraft = Minecraft.getInstance();
      LocalPlayer player = minecraft.player;
      if (player == null) {
         return null;
      }
      FastPlaceStage stage = effectiveStage(snapshot);
      if (stage == FastPlaceStage.LINE && snapshot.lineMode() == LineMode.FREE_SCROLL) {
         BlockPos offset = snapshot.freeScrollOffset();
         return Component.translatable("fastformer.message.context.free_scroll", offset.getX(), offset.getY(), offset.getZ()).withStyle(ChatFormatting.YELLOW);
      } else if (stage == FastPlaceStage.LINE && snapshot.lineMode() == LineMode.RAYCAST) {
         return Component.translatable(
               "fastformer.message.context.raycast",
               snapshot.raycastPlacement() == io.github.fastformer.fastplace.quickshape.RaycastPlacement.EMBEDDED
                  ? Component.translatable("fastformer.mode.raycast.embedded")
                  : Component.translatable("fastformer.mode.raycast.surface")
            )
            .withStyle(ChatFormatting.YELLOW);
      } else if (stage == FastPlaceStage.FACE && snapshot.faceMode() == FaceMode.PARALLELOGRAM_BASE_PLANE) {
         return Component.translatable(
               "fastformer.message.context.face_offset",
               GeometryNumbers.fixed(FastPlaceGeometry.faceBaseOffsetValue(snapshot.points(), snapshot.faceBaseOffset(), player.getViewVector(1.0F)), 0)
             )
             .withStyle(ChatFormatting.YELLOW);
      } else if (stage == FastPlaceStage.FACE && snapshot.faceMode() == FaceMode.POLYGON && !snapshot.polygonClosed()) {
         return Component.translatable("fastformer.message.polygon_close_hint").withStyle(ChatFormatting.YELLOW);
      } else {
         return stage == FastPlaceStage.VOLUME && FastPlaceGeometry.usesVolumeOffset(snapshot.modes())
            ? Component.translatable(
                  snapshot.volumeMode() == VolumeMode.FREE ? "fastformer.message.context.volume_free" : "fastformer.message.context.volume_base",
                  snapshot.volumeMode() == VolumeMode.FREE ? formatOffset(snapshot.volumeBaseOffset()) : formatScalar(snapshot.volumeBaseOffset())
               )
               .withStyle(ChatFormatting.YELLOW)
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

   private static FastPlaceStage effectiveStage(BuildingPreviewPayload snapshot) {
      return FastPlaceGeometry.effectiveStage(snapshot.points(), snapshot.faceMode(), snapshot.polygonClosed());
   }

   private static VertexBuffer uploadPendingGrid(List<PendingPreviewGrid.Segment> edges) {
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
               .setColor(PENDING_RED, PENDING_GREEN, PENDING_BLUE, 1.0F)
               .setNormal(nx, ny, nz);
            builder.addVertex(to.x(), to.y(), to.z())
               .setUv(length, 0.0F)
               .setColor(PENDING_RED, PENDING_GREEN, PENDING_BLUE, 1.0F)
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
