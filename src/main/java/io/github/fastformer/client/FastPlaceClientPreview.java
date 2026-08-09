package io.github.fastformer.client;

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
import io.github.fastformer.fastplace.FaceMode;
import io.github.fastformer.fastplace.FastPlaceGeometry;
import io.github.fastformer.fastplace.FastPlaceActivity;
import io.github.fastformer.fastplace.FastPlaceMode;
import io.github.fastformer.fastplace.FastPlaceStage;
import io.github.fastformer.fastplace.FastPlaceStateMachine;
import io.github.fastformer.fastplace.FillMode;
import io.github.fastformer.fastplace.TranslatableText;
import io.github.fastformer.fastplace.LineMode;
import io.github.fastformer.fastplace.LongRangeBlockRaycast;
import io.github.fastformer.fastplace.ConePlaneMode;
import io.github.fastformer.fastplace.ConePrismStage;
import io.github.fastformer.fastplace.GeometryHit;
import io.github.fastformer.fastplace.GeometryMode;
import io.github.fastformer.fastplace.geometry.OperationGeometry;
import io.github.fastformer.fastplace.geometry.PlaneAxes;
import io.github.fastformer.fastplace.OperationMode;
import io.github.fastformer.fastplace.OperationStageMode;
import io.github.fastformer.client.operation.ClientOperationController;
import io.github.fastformer.client.operation.ClientSelectionPart;
import io.github.fastformer.client.operation.OccupiedBlockBounds;
import io.github.fastformer.client.operation.WorkspacePreviewComposer;
import io.github.fastformer.client.operation.WorkspaceTransform;
import io.github.fastformer.fastplace.OperationSelectionMode;
import io.github.fastformer.fastplace.OperationSelectionStage;
import io.github.fastformer.fastplace.OperationSelectionVolume;
import io.github.fastformer.fastplace.geometry.ControlPoint;
import io.github.fastformer.fastplace.geometry.ControlPointRole;
import io.github.fastformer.fastplace.geometry.ControlPointFeedback;
import io.github.fastformer.fastplace.geometry.ControlPointStyle;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.GeometryAction;
import io.github.fastformer.fastplace.geometry.GeometryInteractionHit;
import io.github.fastformer.fastplace.geometry.GeometryInteractionTarget;
import io.github.fastformer.fastplace.geometry.GeometryNumbers;
import io.github.fastformer.fastplace.geometry.GeometryPreviewBlocks;
import io.github.fastformer.fastplace.geometry.GeometryRayVisibility;
import io.github.fastformer.fastplace.geometry.TransformFrame;
import io.github.fastformer.fastplace.GeometryWorkflowView;
import io.github.fastformer.fastplace.GeometryWorkflows;
import io.github.fastformer.fastplace.geometry.GizmoTextComponent;
import io.github.fastformer.fastplace.geometry.GizmoTextContext;
import io.github.fastformer.fastplace.geometry.GeometryTextBlock;
import io.github.fastformer.fastplace.geometry.generation.WallGenerator;
import io.github.fastformer.fastplace.geometry.generation.ConePrismGeometry;
import io.github.fastformer.fastplace.geometry.generation.ConePrismParameters;
import io.github.fastformer.fastplace.geometry.generation.ConePrismGenerator;
import io.github.fastformer.fastplace.geometry.generation.ProgressiveBlockGeneration;
import io.github.fastformer.fastplace.geometry.generation.GenerationFailed;
import io.github.fastformer.fastplace.geometry.generation.GenerationLimitExceeded;
import io.github.fastformer.fastplace.geometry.generation.LineTieBias;
import io.github.fastformer.fastplace.geometry.GeometryPreviewPlan;
import io.github.fastformer.fastplace.geometry.GuideLine;
import io.github.fastformer.fastplace.geometry.GuidePlane;
import io.github.fastformer.fastplace.geometry.SelectionPrism;
import io.github.fastformer.fastplace.PointMode;
import io.github.fastformer.fastplace.PolygonVolumeShape;
import io.github.fastformer.fastplace.PlaceableItems;
import io.github.fastformer.fastplace.PlacementContextSnapshot;
import io.github.fastformer.fastplace.RaycastPlacement;
import io.github.fastformer.fastplace.VolumeMode;
import io.github.fastformer.network.BuildingPreviewPayload;
import io.github.fastformer.network.OperationPreviewPayload;
import io.github.fastformer.network.GeometryPreviewPayload;
import io.github.fastformer.network.ActivityStatePayload;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
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
public final class FastPlaceClientPreview {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final RenderType PENDING_LINES = RenderType.create(
      "fastformer_pending_lines",
      DefaultVertexFormat.POSITION_COLOR_NORMAL,
      VertexFormat.Mode.LINES,
      1536,
      false,
      false,
      RenderType.CompositeState.builder()
         .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
         .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(1.0)))
         .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
         .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
          .setWriteMaskState(RenderStateShard.COLOR_WRITE)
         .setCullState(RenderStateShard.NO_CULL)
         .createCompositeState(false)
   );
   private static final RenderType PENDING_DASHED_LINES = RenderType.create(
      "fastformer_pending_dashed_lines",
      DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL,
      VertexFormat.Mode.LINES,
      256,
      false,
      false,
      RenderType.CompositeState.builder()
         .setShaderState(new RenderStateShard.ShaderStateShard(FastPlaceClientShaders::pendingDashedLines))
         .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(1.0)))
         .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
         .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
         .setWriteMaskState(RenderStateShard.COLOR_WRITE)
         .setCullState(RenderStateShard.NO_CULL)
         .createCompositeState(false)
   );
   private static final RenderType PENDING_DASHED_XRAY_LINES = RenderType.create(
      "fastformer_pending_dashed_xray_lines",
      DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL,
      VertexFormat.Mode.LINES,
      256,
      false,
      false,
      RenderType.CompositeState.builder()
         .setShaderState(new RenderStateShard.ShaderStateShard(FastPlaceClientShaders::pendingDashedLines))
         .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(1.0)))
         .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
         .setDepthTestState(RenderStateShard.GREATER_DEPTH_TEST)
         .setWriteMaskState(RenderStateShard.COLOR_WRITE)
         .setCullState(RenderStateShard.NO_CULL)
         .createCompositeState(false)
   );
   private static final RenderType GHOST_FACES = RenderType.create(
      "fastformer_ghost_faces",
      DefaultVertexFormat.POSITION_COLOR,
      VertexFormat.Mode.QUADS,
      1536,
      false,
      true,
      RenderType.CompositeState.builder()
         .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
         .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
         .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
         .setLayeringState(RenderType.POLYGON_OFFSET_LAYERING)
         .setWriteMaskState(RenderStateShard.COLOR_WRITE)
         .setCullState(RenderStateShard.NO_CULL)
         .createCompositeState(false)
   );
   private static final RenderType GHOST_OUTLINE_LINES = RenderType.create(
      "fastformer_ghost_outline_lines",
      DefaultVertexFormat.POSITION_COLOR_NORMAL,
      VertexFormat.Mode.LINES,
      1536,
      false,
      false,
      RenderType.CompositeState.builder()
         .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
         .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(1.0)))
         .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
         .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
         .setLayeringState(RenderType.POLYGON_OFFSET_LAYERING)
         .setWriteMaskState(RenderStateShard.COLOR_WRITE)
         .setCullState(RenderStateShard.NO_CULL)
         .createCompositeState(false)
   );
   private static final RenderType GIZMO_LINES = RenderType.create(
      "fastformer_gizmo_lines",
      DefaultVertexFormat.POSITION_COLOR_NORMAL,
      VertexFormat.Mode.LINES,
      1536,
      false,
      false,
      RenderType.CompositeState.builder()
         .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
         .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(4.0)))
         .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
         .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
         .setWriteMaskState(RenderStateShard.COLOR_WRITE)
         .setCullState(RenderStateShard.NO_CULL)
         .createCompositeState(false)
   );
   private static final RenderType GIZMO_HOVER_LINES = RenderType.create(
      "fastformer_gizmo_hover_lines",
      DefaultVertexFormat.POSITION_COLOR_NORMAL,
      VertexFormat.Mode.LINES,
      1536,
      false,
      false,
      RenderType.CompositeState.builder()
         .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
         .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(4.0)))
         .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
         .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
         .setWriteMaskState(RenderStateShard.COLOR_WRITE)
         .setCullState(RenderStateShard.NO_CULL)
         .createCompositeState(false)
   );
   private static final RenderType PENDING_XRAY_LINES = RenderType.create(
      "fastformer_pending_xray_lines",
      DefaultVertexFormat.POSITION_COLOR_NORMAL,
      VertexFormat.Mode.LINES,
      1536,
      false,
      false,
      RenderType.CompositeState.builder()
         .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
         .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(1.0)))
         .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
         .setDepthTestState(RenderStateShard.GREATER_DEPTH_TEST)
         .setWriteMaskState(RenderStateShard.COLOR_WRITE)
         .setCullState(RenderStateShard.NO_CULL)
          .createCompositeState(false)
   );
   private static final RenderType OCCLUDED_CONTROL_POINTS = RenderType.create(
      "fastformer_occluded_control_points",
      DefaultVertexFormat.POSITION_COLOR,
      VertexFormat.Mode.TRIANGLE_STRIP,
      1536,
      false,
      true,
      RenderType.CompositeState.builder()
         .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
         .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
         .setDepthTestState(RenderStateShard.GREATER_DEPTH_TEST)
         .setWriteMaskState(RenderStateShard.COLOR_WRITE)
         .setCullState(RenderStateShard.NO_CULL)
         .createCompositeState(false)
   );
   private static final RenderType GIZMO_SOLIDS = RenderType.create(
      "fastformer_gizmo_solids",
      DefaultVertexFormat.POSITION_COLOR,
      VertexFormat.Mode.QUADS,
      1536,
      false,
      false,
      RenderType.CompositeState.builder()
         .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
         .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
         .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
         .setWriteMaskState(RenderStateShard.COLOR_WRITE)
         .setCullState(RenderStateShard.NO_CULL)
         .createCompositeState(false)
   );
   private static final double PREVIEW_REACH = LongRangeBlockRaycast.MAX_REACH;
   private static final int CONFIRMED_FACE_BLOCK_LIMIT = 16000;
   private static final double MIN_PLANE_RADIUS = 2.0;
   private static final double PLANE_DISTANCE_SCALE = 0.08;
   private static final float GHOST_RED = 1.0F;
   private static final float GHOST_GREEN = 1.0F;
   private static final float GHOST_BLUE = 1.0F;
   private static final float PENDING_RED = 1.0F;
   private static final float PENDING_GREEN = 1.0F;
   private static final float PENDING_BLUE = 1.0F;
   private static final float GHOST_FACE_ALPHA_MIN = 0.10F;
   private static final float GHOST_FACE_ALPHA_MAX = 0.20F;
   private static final float BUILDING_NEAR_MINIMUM_OPACITY = 0.35F;
   private static final long FACE_NORMAL_INTERPOLATION_NANOS = 50_000_000L;
   private static final long PREVIEW_FAILURE_LOG_INTERVAL_NANOS = 5_000_000_000L;
   private static final float SELECTION_HIGHLIGHT_ALPHA = 0.42F;
   private static final float GHOST_OUTLINE_ALPHA_MIN = 0.45F;
   private static final float GHOST_OUTLINE_ALPHA_MAX = 0.90F;
   private static final float PENDING_GRID_ALPHA = 0.52F;
   private static final float PENDING_XRAY_ALPHA = 0.10F;
   private static final float SELECTION_XRAY_ALPHA = 0.34F;
   private static final double PENDING_DASH_UNIT = 1.0 / 16.0;
   private static final double PENDING_DASH_LENGTH = PENDING_DASH_UNIT * 4.0;
   private static final double PENDING_DASH_GAP = PENDING_DASH_UNIT * 2.0;
   private static final double PENDING_DASH_SPEED = 1.0;
   private static final double SELECTION_DASH_LENGTH = PENDING_DASH_UNIT * 4.0;
   private static final double SELECTION_DASH_PERIOD = SELECTION_DASH_LENGTH * 2.0;
   private static final long GHOST_BREATH_PERIOD_NANOS = 2_400_000_000L;
   private static final double GHOST_FACE_OFFSET = 0.002;
   private static final double BUILDING_PREVIEW_DISTANCE_FADE = 1.5;
   private static final double SELECTION_FACE_INFLATE = OperationSelectionVolume.RAYCAST_INFLATE;
   private static final double GHOST_OUTLINE_CAMERA_BIAS = 0.012;
   private static final double CONTROL_POINT_OUTLINE_INFLATE = 0.003;
   private static final float PLANE_RED = 0.18F;
   private static final float PLANE_GREEN = 0.78F;
   private static final float PLANE_BLUE = 1.0F;
   private static final float PLANE_ALPHA = 0.3F;
   private static final float OCCLUDED_POINT_ALPHA = 0.38F;
   private static final ResourceLocation CROSSHAIR_SPRITE = ResourceLocation.withDefaultNamespace("hud/crosshair");
   private static final double EPSILON = 1.0E-7;
   private static BuildingPreviewPayload buildingState = BuildingPreviewPayload.inactive();
   private static OperationPreviewPayload operationState = OperationPreviewPayload.inactive();
   private static GeometryPreviewPayload geometryState = GeometryPreviewPayload.inactive();
   private static FastPlaceActivity activityState = FastPlaceActivity.NONE;
   private static final HudFadeTimer SCROLL_FEEDBACK = new HudFadeTimer(1_500_000_000L, 400_000_000L);
   private static final HudFadeTimer GIZMO_FEEDBACK = new HudFadeTimer(1_500_000_000L, 400_000_000L);
   private static final PreviewOpacityController WORLD_PREVIEW_OPACITY = new PreviewOpacityController(180_000_000L, true);
   private static final OperationFaceHitInterpolator OPERATION_FACE_INTERPOLATOR = new OperationFaceHitInterpolator(FACE_NORMAL_INTERPOLATION_NANOS);
   private static final OperationFaceHitInterpolator WORKSPACE_FACE_INTERPOLATOR = new OperationFaceHitInterpolator(FACE_NORMAL_INTERPOLATION_NANOS);
   private static final List<InteractionIntentProvider> OPERATION_INTENT_PROVIDERS = List.of(
      FastPlaceClientPreview::resolveDraggedWorkspaceFaceIntent,
      FastPlaceClientPreview::resolveWorkspaceGizmoIntent,
      FastPlaceClientPreview::resolveWorkspaceFaceIntent,
      FastPlaceClientPreview::resolveWorkspacePartIntent,
      FastPlaceClientPreview::resolveSelectionCreateIntent
   );
   private static final IdentityHashMap<ClientSelectionPart, Map<BlockPos, io.github.fastformer.client.operation.ClientBlockSnapshot>>
      WORKSPACE_RESOLVED_CACHE = new IdentityHashMap<>();
   private static float worldPreviewOpacity = 1.0F;
   private static LongRangeBlockRaycast.Result raycastDebug;
   private static LongRangeBlockRaycast.Result cachedRaycast;
   private static Vec3 cachedRaycastStart;
   private static Vec3 cachedRaycastDirection;
   private static long cachedRaycastAt;
   private static AxisGizmo.Axis lastGizmoFeedbackAxis;
   private static AxisGizmo.Operation lastGizmoFeedbackOperation;
   private static int lastGizmoFeedbackSteps;
   private static double lastGizmoFeedbackBaseValue;
   private static TransformStatus geometryTransformBaseline;
   private static long lastPreviewFailureLogAt;
   private static final GhostMeshCache CONFIRMED_GHOST_CACHE = new GhostMeshCache(true, true, true);
   private static final GhostMeshCache CONFIRMED_OUTLINE_CACHE = new GhostMeshCache(false, true, true);
   private static final BuildingShellCache CONFIRMED_BUILDING_SHELL_CACHE = new BuildingShellCache(false);
   private static final BuildingShellCache PENDING_BUILDING_SHELL_CACHE = new BuildingShellCache(true);
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
   private static final PendingGhostMeshCache PENDING_GHOST_CACHE = new PendingGhostMeshCache();
   private static final PendingGhostBufferCache PENDING_GHOST_BUFFER_CACHE = new PendingGhostBufferCache();
   private static BuildingPreviewPayload cachedConfirmedBuildingState;
   private static LineTieBias cachedConfirmedBuildingBias = LineTieBias.DEFAULT;
   private static Set<BlockPos> cachedConfirmedBuildingBlocks = Set.of();
   private static long buildingStateVersion;
   private static BuildingPreviewKey cachedBuildingPreviewKey;
   private static Set<BlockPos> cachedBuildingPreviewBlocks = Set.of();
   private static Set<BlockPos> cachedBuildingFallbackBlocks = Set.of();
   private static Future<BuildingBlockResult> cachedBuildingPreviewFuture;
   private static ProgressiveBlockGeneration cachedBuildingPreviewProgress;
   private static boolean cachedBuildingPreviewAtLimit;
   private static long cachedBuildingPreviewResultVersion;
   private static BuildingRenderKey cachedBuildingRenderKey;
   private static BuildingRenderLayers cachedBuildingRenderLayers = BuildingRenderLayers.empty();
   private static long geometryStateVersion;
   private static GeometryPlanKey cachedGeometryPlanKey;
   private static GeometryPreviewPlan cachedGeometryPlan;
   private static GeometryPreviewPlan cachedGeometryRenderSource;
   private static GeometryRenderLayers cachedGeometryRenderLayers = GeometryRenderLayers.empty();
   private static boolean smoothReticleFrame;

   private FastPlaceClientPreview() {
   }

   public static void applyBuilding(BuildingPreviewPayload payload) {
      buildingState = payload;
      buildingStateVersion++;
      cachedConfirmedBuildingState = null;
      cachedConfirmedBuildingBias = LineTieBias.DEFAULT;
      cachedConfirmedBuildingBlocks = Set.of();
      cachedBuildingPreviewKey = null;
      cachedBuildingPreviewBlocks = Set.of();
      cachedBuildingFallbackBlocks = Set.of();
      cachedBuildingPreviewAtLimit = false;
      cancelBuildingPreviewGeneration();
      cachedBuildingRenderKey = null;
      cachedBuildingRenderLayers = BuildingRenderLayers.empty();
   }

   public static void applyOperation(OperationPreviewPayload payload) {
      operationState = payload;
      ClientOperationController.synchronize(payload);
   }

   public static void applyGeometry(GeometryPreviewPayload payload) {
      if (!continuesGeometryTransform(geometryState, payload)) {
         geometryTransformBaseline = null;
      }
      geometryState = payload;
      geometryStateVersion++;
      cachedGeometryPlanKey = null;
      cachedGeometryPlan = null;
      cachedGeometryRenderSource = null;
      cachedGeometryRenderLayers = GeometryRenderLayers.empty();
   }

   public static void applyActivity(ActivityStatePayload payload) {
      activityState = payload.activity();
   }

   public static void noteScrollFeedback() {
      SCROLL_FEEDBACK.touch(System.nanoTime());
   }

   public static void noteGizmoFeedback(
      AxisGizmo.Axis axis, AxisGizmo.Operation operation, int steps, double baseValue
   ) {
      lastGizmoFeedbackAxis = axis;
      lastGizmoFeedbackOperation = operation;
      lastGizmoFeedbackSteps = steps;
      lastGizmoFeedbackBaseValue = GeometryNumbers.finiteOr(baseValue, operation == AxisGizmo.Operation.SCALE ? 1.0 : 0.0);
      GIZMO_FEEDBACK.touch(System.nanoTime());
   }

   public static boolean active() {
      return buildingState.active();
   }

   public static boolean enabled() {
      return buildingState.enabled();
   }

   public static boolean middleConfirmEnabled() {
      return buildingState.middleConfirmEnabled();
   }

   public static boolean taskActive() {
      return activityState.task();
   }

   public static boolean activityCancellable() {
      return activityState.cancellable();
   }

   public static boolean operationActive() {
      return operationState.active() || ClientOperationController.active();
   }

   public static boolean operationNeedsSecond() {
      return operationState.active() && !operationState.hasSecond();
   }

   public static boolean operationNeedsFirst() {
      return operationState.active() && !operationState.hasFirst();
   }

   public static boolean operationSelectionReady() {
      return operationState.active()
         && (operationState.operationSelectionMode() == OperationSelectionMode.CUBOID
            ? operationState.hasFirst() && operationState.hasSecond()
            : operationState.operationSelectionMode() == OperationSelectionMode.PRISM
               ? operationState.operationPrismBasePointCount() >= 3
                  && operationState.points().size() > operationState.operationPrismBasePointCount()
               : operationState.points().size() >= operationState.operationSelectionMode().requiredPoints());
   }

   public static boolean operationSelectionConfirmed() {
      return operationState.active() && operationState.operationSelectionConfirmed();
   }

   public static boolean operationAdjustmentStarted() {
      return operationState.active() && operationState.operationAdjustmentStarted();
   }

   public static int operationPointCount() {
      return operationState.active() ? operationState.points().size() : 0;
   }

   public static boolean operationCuboid() {
      return operationState.active() && operationState.operationSelectionMode() == OperationSelectionMode.CUBOID;
   }

   public static boolean operationPrism() {
      return operationState.active() && operationState.operationSelectionMode() == OperationSelectionMode.PRISM;
   }

   public static boolean operationPrismBaseOpen() {
      return operationState.active()
         && operationState.operationSelectionMode() == OperationSelectionMode.PRISM
         && operationState.operationPrismBasePointCount() == 0;
   }

   public static boolean operationPointSelected() {
      return operationState.active()
         && operationState.operationSelectedPointIndex() >= 0
         && operationState.operationSelectedPointIndex() < operationState.points().size();
   }

   public static int operationSelectedPointIndex() {
      return operationPointSelected() ? operationState.operationSelectedPointIndex() : -1;
   }

   public static Vec3 operationPointCenter(int index) {
      return operationState.active() && index >= 0 && index < operationState.points().size()
         ? Vec3.atCenterOf(operationState.points().get(index))
         : null;
   }

   public static int operationPointUnderCrosshairIndex() {
      if (!operationState.active() || operationSelectionConfirmed() || operationState.points().isEmpty()) {
         return -1;
      }
      BlockPos point = pointUnderCrosshair(operationState.points(), true);
      return point == null ? -1 : operationState.points().indexOf(point);
   }

   public static SelectionPrism.EdgeInsertion operationPrismEdgeInsertion() {
      Minecraft minecraft = Minecraft.getInstance();
      LocalPlayer player = minecraft.player;
      if (!operationPrism() || operationSelectionConfirmed() || FastPlaceClientInput.modifierHeld() || player == null || operationState.points().size() < 2
         || operationPointUnderCrosshairIndex() >= 0) {
         return null;
      }
      raycastBlocks(player);
      return SelectionPrism.resolveEdgeInsertion(
         operationState.points(),
         operationState.operationPrismBasePointCount(),
         player.getEyePosition(),
         player.getViewVector(1.0F),
         raycastDebug == null ? 0.0 : raycastDebug.distance()
      );
   }

   public static SelectionPrism.GridPlane operationPointGridPlane(int pointIndex) {
      if (!operationPrism() || pointIndex < 0 || pointIndex >= operationState.points().size()) {
         return null;
      }
      int baseCount = operationState.operationPrismBasePointCount() >= 3
         ? operationState.operationPrismBasePointCount()
         : operationState.points().size();
      return pointIndex < baseCount && baseCount >= 3
         ? SelectionPrism.gridPlane(operationState.points().subList(0, baseCount))
         : null;
   }

   public static SelectionPrism.GridLine operationPointGridLine(int pointIndex) {
      if (!operationPrism() || pointIndex < 0 || pointIndex >= operationState.points().size()) {
         return null;
      }
      int closedBaseCount = operationState.operationPrismBasePointCount();
      int baseCount = closedBaseCount >= 3 ? closedBaseCount : operationState.points().size();
      if (baseCount < 3 || pointIndex != 0 && (closedBaseCount < 3 || pointIndex < baseCount)) {
         return null;
      }
      return SelectionPrism.heightGridLine(operationState.points().subList(0, baseCount));
   }

   public static BlockPos operationCandidatePoint() {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.player == null || operationSelectionConfirmed() || FastPlaceClientInput.modifierHeld()) {
         return null;
      }
      BlockHitResult hit = raycastBlocks(minecraft.player);
      if (operationPrismBaseOpen() && operationState.points().size() >= 3) {
         return SelectionPrism.resolveBasePlanePoint(
            operationState.points(),
            minecraft.player.getEyePosition(),
            minecraft.player.getViewVector(1.0F),
            raycastDebug == null ? 0.0 : raycastDebug.distance()
         );
      }
      if (operationWaitingForPrismHeight()) {
         int baseCount = operationState.operationPrismBasePointCount();
         return SelectionPrism.resolveHeightPoint(
            operationState.points().subList(0, baseCount),
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
      return operationState.active()
         && operationState.operationSelectionMode() == OperationSelectionMode.PRISM
         && operationState.operationPrismBasePointCount() >= 3
         && operationState.points().size() == operationState.operationPrismBasePointCount();
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
      AxisGizmo.Operation[] operations = OperationGizmoPresentation.operations(operationState.operationStageMode())
         .toArray(AxisGizmo.Operation[]::new);
      if (operations.length == 0) {
         return null;
      }
      AABB bounds = selection.bounds();
      Vec3 center = bounds.getCenter();
      BlockPos minDisplacement = OperationGeometry.stackDisplacement(bounds, operationState.operationStackMin());
      BlockPos maxDisplacement = OperationGeometry.stackDisplacement(bounds, operationState.operationStackMax());
      center = center.add(
         (minDisplacement.getX() + maxDisplacement.getX()) * 0.5,
         (minDisplacement.getY() + maxDisplacement.getY()) * 0.5,
         (minDisplacement.getZ() + maxDisplacement.getZ()) * 0.5
      ).add(Vec3.atLowerCornerOf(operationState.operationTranslation()));
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
         case MOVE -> axisComponent(Vec3.atLowerCornerOf(operationState.operationTranslation()), axis);
         case SCALE -> axisComponent(Vec3.atLowerCornerOf(operationState.operationStackVector()), axis);
         case ROTATE -> Math.toDegrees(axisComponent(operationState.operationRotation(), axis));
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
      InteractionContext context = InteractionContext.capture(Minecraft.getInstance());
      return InteractionIntentResolver.resolve(context, OPERATION_INTENT_PROVIDERS);
   }

   public static OperationInteractionIntent.Gizmo operationWorkspaceGizmoHit() {
      return operationInteractionIntent()
         .filter(OperationInteractionIntent.Gizmo.class::isInstance)
         .map(OperationInteractionIntent.Gizmo.class::cast)
         .orElse(null);
   }

   private static Optional<OperationInteractionIntent> resolveWorkspaceGizmoIntent(InteractionContext context) {
      if (!ClientOperationController.active() || context.alternative()) {
         return Optional.empty();
      }
      List<OperationInteractionIntent.Gizmo> targets = new ArrayList<>();
      var workspace = ClientOperationController.workspace();
      List<ClientSelectionPart> parts = workspace.parts();
      pruneWorkspaceResolvedCache(parts);
      for (ClientSelectionPart part : parts) {
         Map<BlockPos, io.github.fastformer.client.operation.ClientBlockSnapshot> resolved =
            resolveWorkspacePart(part);
         if (resolved.isEmpty()) {
            continue;
         }
         Vec3 center = OccupiedBlockBounds.from(resolved.keySet()).orElseThrow().center();
         GizmoViewScale scale = GizmoViewScale.fromDistance(context.camera().distanceTo(center));
         AxisGizmo gizmo = workspacePartGizmo(part, center, scale)
            .withTextComponent(GizmoTextComponent.pointLevel());
         AxisGizmo.Hit hit = gizmo.hitTest(context.eye(), context.view(), PREVIEW_REACH);
         if (hit != null) {
            targets.add(new OperationInteractionIntent.Gizmo(part.id(), false, gizmo, hit));
         }
      }
      if (workspace.selectedIds().size() > 1) {
         OccupiedBlockBounds group = workspace.selectedParts().stream()
            .map(FastPlaceClientPreview::resolveWorkspacePart)
            .filter(values -> !values.isEmpty())
            .map(values -> OccupiedBlockBounds.from(values.keySet()).orElseThrow())
            .reduce(OccupiedBlockBounds::union)
            .orElse(null);
         if (group != null) {
            Vec3 center = group.center();
            GizmoViewScale scale = GizmoViewScale.fromDistance(context.camera().distanceTo(center));
            boolean includesPrism = workspace.selectedParts().stream()
               .anyMatch(part -> part.selection() != null && part.selection().prism() != null);
            AxisGizmo gizmo = (includesPrism
               ? AxisGizmo.inFrame(
                  TransformFrame.world(center), scale.axisLength() * 1.12, scale.handleRadius() * 1.12,
                  AxisGizmo.Operation.MOVE, AxisGizmo.Operation.ROTATE
               )
               : AxisGizmo.inFrame(
                  TransformFrame.world(center), scale.axisLength() * 1.12, scale.handleRadius() * 1.12,
                  AxisGizmo.Operation.MOVE, AxisGizmo.Operation.SCALE, AxisGizmo.Operation.ROTATE
               )).withTextComponent(GizmoTextComponent.pointLevel());
            AxisGizmo.Hit hit = gizmo.hitTest(context.eye(), context.view(), PREVIEW_REACH);
            if (hit != null) {
               targets.add(new OperationInteractionIntent.Gizmo(0, true, gizmo, hit));
            }
         }
      }
      return targets.stream().min(java.util.Comparator
         .comparingDouble((OperationInteractionIntent.Gizmo target) -> target.hit().rayDistance())
         .thenComparingDouble(target -> target.hit().handleDistance()))
         .map(OperationInteractionIntent.class::cast);
   }

   private static AxisGizmo workspacePartGizmo(ClientSelectionPart part, Vec3 center, GizmoViewScale scale) {
      return part.orientedCuboid()
         ? AxisGizmo.inFrame(
            TransformFrame.world(center), scale.axisLength(), scale.handleRadius(),
            AxisGizmo.Operation.MOVE, AxisGizmo.Operation.ROTATE
         )
         : AxisGizmo.inFrame(
            TransformFrame.world(center), scale.axisLength(), scale.handleRadius(),
            AxisGizmo.Operation.MOVE, AxisGizmo.Operation.SCALE, AxisGizmo.Operation.ROTATE
         );
   }

   private static Optional<OperationInteractionIntent> resolveWorkspacePartIntent(InteractionContext context) {
      if (!ClientOperationController.active() || context.alternative()) {
         return Optional.empty();
      }
      int bestId = 0;
      double bestDistance = Double.POSITIVE_INFINITY;
      List<ClientSelectionPart> parts = ClientOperationController.workspace().parts();
      pruneWorkspaceResolvedCache(parts);
      for (ClientSelectionPart part : parts) {
         Map<BlockPos, io.github.fastformer.client.operation.ClientBlockSnapshot> resolved =
            resolveWorkspacePart(part);
         if (resolved.isEmpty()) {
            continue;
         }
         OccupiedBlockBounds occupied = OccupiedBlockBounds.from(resolved.keySet()).orElseThrow();
         AABB bounds = occupied.aabb();
         OperationGeometry.RayHit hit = OperationGeometry.raycast(
            bounds.inflate(0.015), context.eye(), context.view(), PREVIEW_REACH
         );
         boolean frameHit = hit != null && (context.control() || nearAabbEdge(hit.point(), bounds, 0.12));
         Vec3 label = occupied.center().add(0.0, 0.22, 0.0);
         double labelRayDistance = Math.max(0.0, label.subtract(context.eye()).dot(context.view()));
         double labelDistance = context.eye().add(context.view().scale(labelRayDistance)).distanceTo(label);
         boolean labelHit = labelRayDistance <= PREVIEW_REACH && labelDistance <= 0.22;
         double distance = labelHit ? labelRayDistance : frameHit ? hit.distance() : Double.POSITIVE_INFINITY;
         if (distance < bestDistance) {
            bestDistance = distance;
            bestId = part.id();
         }
      }
      return bestId == 0
         ? Optional.empty()
         : Optional.of(new OperationInteractionIntent.Part(bestId, bestDistance));
   }

   private static Optional<OperationInteractionIntent> resolveDraggedWorkspaceFaceIntent(InteractionContext context) {
      OperationGeometry.RayHit dragged = FastPlaceClientInput.workspaceFaceDragHit();
      int draggedPartId = FastPlaceClientInput.workspaceFaceDragPartId();
      if (dragged != null && draggedPartId > 0) {
         ClientSelectionPart part = ClientOperationController.workspace().part(draggedPartId).orElse(null);
         if (part != null) {
            AABB bounds = workspaceSelectionBounds(part);
            if (bounds != null) {
               return Optional.of(new OperationInteractionIntent.Face(draggedPartId, bounds, dragged, true));
            }
         }
      }
      return Optional.empty();
   }

   private static Optional<OperationInteractionIntent> resolveWorkspaceFaceIntent(InteractionContext context) {
      if (!ClientOperationController.active() || context.control() || context.alternative()) {
         return Optional.empty();
      }
      OperationInteractionIntent.Face best = null;
      for (ClientSelectionPart part : ClientOperationController.workspace().parts()) {
         AABB bounds = workspaceSelectionBounds(part);
         if (bounds == null) {
            continue;
         }
         OperationGeometry.RayHit hit = OperationGeometry.raycast(
            bounds.inflate(0.012), context.eye(), context.view(), PREVIEW_REACH
         );
         if (hit != null && (best == null || hit.distance() < best.hit().distance())) {
            best = new OperationInteractionIntent.Face(
               part.id(), bounds, hit, ClientOperationController.canAdjustAabbFace(part)
            );
         }
      }
      return Optional.ofNullable(best).map(OperationInteractionIntent.class::cast);
   }

   private static Optional<OperationInteractionIntent> resolveSelectionCreateIntent(InteractionContext context) {
      if (context.alternative()) {
         return Optional.empty();
      }
      BlockPos point;
      boolean workspaceCreation = ClientOperationController.active()
         && (ClientOperationController.activeSelectionTransformed() || context.control());
      if (workspaceCreation) {
         if (context.minecraft().level == null) {
            return Optional.empty();
         }
         BlockHitResult hit = LongRangeBlockRaycast.clip(
            context.minecraft().level, context.player(), context.eye(), context.view()
         ).hit();
         point = hit.getType() == Type.BLOCK ? hit.getBlockPos() : null;
      } else {
         if (context.nearVanillaBlock()
            || !operationState.active() || operationSelectionConfirmed()
            || operationCuboid() && !operationNeedsFirst() && !operationNeedsSecond()) {
            return Optional.empty();
         }
         point = operationCandidatePoint();
      }
      return point == null
         ? Optional.empty()
         : Optional.of(new OperationInteractionIntent.CreateSelection(point));
   }

   private static AABB workspaceSelectionBounds(ClientSelectionPart part) {
      if (part == null) {
         return null;
      }
      if (part.selection() != null && part.axisAlignedCuboid()
         && part.transform().rotation().equals(Vec3.ZERO)) {
         Vec3 translation = part.transform().translation();
         return part.selection().bounds().move(translation.x, translation.y, translation.z);
      }
      Map<BlockPos, io.github.fastformer.client.operation.ClientBlockSnapshot> resolved = resolveWorkspaceBasePart(part);
      OccupiedBlockBounds occupied = OccupiedBlockBounds.from(resolved.keySet()).orElse(null);
      return occupied == null ? null : occupied.aabb();
   }

   private static Map<BlockPos, io.github.fastformer.client.operation.ClientBlockSnapshot> resolveWorkspacePart(
      ClientSelectionPart part
   ) {
      return WORKSPACE_RESOLVED_CACHE.computeIfAbsent(part, WorkspacePreviewComposer::resolve);
   }

   private static Map<BlockPos, io.github.fastformer.client.operation.ClientBlockSnapshot> resolveWorkspaceBasePart(
      ClientSelectionPart part
   ) {
      if (part.transform().repeats().equals(io.github.fastformer.fastplace.OperationStackRegion.origin())) {
         return resolveWorkspacePart(part);
      }
      return WorkspacePreviewComposer.resolveValues(part.blocks(), part.transform().withoutRepeats());
   }

   private static void pruneWorkspaceResolvedCache(List<ClientSelectionPart> currentParts) {
      Set<ClientSelectionPart> live = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
      live.addAll(currentParts);
      WORKSPACE_RESOLVED_CACHE.keySet().removeIf(part -> !live.contains(part));
   }

   private static boolean nearAabbEdge(Vec3 point, AABB bounds, double threshold) {
      int boundaryAxes = 0;
      if (Math.min(Math.abs(point.x - bounds.minX), Math.abs(point.x - bounds.maxX)) <= threshold) {
         boundaryAxes++;
      }
      if (Math.min(Math.abs(point.y - bounds.minY), Math.abs(point.y - bounds.maxY)) <= threshold) {
         boundaryAxes++;
      }
      if (Math.min(Math.abs(point.z - bounds.minZ), Math.abs(point.z - bounds.maxZ)) <= threshold) {
         boundaryAxes++;
      }
      return boundaryAxes >= 2;
   }

   public static OperationGeometry.RayHit operationFaceHit() {
      if (!operationCuboid() || operationSelectionConfirmed() || FastPlaceClientInput.modifierHeld()) {
         return null;
      }
      OperationPointerTarget target = operationPointerTarget();
      return target.kind() == OperationPointerKind.FACE ? target.face() : null;
   }

   private static OperationGeometry.RayHit operationFaceTarget(OperationSelectionVolume selection) {
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

   public enum OperationPointerKind {
      WORLD,
      FACE,
      NONE
   }

   public record OperationPointerTarget(
      OperationPointerKind kind, BlockPos block, OperationGeometry.RayHit face, double distance
   ) {
      static OperationPointerTarget world(BlockPos block, double distance) {
         return new OperationPointerTarget(OperationPointerKind.WORLD, block, null, distance);
      }

      static OperationPointerTarget face(OperationGeometry.RayHit face) {
         return new OperationPointerTarget(OperationPointerKind.FACE, null, face, face.distance());
      }

      static OperationPointerTarget none() {
         return new OperationPointerTarget(OperationPointerKind.NONE, null, null, Double.POSITIVE_INFINITY);
      }
   }

   public static boolean geometryActive() {
      return geometryState.active();
   }

   public static boolean geometryAwaitingFirstPoint() {
      return geometryState.active() && geometryState.points().isEmpty();
   }

   public static boolean geometryPointSelected() {
      return geometryState.active() && geometryState.selectedPointIndex() >= 0;
   }

   public static boolean buildingRaycastSubmodeAvailable() {
      if (!buildingState.enabled()) {
         return false;
      }
      if (!buildingState.active()) {
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
      if (!geometryState.active() || player == null || InteractionContext.nearVanillaBlock(minecraft)) {
         return null;
      }
      Vec3 eye = player.getEyePosition();
      Vec3 view = player.getViewVector(1.0F);
      GeometryWorkflowView workflowView = geometryWorkflowView(view);
      List<GeometryInteractionTarget> targets = GeometryWorkflows.get(geometryState.mode()).interactionTargets(workflowView);
      return GeometryInteractionHit.nearest(eye, view, visiblePreviewReach(player), targets);
   }

   public static boolean embeddedModifierReticle() {
      if (FastPlaceClientInput.modifierHeld() && geometryState.active()) {
         return geometryState.mode() == GeometryMode.WALL;
      }
      return !geometryState.active()
         && !operationState.active()
         && buildingRaycastSubmode()
         && FastPlaceClientInput.modifierHeld();
   }

   public static boolean halfGridModifierReticle() {
      if (buildingRaycastSubmode()) {
         return false;
      }
      if (!geometryState.active()) {
         return false;
      }
      return switch (geometryState.mode()) {
         case POLYHEDRON -> !geometryState.closed();
         case CONE_PRISM -> geometryState.conePlaneMode().stageFor(geometryState.points().size()) != ConePrismStage.ADJUST;
         case CONVEX_POLYHEDRON -> true;
         default -> false;
      };
   }

   public static AxisGizmo.Hit geometryGizmoHit() {
      Minecraft minecraft = Minecraft.getInstance();
      LocalPlayer player = minecraft.player;
      if (!geometryState.active() || player == null || InteractionContext.nearVanillaBlock(minecraft)) {
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
      if (axis == null || operation == null || player == null || !geometryState.active()) {
         return operation == AxisGizmo.Operation.SCALE ? 1.0 : 0.0;
      }
      GeometryPreviewPlan plan = geometryPreviewPlan(player.getViewVector(1.0F));
      return geometryGizmoValue(plan, axis, operation);
   }

   private static AxisGizmo geometryGizmo(Vec3 view) {
      Minecraft minecraft = Minecraft.getInstance();
      if (!geometryState.active() || minecraft.player == null || InteractionContext.nearVanillaBlock(minecraft)) {
         return null;
      }
      return geometryPreviewPlan(view).gizmo();
   }

   public static BlockPos geometryPointUnderCrosshair() {
      if (!geometryState.active()
         || geometryState.mode() != GeometryMode.WALL
         || geometryState.closed()
         || geometryState.points().isEmpty()) {
         return null;
      }
      return pointUnderCrosshair(List.of(geometryState.points().getFirst()));
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
      if (geometryState.active()) {
         return geometryCandidatePoint(minecraft, player);
      }
      if (operationState.active()) {
         return operationCandidatePoint();
      }
      return buildingState.active() ? buildingCandidatePoint(buildingState, player) : null;
   }

   private static List<BlockPos> closablePathPoints(int minimumPoints) {
      if (operationPrismBaseOpen() && operationState.points().size() >= minimumPoints) {
         return operationState.points();
      }
      if (geometryState.active()
         && geometryState.mode() == GeometryMode.WALL
         && !geometryState.closed()
         && geometryState.points().size() >= minimumPoints) {
         return geometryState.points();
      }
      if (buildingState.active()
         && buildingState.faceMode() == FaceMode.POLYGON
         && !buildingState.polygonClosed()
         && buildingState.points().size() >= minimumPoints) {
         return buildingState.points();
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
      BuildingPreviewPayload snapshot = buildingState;
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
      OperationPreviewPayload snapshot = operationState;
      return snapshot.active()
         ? OperationSelectionVolume.create(
            snapshot.operationSelectionMode(),
            snapshot.points(),
            snapshot.operationPrismBasePointCount(),
            snapshot.operationMinOffset(),
            snapshot.operationMaxOffset(),
            snapshot.operationHullInflation()
         )
         : null;
   }

   public static boolean usesAngleDistance() {
      if (!buildingState.enabled()) {
         return false;
      } else {
         FastPlaceStage stage = buildingState.active() ? FastPlaceGeometry.stageFor(buildingState.points()) : FastPlaceStage.POINT;
         return stage == FastPlaceStage.LINE && buildingState.lineMode() == LineMode.FREE_SCROLL;
      }
   }

   public static boolean usesScrollContext() {
      if (geometryState.active()) {
         return geometryAllows(GeometryAction.SCALAR_ADJUST);
      } else if (operationState.active()) {
         return false;
      } else if (!buildingState.active()) {
         return false;
      } else {
         FastPlaceStage stage = effectiveStage(buildingState);
         return usesAngleDistance()
            || stage == FastPlaceStage.FACE && buildingState.faceMode() == FaceMode.PARALLELOGRAM_BASE_PLANE
            || stage == FastPlaceStage.VOLUME && FastPlaceGeometry.usesVolumeOffset(buildingState.modes());
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
         SmoothReticlePostEffect.updateTarget(FastPlaceClientInput.ModifierReticleMode.NONE);
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
      BuildingPreviewPayload snapshot = buildingState;
      if (!(snapshot.enabled() || geometryState.active() || operationState.active() || activityState.task())) {
         restoreVanillaCrosshairIfNeeded(graphics);
         smoothReticleFrame = false;
         return;
      }

      Vec3 view = minecraft.player == null ? new Vec3(0.0, 0.0, 1.0) : minecraft.player.getViewVector(1.0F);
      RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
      try {

      MutableComponent fill = Component.translatable(
            "fastformer.message.fill_mode",
            Component.translatable((geometryState.active() ? geometryState.fillMode() : snapshot.fillMode()).translationKey()),
            keyName(minecraft.options.keySwapOffhand)
      )
      .withStyle(ChatFormatting.AQUA);
      graphics.drawString(minecraft.font, fill, 8, 8, -1, true);

      MutableComponent embeddedHint = buildingRaycastHint();
      if (embeddedHint != null && minecraft.screen == null && !activityState.task()) {
         int x = Math.max(8, graphics.guiWidth() - minecraft.font.width(embeddedHint) - 8);
         graphics.drawString(minecraft.font, embeddedHint, x, 8, 0xFFAAAAAA, true);
      }
      if (activityState.task()) {
         MutableComponent task = Component.translatable(activityState.translationKey()).withStyle(ChatFormatting.GREEN);
         if (activityState.cancellable()) {
            String hintKey = activityState == FastPlaceActivity.RESTORE_TASK
               ? "fastformer.activity.pause_hint"
               : "fastformer.activity.cancel_hint";
            task.append(Component.literal(" ").append(Component.translatable(hintKey)).withStyle(ChatFormatting.GRAY));
         }
         graphics.drawString(minecraft.font, task, 8, 20, -1, true);
      }

      GeometryPreviewPlan geometryPlan = null;
      if (!activityState.task()) {
         geometryPlan = renderSessionHud(graphics, minecraft, view);
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
      return geometryState.active()
         && GeometryWorkflows.allows(geometryWorkflowView(new Vec3(0.0, 0.0, 1.0)), action);
   }

   private static GeometryPreviewPlan renderSessionHud(GuiGraphics graphics, Minecraft minecraft, Vec3 view) {
      if (geometryState.active()) {
         GeometryPreviewPlan geometryPlan = geometryPreviewPlan(view);
         renderGeometryTextBlocks(graphics, minecraft, geometryPlan);
         renderScrollFeedbackBottom(graphics, minecraft);
         return geometryPlan;
      }

      MutableComponent primary;
      MutableComponent secondary = null;
      if (operationState.active()) {
         primary = operationBottomStatus();
      } else {
         primary = buildingBottomStatus();
         secondary = buildingBottomHint();
      }
      renderScrollFeedbackBottom(graphics, minecraft);
      if (primary != null && !primary.getString().isBlank()) {
         int bottomOffset = operationState.active() ? 48 : 64;
         graphics.drawCenteredString(
            minecraft.font, primary, graphics.guiWidth() / 2, graphics.guiHeight() - bottomOffset, -1
         );
      }
      if (secondary != null && !secondary.getString().isBlank()) {
         graphics.drawCenteredString(minecraft.font, secondary, graphics.guiWidth() / 2, graphics.guiHeight() - 50, -1);
      }
      return null;
   }

   private static void renderGeometryTextBlocks(
      GuiGraphics graphics, Minecraft minecraft, GeometryPreviewPlan plan
   ) {
      if (plan == null) {
         return;
      }
      int topLeftLine = 0;
      int topRightLine = 0;
      int bottomCenterLine = 0;
      int bottomHintLine = 0;
      int crosshairLine = 0;
      for (GeometryTextBlock block : plan.textBlocks()) {
         if (!block.visible() || !GeometryTextBlock.hasContent(block.content()) || isTextLayoutControl(block.id())) {
            continue;
         }
         Component content = block.content();
         if (GeometryTextBlock.HINT_ID.equals(block.id())) {
            content = content.copy().withStyle(ChatFormatting.GRAY);
         }
         switch (block.placement()) {
            case TOP_LEFT -> {
               graphics.drawString(minecraft.font, content, 8, 32 + topLeftLine++ * 12, -1, true);
            }
            case TOP_RIGHT -> {
               int x = Math.max(8, graphics.guiWidth() - minecraft.font.width(content) - 8);
               graphics.drawString(minecraft.font, content, x, 32 + topRightLine++ * 12, -1, true);
            }
            case BOTTOM_CENTER -> {
               graphics.drawCenteredString(
                  minecraft.font,
                  content,
                  graphics.guiWidth() / 2,
                  graphics.guiHeight() - 64 - bottomCenterLine++ * 12,
                  -1
               );
            }
            case BOTTOM_HINT -> {
               graphics.drawCenteredString(
                  minecraft.font,
                  content,
                  graphics.guiWidth() / 2,
                  graphics.guiHeight() - 50 - bottomHintLine++ * 12,
                  -1
               );
            }
            case CROSSHAIR -> {
               graphics.drawString(
                  minecraft.font,
                  content,
                  graphics.guiWidth() / 2 + 10,
                  graphics.guiHeight() / 2 + 6 + crosshairLine++ * 12,
                  -1,
                  true
               );
            }
         }
      }
   }

   private static boolean isTextLayoutControl(String id) {
      return GeometryTextBlock.STAGE_ID.equals(id)
         || GeometryTextBlock.MODE_ID.equals(id)
         || GeometryTextBlock.VALUE_ID.equals(id);
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
      return new TransformStatus(
         current.position().subtract(baseline.position()),
         divideScale(current.scale(), baseline.scale()),
         new Vec3(
            cleanDegreeDelta(current.rotationDegrees().x - baseline.rotationDegrees().x),
            cleanDegreeDelta(current.rotationDegrees().y - baseline.rotationDegrees().y),
            cleanDegreeDelta(current.rotationDegrees().z - baseline.rotationDegrees().z)
         )
      );
   }

   private static TransformStatus rawTransformStatus(GeometryPreviewPlan geometryPlan) {
      if (!geometryAdjusting(geometryState)) {
         return null;
      }
      if (geometryState.mode() == GeometryMode.CONE_PRISM) {
         ConePrismGeometry geometry = coneGeometry(geometryState);
         if (geometry == null || !geometry.heightReady()) {
            return null;
         }
         Vec3 center = geometry.base().center().add(geometry.topCenter()).scale(0.5);
         return new TransformStatus(
            center,
            new Vec3(geometry.scaleX(), Math.max(0.5, Math.abs(geometry.height())), geometry.scaleZ()),
            new Vec3(0.0, Math.toDegrees(geometryState.coneRotationRadians()), 0.0)
         );
      }
      if (geometryState.mode() == GeometryMode.POLYHEDRON) {
         Vec3 center = transformCenter(geometryPlan);
         if (center == null) {
            return null;
         }
         Vec3 scale = geometryState.polyhedronGizmoLocal()
            ? geometryState.polyhedronLocalScale()
            : geometryState.polyhedronWorldScale();
         return new TransformStatus(center, scale, eulerDegrees(geometryState.rotation()));
      }
      return null;
   }

   private static Vec3 transformCenter(GeometryPreviewPlan geometryPlan) {
      if (geometryPlan != null && geometryPlan.gizmo() != null) {
         return geometryPlan.gizmo().center();
      }
      return geometryState.pointLocations().isEmpty() ? null : geometryState.pointLocations().getFirst();
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

   private static Vec3 divideScale(Vec3 value, Vec3 baseline) {
      return new Vec3(
         value.x / Math.max(EPSILON, baseline.x),
         value.y / Math.max(EPSILON, baseline.y),
         value.z / Math.max(EPSILON, baseline.z)
      );
   }

   private static double cleanDegreeDelta(double value) {
      return GeometryNumbers.cleanZero(Math.IEEEremainder(value, 360.0));
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

   private static Vec3 eulerDegrees(double[] rotation) {
      if (rotation == null || rotation.length != 9) {
         return Vec3.ZERO;
      }
      double y = Math.asin(Math.clamp(-rotation[6], -1.0, 1.0));
      double x;
      double z;
      if (Math.abs(Math.cos(y)) > 1.0E-6) {
         x = Math.atan2(rotation[7], rotation[8]);
         z = Math.atan2(rotation[3], rotation[0]);
      } else {
         x = Math.atan2(-rotation[5], rotation[4]);
         z = 0.0;
      }
      return new Vec3(cleanDegrees(x), cleanDegrees(y), cleanDegrees(z));
   }

   private static double cleanDegrees(double radians) {
      return GeometryNumbers.cleanZero(Math.IEEEremainder(Math.toDegrees(radians), 360.0));
   }

   private static String formatCoordinate(double value) {
      double rounded = Math.rint(GeometryNumbers.finiteOr(value, 0.0));
      return Math.abs(value - rounded) < EPSILON
         ? GeometryNumbers.fixed(rounded, 0)
         : GeometryNumbers.fixed(value, 1);
   }

   private static String formatScale(double value) {
      double rounded = Math.rint(GeometryNumbers.finiteOr(value, 1.0));
      return Math.abs(value - rounded) < EPSILON
         ? GeometryNumbers.fixed(rounded, 0)
         : GeometryNumbers.fixed(value, 2);
   }

   private static String formatRotation(double value) {
      double rounded = Math.rint(GeometryNumbers.finiteOr(value, 0.0));
      return (Math.abs(value - rounded) < EPSILON
         ? GeometryNumbers.fixed(rounded, 0)
         : GeometryNumbers.fixed(value, 1)) + "\u00b0";
   }

   private static MutableComponent buildingBottomStatus() {
      if (!buildingState.active()) {
         return null;
      }

      FastPlaceStage stage = effectiveStage(buildingState);
      MutableComponent status = Component.translatable(stage.translationKey()).withStyle(ChatFormatting.WHITE);
      status.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY));
      if (buildingState.polygonClosed()) {
         PolygonVolumeShape selected = buildingState.polygonVolumeShape();
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
            buildingState.lineMode(),
            buildingState.faceMode()
         );
         FastPlaceMode selected = selectedMode(buildingState, stage);
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
      if (geometryState.active()
         || operationState.active()
         || !buildingRaycastSubmodeAvailable()
         || !FastPlaceClientInput.modifierHeld()
         || buildingState.raycastPlacement() == RaycastPlacement.EMBEDDED) {
         return null;
      }
      return Component.translatable("fastformer.hud.raycast_embedded_hint");
   }

   private static boolean buildingRaycastSubmode() {
      if (geometryState.active() || operationState.active()) {
         return false;
      }
      return switch (effectiveStage(buildingState)) {
         case POINT -> buildingState.pointMode() == PointMode.RAYCAST;
         case LINE -> buildingState.lineMode() == LineMode.RAYCAST;
         default -> false;
      };
   }

   private static MutableComponent buildingBottomHint() {
      if (!buildingState.active()) {
         return null;
      }
      FastPlaceStage stage = effectiveStage(buildingState);
      if (stage == FastPlaceStage.FACE && buildingState.faceMode() == FaceMode.POLYGON && !buildingState.polygonClosed()) {
         return Component.translatable("fastformer.message.polygon_close_hint").withStyle(ChatFormatting.GRAY);
      }
      return Component.translatable(
         stage == FastPlaceStage.VOLUME
            ? buildingState.polygonClosed()
               ? "fastformer.message.building_hint_height"
               : "fastformer.message.building_hint_points"
            : "fastformer.message.building_hint_points"
      ).withStyle(ChatFormatting.GRAY);
   }

   private static MutableComponent operationBottomStatus() {
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
               mode == operationState.operationStageMode() ? ChatFormatting.GREEN : ChatFormatting.GRAY
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
            mode == operationState.operationSelectionMode() ? ChatFormatting.GREEN : ChatFormatting.GRAY
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
      if (operationState.active()
         && operationSelectionReady()
         && operationFaceHit() != null
         && operationGizmoHit() == null
         && operationPointUnderCrosshairIndex() < 0) {
         graphics.drawString(minecraft.font, Component.translatable("fastformer.hud.selection.push"), x, y, 0xFFFFFFFF, true);
         graphics.drawString(minecraft.font, Component.translatable("fastformer.hud.selection.pull"), x, y + 10, 0xFFFFFFFF, true);
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
            gizmoAlpha = GIZMO_FEEDBACK.alpha(System.nanoTime());
            retainedGizmo = gizmoAlpha > 0 && axis == lastGizmoFeedbackAxis;
         } else {
            gizmoAlpha = GIZMO_FEEDBACK.alpha(System.nanoTime());
            if (gizmoAlpha > 0) {
               axis = lastGizmoFeedbackAxis;
               operation = lastGizmoFeedbackOperation;
               retainedGizmo = axis != null;
            }
         }
      }
      if (axis != null && operation != null) {
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
               : lastGizmoFeedbackBaseValue;
            double currentValue = gizmoHudValue(hudInput, geometryPlan, axis, operation);
            int steps = dragAxis != null ? hudInput.dragSteps() : lastGizmoFeedbackSteps;
            GizmoTextContext context = hudInput.operationSelection() && operationSelectionReady()
               ? operationTransformTextContext(axis, operation, baseValue, currentValue, steps)
               : gizmoTextContext(axis, operation, baseValue, currentValue, steps);
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
                  : lastGizmoFeedbackBaseValue;
               double currentValue = gizmoHudValue(hudInput, geometryPlan, axis, operation);
               String value = formatGizmoHudValue(
                  dragAxis != null ? hudInput.dragOperation() : lastGizmoFeedbackOperation,
                  baseValue,
                  currentValue,
                  dragAxis != null ? hudInput.dragSteps() : lastGizmoFeedbackSteps
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
      if (operationState.active()) {
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
         Component.translatable(transformLabelKey(operation)),
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

   private static String transformLabelKey(AxisGizmo.Operation operation) {
      return switch (operation) {
         case MOVE -> "fastformer.hud.transform.position";
         case SCALE -> "fastformer.hud.transform.scale";
         case ROTATE -> "fastformer.hud.transform.rotation";
      };
   }

   private static GizmoTextContext operationTransformTextContext(
      AxisGizmo.Axis axis,
      AxisGizmo.Operation operation,
      double baseValue,
      double currentValue,
      int fallbackSteps
   ) {
      GizmoTextContext generic = gizmoTextContext(axis, operation, baseValue, currentValue, fallbackSteps);
      String operationName = Component.translatable(
         operation == AxisGizmo.Operation.SCALE
            ? "fastformer.hud.transform.stack"
            : transformLabelKey(operation)
      ).getString();
      return new GizmoTextContext(
         generic.axis(), operationName, generic.base(), generic.delta(), generic.current(), generic.direction()
      );
   }

   private static String formatGizmoHudValue(
      AxisGizmo.Operation operation, double baseValue, double currentValue, int fallbackSteps
   ) {
      GizmoTextContext context = gizmoTextContext(AxisGizmo.Axis.X, operation, baseValue, currentValue, fallbackSteps);
      return context.base() + context.delta();
   }

   private static GizmoTextContext gizmoTextContext(
      AxisGizmo.Axis axis,
      AxisGizmo.Operation operation,
      double baseValue,
      double currentValue,
      int fallbackSteps
   ) {
      double delta = GeometryNumbers.cleanZero(currentValue - baseValue);
      if (Math.abs(delta) < EPSILON && fallbackSteps != 0) {
         delta = switch (operation) {
            case MOVE -> fallbackSteps * 0.5;
            case ROTATE -> fallbackSteps * 360.0 / 1024.0;
            case SCALE -> 0.0;
         };
      }
      String base = operation == AxisGizmo.Operation.SCALE ? formatScale(baseValue) : formatCoordinate(baseValue);
      String change = operation == AxisGizmo.Operation.SCALE
         ? formatScale(Math.abs(delta))
         : formatCoordinate(Math.abs(delta));
      String suffix = operation == AxisGizmo.Operation.ROTATE ? "\u00b0" : "";
      String deltaText = (delta < 0.0 ? "-" : "+") + change + suffix;
      String current = operation == AxisGizmo.Operation.SCALE ? formatScale(currentValue) : formatCoordinate(currentValue);
      String direction = delta < -EPSILON ? "NEGATIVE" : delta > EPSILON ? "POSITIVE" : "NONE";
      return new GizmoTextContext(
         axis.name(),
         Component.translatable(transformLabelKey(operation)).getString(),
         base,
         deltaText,
         current,
         direction
      );
   }

   private static void renderScrollFeedbackBottom(GuiGraphics graphics, Minecraft minecraft) {
      int alpha = SCROLL_FEEDBACK.alpha(System.nanoTime());
      if (alpha <= 0) {
         return;
      }
      ScrollFeedbackData data = scrollFeedbackData();
      if (data == null) {
         return;
      }
      int y = graphics.guiHeight() - 88;
      if (!data.axes().isEmpty()) {
         int spacing = 14;
         int width = data.axes().stream()
            .mapToInt(axis -> minecraft.font.width(axis.label() + ":" + axis.value()))
            .sum() + spacing * Math.max(0, data.axes().size() - 1);
         int x = (graphics.guiWidth() - width) / 2;
         for (AxisFeedback axis : data.axes()) {
            String text = axis.label() + ":" + axis.value();
            graphics.drawString(minecraft.font, text, x, y, withAlpha(axis.color(), alpha), true);
            x += minecraft.font.width(text) + spacing;
         }
      } else if (!data.text().isBlank()) {
         graphics.drawCenteredString(minecraft.font, data.text(), graphics.guiWidth() / 2, y, withAlpha(0xFFFFFFFF, alpha));
      }
   }

   private static ScrollFeedbackData scrollFeedbackData() {
      if (operationState.active() && operationSelectionReady()) {
         BlockPos value = operationSelectionConfirmed() && FastPlaceClientInput.modifierHeld()
            ? operationState.stackVector()
            : operationState.translation();
         return new ScrollFeedbackData(xyzFeedback(value), "");
      }
      if (geometryState.active() && geometryState.mode() == GeometryMode.CONE_PRISM) {
         Vec3 offset = geometryState.coneTopOffset();
         return new ScrollFeedbackData(
            List.of(
               new AxisFeedback("X", GeometryNumbers.fixed(offset.x, 2), gizmoAxisHudColor(AxisGizmo.Axis.X)),
               new AxisFeedback("Z", GeometryNumbers.fixed(offset.z, 2), gizmoAxisHudColor(AxisGizmo.Axis.Z))
            ),
            ""
         );
      }
      if (buildingState.active()) {
         FastPlaceStage stage = effectiveStage(buildingState);
         if (stage == FastPlaceStage.LINE && buildingState.lineMode() == LineMode.FREE_SCROLL) {
            return new ScrollFeedbackData(xyzFeedback(buildingState.freeScrollOffset()), "");
         }
         if (stage == FastPlaceStage.FACE && buildingState.faceMode() == FaceMode.PARALLELOGRAM_BASE_PLANE) {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
               return new ScrollFeedbackData(
                  List.of(),
                  GeometryNumbers.fixed(
                     FastPlaceGeometry.faceBaseOffsetValue(buildingState.points(), buildingState.faceBaseOffset(), player.getViewVector(1.0F)),
                     0
                  )
               );
            }
         }
         if (stage == FastPlaceStage.VOLUME && FastPlaceGeometry.usesVolumeOffset(effectiveBuildingModes(buildingState))) {
            return buildingState.volumeMode() == VolumeMode.FREE
               ? new ScrollFeedbackData(xyzFeedback(buildingState.volumeBaseOffset()), "")
               : new ScrollFeedbackData(List.of(), formatScalar(buildingState.volumeBaseOffset()));
         }
      }
      return null;
   }

   private static List<AxisFeedback> xyzFeedback(BlockPos value) {
      return List.of(
         new AxisFeedback("X", Integer.toString(value.getX()), gizmoAxisHudColor(AxisGizmo.Axis.X)),
         new AxisFeedback("Y", Integer.toString(value.getY()), gizmoAxisHudColor(AxisGizmo.Axis.Y)),
         new AxisFeedback("Z", Integer.toString(value.getZ()), gizmoAxisHudColor(AxisGizmo.Axis.Z))
      );
   }

   private static List<AxisFeedback> xyzFeedback(Vec3 value) {
      Vec3 clean = GeometryNumbers.cleanZero(value);
      return List.of(
         new AxisFeedback("X", formatCoordinate(clean.x), gizmoAxisHudColor(AxisGizmo.Axis.X)),
         new AxisFeedback("Y", formatCoordinate(clean.y), gizmoAxisHudColor(AxisGizmo.Axis.Y)),
         new AxisFeedback("Z", formatCoordinate(clean.z), gizmoAxisHudColor(AxisGizmo.Axis.Z))
      );
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

   @SubscribeEvent
   public static void onRenderLevelStage(RenderLevelStageEvent event) {
      BuildingPreviewPayload snapshot = buildingState;
      if (event.getStage() == Stage.AFTER_PARTICLES) {
          Minecraft minecraft = Minecraft.getInstance();
          LocalPlayer player = minecraft.player;
          boolean emptyBuildingPreview = snapshot.enabled()
             && player != null
             && PlaceableItems.isPlaceable(player.getMainHandItem());
          if (!(snapshot.active() || emptyBuildingPreview || operationState.active()
             || ClientOperationController.active() || geometryState.active())) {
             return;
          }
          if (emptyBuildingPreview && InteractionContext.nearVanillaBlock(minecraft)) {
             WORLD_PREVIEW_OPACITY.reset(true);
             return;
          }
          worldPreviewOpacity = updateWorldPreviewOpacity(minecraft, System.nanoTime());
          if (worldPreviewOpacity <= 0.01F) {
             return;
         }
         if (minecraft.level != null && player != null && geometryState.active()) {
            renderGeometryWall(event, minecraft);
         } else if (minecraft.level != null && player != null
            && (operationState.active() || ClientOperationController.active())) {
            OperationInteractionIntent pointerIntent = operationInteractionIntent().orElse(null);
            // The confirmed selection is retained as source data, but the workspace owns
            // its overlay and hit targets from this point on.
            if (operationState.active() && !ClientOperationController.active()) {
               renderOperationSelection(event, minecraft, player, operationState);
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
            boolean closing = isBuildingClosingCandidate(snapshot, candidate, hoveredPoint);
            if (!snapshot.polygonHeightConfirmed()
               && (candidate != null || closing)) {
               BlockPos previewPoint = closing ? snapshot.points().getFirst() : candidate;
               if (previewPoints.isEmpty() || !previewPoint.equals(previewPoints.getLast())) {
                  previewPoints.add(previewPoint);
               }
            }

             boolean polygonHeightConfirmed = snapshot.polygonHeightConfirmed()
                || snapshot.polygonClosed() && previewPoints.size() > snapshot.points().size();
             Set<BlockPos> previewBlocks = buildingPreviewBlocksCached(snapshot, previewPoints, polygonHeightConfirmed);
             Set<BlockPos> candidateBlocks = buildingCandidateBlocks(snapshot, candidate, hoveredPoint);
             BuildingRenderLayers layers = buildingRenderLayers(snapshot, previewBlocks, candidateBlocks, candidate, hoveredPoint);
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
            BlockState previewState = PlaceableItems.placementState(
               player.getMainHandItem(), player, previewPlacementContext(snapshot, player)
            ).orElse(null);
            List<ControlPoint> buildingPoints = buildingControlPoints(snapshot, candidate, hoveredPoint);
            Map<BlockPos, BuildingSpecialBlock> specialBlockStyles = buildingSpecialBlockStyles(
               snapshot, candidate, hoveredPoint, layers.allBlocks()
            );
            HashSet<BlockPos> confirmedPreviewBlocks = new HashSet<>(layers.confirmedRenderBlocks());
            specialBlockStyles.forEach((pos, special) -> {
               if (special.confirmed()) {
                  confirmedPreviewBlocks.add(pos);
               }
            });
            HashSet<BlockPos> pendingPreviewBlocks = new HashSet<>(layers.pendingRenderBlocks());
            for (ControlPoint point : buildingPoints) {
               if (!point.confirmed()) {
                  pendingPreviewBlocks.add(BlockPos.containing(point.center()));
               }
            }
            Set<BlockPos> shapeEnvironment = unionBlocks(confirmedPreviewBlocks, pendingPreviewBlocks);
            renderBuildingShells(
               player,
               poseStack,
               buffers,
               camera,
               previewState,
               confirmedPreviewBlocks,
               pendingPreviewBlocks,
               shapeEnvironment,
               specialBlockStyles
            );
            renderBuildingFallbackPoints(
               poseStack, buffers, camera, buildingPoints, shapeEnvironment
            );
            renderBuildingGuidePlaneGrid(poseStack, buffers.getBuffer(RenderType.lines()), camera, planes);
            renderBuildingGuideLines(poseStack, buffers.getBuffer(RenderType.lines()), camera, lines);
            buffers.endBatch(RenderType.lines());
         }
      }
   }

   private static void renderOperationSelection(RenderLevelStageEvent event, Minecraft minecraft, LocalPlayer player, OperationPreviewPayload snapshot) {
      List<BlockPos> points = snapshot.points();
      boolean closingPrismBase = snapshot.operationSelectionMode() == OperationSelectionMode.PRISM
         && snapshot.operationPrismBasePointCount() == 0
         && points.size() >= 3
         && operationPointUnderCrosshairIndex() == 0;
      SelectionPrism.EdgeInsertion edgeInsertion = closingPrismBase ? null : operationPrismEdgeInsertion();
      BlockPos candidate = closingPrismBase
         ? null
         : edgeInsertion != null
         ? edgeInsertion.point()
         : operationSelectionReady() ? null : operationCandidatePoint();
      List<BlockPos> previewPoints = new ArrayList<>(points);
      if (closingPrismBase) {
         previewPoints.add(points.getFirst());
      } else if (edgeInsertion == null && candidate != null && !previewPoints.contains(candidate)) {
         previewPoints.add(candidate);
      }

      OperationSelectionVolume selection = OperationSelectionVolume.create(
         snapshot.operationSelectionMode(),
         previewPoints,
         snapshot.operationPrismBasePointCount(),
         snapshot.operationMinOffset(),
         snapshot.operationMaxOffset(),
         snapshot.operationHullInflation()
      );
      AABB bounds = selection == null ? null : selection.bounds();
      Vec3 eye = player.getEyePosition();
      AxisGizmo gizmo = operationGizmo();
      OperationGeometry.RayHit hit = operationFaceTarget(selection);
      OperationGeometry.RayHit guideHit = OPERATION_FACE_INTERPOLATOR.update(hit, System.nanoTime());
      float pulse = ghostBreathPulse();
      float faceAlpha = GHOST_FACE_ALPHA_MIN + (GHOST_FACE_ALPHA_MAX - GHOST_FACE_ALPHA_MIN) * pulse;
      float outlineAlpha = GHOST_OUTLINE_ALPHA_MIN + (GHOST_OUTLINE_ALPHA_MAX - GHOST_OUTLINE_ALPHA_MIN) * pulse;
      BufferSource buffers = minecraft.renderBuffers().bufferSource();
      PoseStack poseStack = event.getPoseStack();
      Vec3 camera = event.getCamera().getPosition();
      poseStack.pushPose();
      poseStack.translate(-camera.x, -camera.y, -camera.z);

      if (selection != null) {
         renderOperationFaces(poseStack, buffers.getBuffer(GHOST_FACES), selection, faceAlpha, camera);
         if (hit != null) {
            renderOperationHighlightedFace(
               poseStack,
               buffers.getBuffer(GHOST_FACES),
               selection,
               hit,
               SELECTION_HIGHLIGHT_ALPHA,
               camera
            );
         }
         buffers.endBatch(GHOST_FACES);
         if (hit != null) {
            // Smooth the arrow anchor in the face plane while retaining the raycast normal.
            renderSelectionFaceNormal(
               poseStack, buffers.getBuffer(RenderType.lines()),
               guideHit == null ? hit : new OperationGeometry.RayHit(
                  guideHit.point(), hit.normal(), guideHit.distance(), hit.axis()
               ), camera, 0.82F
            );
            buffers.endBatch(RenderType.lines());
         }
      }

      VertexConsumer occludedLines = buffers.getBuffer(PENDING_XRAY_LINES);
      renderOperationSelectionPass(
         poseStack, occludedLines, snapshot, previewPoints, selection, bounds, guideHit, eye,
         SELECTION_XRAY_ALPHA, outlineAlpha
      );
      buffers.endBatch(PENDING_XRAY_LINES);

      VertexConsumer lines = buffers.getBuffer(RenderType.lines());
      renderOperationSelectionPass(
         poseStack, lines, snapshot, previewPoints, selection, bounds, guideHit, eye,
         1.0F, outlineAlpha
      );
      buffers.endBatch(RenderType.lines());

      poseStack.popPose();
      if (hit != null) {
         renderWorkspaceHintLabel(
            poseStack, buffers, minecraft, camera,
            hit.point().add(hit.normal().scale(0.08)),
            Component.translatable("fastformer.operation.face_drag_hint").getString()
         );
      }
      renderOperationPointDragGuides(poseStack, buffers, camera, snapshot);
      renderControlPoints(
         poseStack, buffers, camera, operationControlPoints(snapshot, candidate, edgeInsertion != null)
      );
      if (gizmo != null) {
         renderGeometryGizmo(poseStack, buffers, camera, gizmo, operationGizmoAlpha(gizmo));
      }
   }

   private static void renderClientOperationWorkspace(
      RenderLevelStageEvent event, Minecraft minecraft, OperationInteractionIntent pointerIntent
   ) {
      var workspace = ClientOperationController.workspace();
      if (minecraft.level == null || workspace.isEmpty()) {
         return;
      }
      PoseStack poseStack = event.getPoseStack();
      BufferSource buffers = minecraft.renderBuffers().bufferSource();
      Vec3 camera = event.getCamera().getPosition();
      float pulse = ghostBreathPulse();
      OperationInteractionIntent.Gizmo hoveredGizmo = pointerIntent instanceof OperationInteractionIntent.Gizmo gizmo
         ? gizmo : null;
      OperationInteractionIntent.Face hoveredFace = pointerIntent instanceof OperationInteractionIntent.Face face
         ? face : null;
      OperationGeometry.RayHit displayedWorkspaceFaceHit = hoveredFace == null
         ? WORKSPACE_FACE_INTERPOLATOR.update(null, System.nanoTime())
         : WORKSPACE_FACE_INTERPOLATOR.update(hoveredFace.hit(), System.nanoTime());
      int hoveredPartId = hoveredGizmo != null && !hoveredGizmo.common()
         ? hoveredGizmo.partId()
         : hoveredFace != null ? hoveredFace.partId()
         : pointerIntent instanceof OperationInteractionIntent.Part part ? part.partId() : 0;
      boolean controlPreview = FastPlaceClientInput.controlHeld();

      List<ClientSelectionPart> parts = workspace.parts();
      pruneWorkspaceResolvedCache(parts);
      for (ClientSelectionPart part : parts) {
         Map<BlockPos, io.github.fastformer.client.operation.ClientBlockSnapshot> resolved =
            resolveWorkspacePart(part);
         if (resolved.isEmpty()) {
            continue;
         }
         boolean selected = workspace.selectedIds().contains(part.id());
         boolean hovered = part.id() == hoveredPartId;
         OccupiedBlockBounds bounds = OccupiedBlockBounds.from(resolved.keySet()).orElseThrow();
         Map<BlockPos, io.github.fastformer.client.operation.ClientBlockSnapshot> baseResolved =
            resolveWorkspaceBasePart(part);
         OccupiedBlockBounds baseBounds = baseResolved.isEmpty() ? bounds
            : OccupiedBlockBounds.from(baseResolved.keySet()).orElseThrow();
         AABB selectionBounds = workspaceSelectionBounds(part);
         Vec3 selectedLineCenter = selectionBounds == null ? baseBounds.center() : selectionBounds.getCenter();
         Vec3 selectedLineHalfExtents = selectionBounds == null
            ? new Vec3(baseBounds.width(AxisGizmo.Axis.X) * 0.5,
               baseBounds.width(AxisGizmo.Axis.Y) * 0.5,
               baseBounds.width(AxisGizmo.Axis.Z) * 0.5)
            : new Vec3(selectionBounds.getXsize() * 0.5,
               selectionBounds.getYsize() * 0.5,
               selectionBounds.getZsize() * 0.5);
         poseStack.pushPose();
         poseStack.translate(-camera.x, -camera.y, -camera.z);
         Vec3 halfExtents = new Vec3(
            bounds.width(AxisGizmo.Axis.X) * 0.5 + 0.018,
            bounds.width(AxisGizmo.Axis.Y) * 0.5 + 0.018,
            bounds.width(AxisGizmo.Axis.Z) * 0.5 + 0.018
         );
         if (selected) {
            renderFlowingDashedBox(
               poseStack, buffers.getBuffer(RenderType.lines()), bounds.center(), halfExtents,
               pendingGridDashOffset() + part.id() * 0.31, hovered ? 0.48F : 0.38F
            );
            // A single selection has one frame. Avoid drawing the same frame twice,
            // which causes visible z-fighting with the common-frame pass.
            boolean distinctCommonFrame = selectedLineCenter.distanceToSqr(bounds.center()) > 1.0E-8
               || Math.abs(selectedLineHalfExtents.x - halfExtents.x) > 1.0E-8
               || Math.abs(selectedLineHalfExtents.y - halfExtents.y) > 1.0E-8
               || Math.abs(selectedLineHalfExtents.z - halfExtents.z) > 1.0E-8;
            if (distinctCommonFrame) {
               renderFlowingDashedBox(
                  poseStack, buffers.getBuffer(RenderType.lines()), selectedLineCenter,
                  selectedLineHalfExtents, camera,
                  -pendingGridDashOffset() + part.id() * 0.31, hovered ? 1.0F : 0.94F
               );
            }
         } else {
            LevelRenderer.renderLineBox(
               poseStack,
               buffers.getBuffer(RenderType.lines()),
               bounds.aabb().inflate(hovered ? 0.018 + pulse * 0.008 : 0.006),
               hovered ? 0.25F : 0.45F,
               hovered ? 1.0F : 0.72F,
               hovered ? 1.0F : 0.88F,
               hovered ? 1.0F : 0.52F
            );
         }
         if (part.source() == ClientSelectionPart.Source.WORLD && part.initialBounds() != null
            && !part.matchesInitialBounds()) {
            AABB initial = part.initialBounds();
            renderFlowingDashedBox(
               poseStack,
               buffers.getBuffer(GHOST_OUTLINE_LINES),
               initial.getCenter(),
               new Vec3(initial.getXsize() * 0.5, initial.getYsize() * 0.5, initial.getZsize() * 0.5),
               0.0,
               selected ? 0.24F : 0.14F
            );
         }
         poseStack.popPose();
         renderWorkspacePartLabel(
            poseStack, buffers, minecraft, camera, bounds.center(), part.id(), selected, hovered, controlPreview, pulse
         );

         boolean adjusted = !part.matchesInitialBounds() || part.transformed();
         if (!part.pendingDelete()) {
            if (part.source() == ClientSelectionPart.Source.WORLD && adjusted && !part.initialBlocks().isEmpty()) {
               renderWorkspaceBlocks(
                  poseStack, buffers, minecraft, camera, part.initialBlocks(),
                  0.72F, 0.82F, 0.88F, 0.18F
               );
            }
            renderWorkspaceBlocks(
               poseStack, buffers, minecraft, camera, resolved,
               1.0F, 1.0F, 1.0F, adjusted ? 0.58F + 0.30F * pulse : 0.34F + 0.16F * pulse
            );
         }
         if (part.pendingDelete()) {
            renderPendingDeleteBlocks(poseStack, buffers, camera, part.initialBlocks().keySet());
         }

         if (hoveredFace != null && hoveredFace.partId() == part.id()) {
            OperationSelectionVolume faceVolume = new OperationSelectionVolume(
               OperationSelectionMode.CUBOID, hoveredFace.bounds(), null, List.of(), 0
            );
            poseStack.pushPose();
            poseStack.translate(-camera.x, -camera.y, -camera.z);
            renderOperationHighlightedFace(
               poseStack, buffers.getBuffer(GHOST_FACES), faceVolume, displayedWorkspaceFaceHit,
               SELECTION_HIGHLIGHT_ALPHA + pulse * 0.12F, camera
            );
            buffers.endBatch(GHOST_FACES);
            renderSelectionFaceNormal(
               poseStack, buffers.getBuffer(RenderType.lines()), displayedWorkspaceFaceHit, camera, 0.9F
            );
            buffers.endBatch(RenderType.lines());
            poseStack.popPose();
            renderWorkspaceHintLabel(
               poseStack, buffers, minecraft, camera,
               displayedWorkspaceFaceHit.point().add(displayedWorkspaceFaceHit.normal().scale(0.08)),
               Component.translatable(
                  hoveredFace.adjustable()
                     ? "fastformer.operation.face_drag_hint"
                     : "fastformer.operation.selection_click_hint"
               ).getString()
            );
         }

         Vec3 center = bounds.center();
         GizmoViewScale scale = GizmoViewScale.fromDistance(camera.distanceTo(center));
         AxisGizmo gizmo = workspacePartGizmo(part, center, scale)
            .withTextComponent(GizmoTextComponent.pointLevel());
         AxisGizmo.HandleKey hoveredKey = hoveredGizmo != null
            && !hoveredGizmo.common()
            && hoveredGizmo.partId() == part.id()
            ? hoveredGizmo.hit().handle().key() : null;
         AxisGizmo.HandleKey activeKey = FastPlaceClientInput.workspaceGizmoDragMatches(part.id(), false)
            ? FastPlaceClientInput.operationGizmoDragKey() : null;
         gizmo = gizmo.withState(hoveredKey, activeKey);
         renderGeometryGizmo(poseStack, buffers, camera, gizmo, selected || hovered ? 1.0F : 0.52F);
         if (hasNonOrthogonalRotation(part.transform().rotation())) {
            AxisGizmo.Axis highlightedLocalAxis = hoveredGizmo != null
               && !hoveredGizmo.common()
               && hoveredGizmo.partId() == part.id()
               && hoveredGizmo.hit().handle().operation() == AxisGizmo.Operation.SCALE
               ? hoveredGizmo.hit().handle().axis()
               : activeKey != null && activeKey.operation() == AxisGizmo.Operation.SCALE
                  ? activeKey.axis() : null;
            renderLocalWorkspaceGizmo(
               poseStack, buffers, camera, center, scale.axisLength() * 0.82,
               part.transform().rotation(), highlightedLocalAxis, hovered ? 1.0F : 0.58F
            );
         }
      }

      if (workspace.selectedIds().size() > 1) {
         OccupiedBlockBounds group = workspace.selectedParts().stream()
            .map(FastPlaceClientPreview::resolveWorkspacePart)
            .filter(values -> !values.isEmpty())
            .map(values -> OccupiedBlockBounds.from(values.keySet()).orElseThrow())
            .reduce(OccupiedBlockBounds::union)
            .orElse(null);
         if (group != null) {
            Vec3 center = group.center();
            poseStack.pushPose();
            poseStack.translate(-camera.x, -camera.y, -camera.z);
            renderFlowingDashedBox(
               poseStack,
               buffers.getBuffer(RenderType.lines()),
               center,
               new Vec3(
                  group.width(AxisGizmo.Axis.X) * 0.5 + 0.035,
                  group.width(AxisGizmo.Axis.Y) * 0.5 + 0.035,
                  group.width(AxisGizmo.Axis.Z) * 0.5 + 0.035
               ),
               pendingGridDashOffset(),
               0.96F
            );
            renderFlowingDashedBox(
               poseStack,
               buffers.getBuffer(RenderType.lines()),
               center,
               new Vec3(
                  group.width(AxisGizmo.Axis.X) * 0.5 + 0.085,
                  group.width(AxisGizmo.Axis.Y) * 0.5 + 0.085,
                  group.width(AxisGizmo.Axis.Z) * 0.5 + 0.085
               ),
               -pendingGridDashOffset() * 0.72,
               0.62F
            );
            poseStack.popPose();
            GizmoViewScale scale = GizmoViewScale.fromDistance(camera.distanceTo(center));
            boolean includesPrism = workspace.selectedParts().stream()
               .anyMatch(part -> part.selection() != null && part.selection().prism() != null);
            AxisGizmo common = (includesPrism
               ? AxisGizmo.inFrame(
                  TransformFrame.world(center), scale.axisLength() * 1.12, scale.handleRadius() * 1.12,
                  AxisGizmo.Operation.MOVE, AxisGizmo.Operation.ROTATE
               )
               : AxisGizmo.inFrame(
                  TransformFrame.world(center), scale.axisLength() * 1.12, scale.handleRadius() * 1.12,
                  AxisGizmo.Operation.MOVE, AxisGizmo.Operation.SCALE, AxisGizmo.Operation.ROTATE
               )).withTextComponent(GizmoTextComponent.pointLevel());
            AxisGizmo.HandleKey hoveredKey = hoveredGizmo != null && hoveredGizmo.common()
               ? hoveredGizmo.hit().handle().key() : null;
            AxisGizmo.HandleKey activeKey = FastPlaceClientInput.workspaceGizmoDragMatches(0, true)
               ? FastPlaceClientInput.operationGizmoDragKey() : null;
            common = common.withState(hoveredKey, activeKey);
            renderGeometryGizmo(poseStack, buffers, camera, common, 1.0F);
         }
      }
      buffers.endBatch(GHOST_OUTLINE_LINES);
      buffers.endBatch(RenderType.lines());
   }

   /** Draws the shared marker for every state where left/right creates a selection point. */
   private static void renderSelectionCreationCandidate(
      RenderLevelStageEvent event, Minecraft minecraft, OperationInteractionIntent pointerIntent
   ) {
      if (!(pointerIntent instanceof OperationInteractionIntent.CreateSelection create)
         || minecraft.level == null || minecraft.player == null) {
         return;
      }
      BlockPos position = create.point();
      PoseStack poseStack = event.getPoseStack();
      BufferSource buffers = minecraft.renderBuffers().bufferSource();
      Vec3 camera = event.getCamera().getPosition();
      float pulse = ghostBreathPulse();
      poseStack.pushPose();
      poseStack.translate(-camera.x, -camera.y, -camera.z);
      LevelRenderer.renderLineBox(
         poseStack, buffers.getBuffer(RenderType.lines()),
         new AABB(position).inflate(0.018 + pulse * 0.012),
         0.35F, 0.95F, 1.0F, 0.48F + 0.26F * pulse
      );
      poseStack.popPose();
      renderWorkspaceHintLabel(
         poseStack, buffers, minecraft, camera, Vec3.atCenterOf(position).add(0.0, 0.68, 0.0),
         Component.translatable("fastformer.operation.selection_create_hint").getString()
      );
   }

   private static void renderWorkspaceBlocks(
      PoseStack poseStack,
      BufferSource buffers,
      Minecraft minecraft,
      Vec3 camera,
      Map<BlockPos, io.github.fastformer.client.operation.ClientBlockSnapshot> blocks,
      float red,
      float green,
      float blue,
      float alpha
   ) {
      RenderSystem.enableBlend();
      RenderSystem.defaultBlendFunc();
      RenderSystem.setShaderColor(red, green, blue, alpha * worldPreviewOpacity);
      try {
         for (var entry : blocks.entrySet()) {
            poseStack.pushPose();
            poseStack.translate(
               entry.getKey().getX() - camera.x,
               entry.getKey().getY() - camera.y,
               entry.getKey().getZ() - camera.z
            );
            minecraft.getBlockRenderer().renderSingleBlock(
               entry.getValue().state(),
               poseStack,
               buffers,
               LightTexture.FULL_BRIGHT,
               OverlayTexture.NO_OVERLAY
            );
            poseStack.popPose();
         }
      } finally {
         buffers.endBatch();
         RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
         RenderSystem.disableBlend();
      }
   }

   private static void renderWorkspacePartLabel(
      PoseStack poseStack,
      BufferSource buffers,
      Minecraft minecraft,
      Vec3 camera,
      Vec3 center,
      int id,
      boolean selected,
      boolean hovered,
      boolean controlPreview,
      float pulse
   ) {
      String text = hovered && controlPreview
         ? "#" + id + (selected ? "  Ctrl · 取消选择" : "  Ctrl · 追加选择")
         : hovered ? "#" + id + " part" : selected ? "#" + id + " selected" : "#" + id;
      poseStack.pushPose();
      float emphasis = hovered ? 1.28F + pulse * 0.08F : selected ? 1.14F : 1.0F;
      poseStack.translate(center.x - camera.x, center.y - camera.y + 0.22 + (hovered ? pulse * 0.05F : 0.0F), center.z - camera.z);
      poseStack.mulPose(minecraft.gameRenderer.getMainCamera().rotation());
      poseStack.scale(-0.025F * emphasis, -0.025F * emphasis, 0.025F * emphasis);
      float x = -minecraft.font.width(text) * 0.5F;
      minecraft.font.drawInBatch(
         text,
         x,
         -minecraft.font.lineHeight * 0.5F,
         hovered ? 0xFF83F5FF : selected ? 0xFFFFD66B : 0xFFB9D7E8,
         false,
         poseStack.last().pose(),
         buffers,
         Font.DisplayMode.SEE_THROUGH,
         hovered ? 0xC0004050 : selected ? 0xA0603D00 : 0x50000000,
         0x00F000F0
      );
      poseStack.popPose();
   }

   private static void renderWorkspaceHintLabel(
      PoseStack poseStack, BufferSource buffers, Minecraft minecraft, Vec3 camera, Vec3 position, String text
   ) {
      poseStack.pushPose();
      poseStack.translate(position.x - camera.x, position.y - camera.y, position.z - camera.z);
      poseStack.mulPose(minecraft.gameRenderer.getMainCamera().rotation());
      poseStack.scale(-0.021F, -0.021F, 0.021F);
      minecraft.font.drawInBatch(
         text, -minecraft.font.width(text) * 0.5F, -minecraft.font.lineHeight - 3.0F,
         0xFFFFFFFF, false, poseStack.last().pose(), buffers, Font.DisplayMode.SEE_THROUGH,
         0xB0203038, LightTexture.FULL_BRIGHT
      );
      poseStack.popPose();
   }

   private static void renderPendingDeleteBlocks(
      PoseStack poseStack,
      BufferSource buffers,
      Vec3 camera,
      java.util.Collection<BlockPos> blocks
   ) {
      poseStack.pushPose();
      poseStack.translate(-camera.x, -camera.y, -camera.z);
      VertexConsumer lines = buffers.getBuffer(RenderType.lines());
      double offset = pendingGridDashOffset();
      for (BlockPos pos : blocks) {
         Vec3 center = Vec3.atCenterOf(pos);
         renderDeleteFlowingBox(
            poseStack,
            lines,
            center,
            new Vec3(0.505, 0.505, 0.505),
            offset + (pos.getX() + pos.getY() + pos.getZ()) * 0.17,
            0.94F
         );
      }
      poseStack.popPose();
   }

   private static void renderDeleteFlowingBox(
      PoseStack poseStack,
      VertexConsumer consumer,
      Vec3 center,
      Vec3 halfExtents,
      double offset,
      float alpha
   ) {
      double x0 = center.x - halfExtents.x;
      double y0 = center.y - halfExtents.y;
      double z0 = center.z - halfExtents.z;
      double x1 = center.x + halfExtents.x;
      double y1 = center.y + halfExtents.y;
      double z1 = center.z + halfExtents.z;
      Vec3[] corners = {
         new Vec3(x0, y0, z0), new Vec3(x1, y0, z0), new Vec3(x1, y1, z0), new Vec3(x0, y1, z0),
         new Vec3(x0, y0, z1), new Vec3(x1, y0, z1), new Vec3(x1, y1, z1), new Vec3(x0, y1, z1)
      };
      int[][] edgesAndFaceDiagonals = {
         {0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4},
         {0, 4}, {1, 5}, {2, 6}, {3, 7},
         {0, 2}, {1, 3}, {4, 6}, {5, 7},
         {0, 5}, {1, 4}, {3, 6}, {2, 7},
         {0, 7}, {3, 4}, {1, 6}, {2, 5}
      };
      for (int[] edge : edgesAndFaceDiagonals) {
         renderRedFlowingDashedLine(
            poseStack, consumer, corners[edge[0]], corners[edge[1]], alpha, offset
         );
      }
   }

   private static void renderRedFlowingDashedLine(
      PoseStack poseStack, VertexConsumer consumer, Vec3 from, Vec3 to, float alpha, double offset
   ) {
      Vec3 vector = to.subtract(from);
      double length = vector.length();
      if (length < EPSILON) {
         return;
      }
      Vec3 direction = vector.scale(1.0 / length);
      int index = (int)Math.floor(-offset / SELECTION_DASH_LENGTH) - 1;
      for (double start = index * SELECTION_DASH_LENGTH + offset;
           start < length;
           start += SELECTION_DASH_LENGTH, index++) {
         double clippedStart = Math.max(0.0, start);
         double clippedEnd = Math.min(length, start + SELECTION_DASH_LENGTH);
         if (clippedEnd <= clippedStart) {
            continue;
         }
         boolean bright = Math.floorMod(index, 2) == 0;
         renderLine(
            poseStack,
            consumer,
            from.add(direction.scale(clippedStart)),
            from.add(direction.scale(clippedEnd)),
            bright ? 1.0F : 0.38F,
            bright ? 0.12F : 0.0F,
            bright ? 0.08F : 0.0F,
            alpha
         );
      }
   }

   private static void renderOperationPointDragGuides(
      PoseStack poseStack, BufferSource buffers, Vec3 camera, OperationPreviewPayload snapshot
   ) {
      SelectionPrism.GridPlane plane = FastPlaceClientInput.operationPointDragPlane();
      SelectionPrism.GridLine line = FastPlaceClientInput.operationPointDragLine();
      if (plane == null && line == null) {
         return;
      }
      VertexConsumer consumer = buffers.getBuffer(RenderType.lines());
      if (plane != null) {
         List<Vec3> bounds = new ArrayList<>(snapshot.points().stream().map(Vec3::atCenterOf).toList());
         BlockPos target = FastPlaceClientInput.operationPointDragTarget();
         if (target != null) {
            bounds.add(Vec3.atCenterOf(target));
         }
         renderGuidePlaneGrid(
            poseStack, consumer, camera, List.of(new GuidePlane(plane.anchor(), plane.normal(), bounds, true))
         );
      }
      if (line != null) {
         Vec3 anchor = line.anchor();
         double radius = Math.max(8.0, camera.distanceTo(anchor) * 0.3);
         BlockPos target = FastPlaceClientInput.operationPointDragTarget();
         if (target != null) {
            radius = Math.max(radius, Math.abs(Vec3.atCenterOf(target).subtract(anchor).dot(line.direction())) + 4.0);
         }
         renderGuideLines(
            poseStack,
            consumer,
            camera,
            List.of(new GuideLine(anchor.subtract(line.direction().scale(radius)), anchor.add(line.direction().scale(radius))))
         );
      }
      buffers.endBatch(RenderType.lines());
   }

   private static void renderOperationSelectionPass(
      PoseStack poseStack,
      VertexConsumer lines,
      OperationPreviewPayload snapshot,
      List<BlockPos> points,
      OperationSelectionVolume selection,
      AABB bounds,
      OperationGeometry.RayHit hit,
      Vec3 eye,
      float alphaScale,
      float outlineAlpha
   ) {
      if (selection != null) {
         renderOperationVolume(poseStack, lines, selection, Vec3.ZERO, outlineAlpha * alphaScale);
      } else if (points.size() >= 2) {
         if (snapshot.operationSelectionMode() == OperationSelectionMode.PRISM) {
            int baseCount = snapshot.operationPrismBasePointCount() > 0
               ? snapshot.operationPrismBasePointCount()
               : points.size();
            for (int i = 1; i < baseCount; i++) {
               renderOperationOutlineLine(
                  poseStack, lines, Vec3.atCenterOf(points.get(i - 1)), Vec3.atCenterOf(points.get(i)),
                  outlineAlpha * alphaScale
               );
            }
            if (snapshot.operationPrismBasePointCount() >= 3) {
               renderOperationOutlineLine(
                  poseStack, lines, Vec3.atCenterOf(points.get(baseCount - 1)), Vec3.atCenterOf(points.getFirst()),
                  outlineAlpha * alphaScale
               );
            }
         } else {
            Vec3 anchor = Vec3.atCenterOf(points.getFirst());
            for (int i = 1; i < points.size(); i++) {
               renderOperationOutlineLine(poseStack, lines, anchor, Vec3.atCenterOf(points.get(i)), outlineAlpha * alphaScale);
            }
         }
      }
      if (snapshot.operationSelectionMode() == OperationSelectionMode.CONVEX_HULL) {
         for (OperationGeometry.HullFace face : OperationGeometry.convexHullFaces(points)) {
            renderOperationOutlineLine(poseStack, lines, face.a(), face.b(), outlineAlpha * alphaScale);
            renderOperationOutlineLine(poseStack, lines, face.b(), face.c(), outlineAlpha * alphaScale);
            renderOperationOutlineLine(poseStack, lines, face.c(), face.a(), outlineAlpha * alphaScale);
         }
      }
      if (hit != null) {
         double length = Math.max(2.0, eye.distanceTo(hit.point()) * 0.08);
         renderStaticOperationGuideLine(
            poseStack,
            lines,
            hit.point(),
            hit.point().add(hit.normal().scale(length)),
            0.78F * alphaScale
         );
      }

      if (selection != null && snapshot.operationAdjustmentStarted()) {
         for (BlockPos repetition : OperationGizmoPresentation.stackOffsets(
            snapshot.operationStackMin(), snapshot.operationStackMax(), 512
         )) {
            BlockPos displacement = OperationGeometry.stackDisplacement(bounds, repetition)
               .offset(snapshot.operationTranslation());
            if (displacement.equals(BlockPos.ZERO)) {
               continue;
            }
            renderOperationVolume(
               poseStack, lines, selection,
               Vec3.atLowerCornerOf(displacement),
               outlineAlpha * 0.78F * alphaScale
            );
         }
      }
   }

   private static void renderOperationFaces(
      PoseStack poseStack, VertexConsumer consumer, OperationSelectionVolume selection, float alpha, Vec3 camera
   ) {
      float red = GHOST_RED;
      float green = GHOST_GREEN;
      float blue = GHOST_BLUE;
      if (selection.prism() != null) {
         List<Vec3> base = selection.prism().base();
         Vec3 extrusion = selection.prism().extrusion();
         Vec3 center = base.stream().reduce(Vec3.ZERO, Vec3::add).scale(1.0 / base.size()).add(extrusion.scale(0.5));
         for (int index = 1; index + 1 < base.size(); index++) {
            Vec3 a = inflateSelectionVertex(base.getFirst(), center, camera);
            Vec3 b = inflateSelectionVertex(base.get(index), center, camera);
            Vec3 c = inflateSelectionVertex(base.get(index + 1), center, camera);
            addGhostQuad(poseStack, consumer, a, b, c, c, red, green, blue, alpha);
            addGhostQuad(
               poseStack, consumer,
               inflateSelectionVertex(base.getFirst().add(extrusion), center, camera),
               inflateSelectionVertex(base.get(index + 1).add(extrusion), center, camera),
               inflateSelectionVertex(base.get(index).add(extrusion), center, camera),
               inflateSelectionVertex(base.get(index).add(extrusion), center, camera),
               red, green, blue, alpha
            );
         }
         for (int index = 0; index < base.size(); index++) {
            int next = (index + 1) % base.size();
            Vec3 a = inflateSelectionVertex(base.get(index), center, camera);
            Vec3 b = inflateSelectionVertex(base.get(next), center, camera);
            Vec3 c = inflateSelectionVertex(base.get(next).add(extrusion), center, camera);
            Vec3 d = inflateSelectionVertex(base.get(index).add(extrusion), center, camera);
            addGhostQuad(poseStack, consumer, a, b, c, d, red, green, blue, alpha);
         }
         return;
      }

      AABB box = selection.bounds().inflate(SELECTION_FACE_INFLATE);
      Vec3 center = box.getCenter();
      Vec3 p000 = inflateSelectionVertex(new Vec3(box.minX, box.minY, box.minZ), center, camera);
      Vec3 p100 = inflateSelectionVertex(new Vec3(box.maxX, box.minY, box.minZ), center, camera);
      Vec3 p110 = inflateSelectionVertex(new Vec3(box.maxX, box.maxY, box.minZ), center, camera);
      Vec3 p010 = inflateSelectionVertex(new Vec3(box.minX, box.maxY, box.minZ), center, camera);
      Vec3 p001 = inflateSelectionVertex(new Vec3(box.minX, box.minY, box.maxZ), center, camera);
      Vec3 p101 = inflateSelectionVertex(new Vec3(box.maxX, box.minY, box.maxZ), center, camera);
      Vec3 p111 = inflateSelectionVertex(new Vec3(box.maxX, box.maxY, box.maxZ), center, camera);
      Vec3 p011 = inflateSelectionVertex(new Vec3(box.minX, box.maxY, box.maxZ), center, camera);
      addGhostQuad(poseStack, consumer, p000, p100, p110, p010, red, green, blue, alpha);
      addGhostQuad(poseStack, consumer, p001, p011, p111, p101, red, green, blue, alpha);
      addGhostQuad(poseStack, consumer, p000, p001, p101, p100, red, green, blue, alpha);
      addGhostQuad(poseStack, consumer, p010, p110, p111, p011, red, green, blue, alpha);
      addGhostQuad(poseStack, consumer, p000, p010, p011, p001, red, green, blue, alpha);
      addGhostQuad(poseStack, consumer, p100, p101, p111, p110, red, green, blue, alpha);
   }

   private static void renderOperationHighlightedFace(
      PoseStack poseStack,
      VertexConsumer consumer,
      OperationSelectionVolume selection,
      OperationGeometry.RayHit hit,
      float alpha,
      Vec3 camera
   ) {
      float red = GHOST_RED;
      float green = GHOST_GREEN;
      float blue = GHOST_BLUE;
      if (selection.prism() != null) {
         List<Vec3> base = selection.prism().base();
         Vec3 extrusion = selection.prism().extrusion();
         if (hit.axis() == 2) {
            boolean top = hit.normal().dot(extrusion) > 0.0;
            Vec3 offset = top ? extrusion : Vec3.ZERO;
            for (int index = 1; index + 1 < base.size(); index++) {
               Vec3 bias = hit.normal().scale(SELECTION_FACE_INFLATE * 0.35);
               Vec3 a = base.getFirst().add(offset).add(bias);
               Vec3 b = base.get(top ? index + 1 : index).add(offset).add(bias);
               Vec3 c = base.get(top ? index : index + 1).add(offset).add(bias);
               addGhostQuad(poseStack, consumer, a, b, c, c, red, green, blue, alpha);
            }
         } else if (hit.axis() >= 3) {
            int index = hit.axis() - 3;
            if (index < base.size()) {
               Vec3 bias = hit.normal().scale(SELECTION_FACE_INFLATE * 0.35);
               Vec3 a = base.get(index).add(bias);
               Vec3 b = base.get((index + 1) % base.size()).add(bias);
               addGhostQuad(poseStack, consumer, a, b, b.add(extrusion), a.add(extrusion), red, green, blue, alpha);
            }
         }
         return;
      }

      AABB box = selection.bounds();
      boolean positive = hit.normal().dot(selection.axis(hit.axis())) > 0.0;
      double coordinate = switch (hit.axis()) {
         case 0 -> positive ? box.maxX : box.minX;
         case 1 -> positive ? box.maxY : box.minY;
         default -> positive ? box.maxZ : box.minZ;
      };
      Vec3 a;
      Vec3 b;
      Vec3 c;
      Vec3 d;
      if (hit.axis() == 0) {
         a = new Vec3(coordinate, box.minY, box.minZ);
         b = new Vec3(coordinate, box.maxY, box.minZ);
         c = new Vec3(coordinate, box.maxY, box.maxZ);
         d = new Vec3(coordinate, box.minY, box.maxZ);
      } else if (hit.axis() == 1) {
         a = new Vec3(box.minX, coordinate, box.minZ);
         b = new Vec3(box.minX, coordinate, box.maxZ);
         c = new Vec3(box.maxX, coordinate, box.maxZ);
         d = new Vec3(box.maxX, coordinate, box.minZ);
      } else {
         a = new Vec3(box.minX, box.minY, coordinate);
         b = new Vec3(box.maxX, box.minY, coordinate);
         c = new Vec3(box.maxX, box.maxY, coordinate);
         d = new Vec3(box.minX, box.maxY, coordinate);
      }
      // The face is the same plane as the inflated outline, translated only
      // along its normal. Do not bias each vertex toward the camera: that
      // bends the face and makes it diverge from its outline.
      Vec3 bias = hit.normal().scale(SELECTION_FACE_INFLATE * 0.35);
      a = a.add(bias);
      b = b.add(bias);
      c = c.add(bias);
      d = d.add(bias);
      addGhostQuad(poseStack, consumer, a, b, c, d, red, green, blue, alpha);
   }

   private static void renderSelectionFaceNormal(
      PoseStack poseStack, VertexConsumer consumer, OperationGeometry.RayHit hit, Vec3 camera, float alpha
   ) {
      if (hit == null || hit.normal().lengthSqr() < EPSILON) {
         return;
      }
      Vec3 normal = hit.normal().normalize();
      Vec3 from = hit.point().add(normal.scale(SELECTION_FACE_INFLATE * 2.5));
      Vec3 to = from.add(normal.scale(0.62));
      renderLine(poseStack, consumer, from, to, 1.0F, 0.86F, 0.22F, alpha);
      renderLine(poseStack, consumer, to, to.subtract(normal.scale(0.14)).add(normal.cross(new Vec3(0.0, 1.0, 0.0)).normalize().scale(0.08)), 1.0F, 0.86F, 0.22F, alpha);
   }

   private static Vec3 inflateSelectionVertex(Vec3 point, Vec3 center, Vec3 camera) {
      // Faces and outlines must share the same rigid geometry. Polygon offset
      // on GHOST_FACES supplies depth separation; radial/camera displacement
      // would make the face no longer be a translated copy of its outline.
      return point;
   }

   private static Vec3 cameraBiasedSelectionVertex(Vec3 point, Vec3 camera) {
      Vec3 towardCamera = camera.subtract(point);
      if (towardCamera.lengthSqr() < EPSILON) {
         return point;
      }
      double distance = Math.sqrt(towardCamera.lengthSqr());
      double bias = Math.min(0.12, SELECTION_FACE_INFLATE + distance * 0.00005);
      return point.add(towardCamera.scale(bias / distance));
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
         renderGeometryGizmo(poseStack, buffers, camera, plan.gizmo());
      }
   }

   private static GeometryWorkflowView geometryWorkflowView(Vec3 view) {
      return new GeometryWorkflowView(
         geometryState.mode(),
         geometryState.points().size(),
         geometryState.pointLocations(),
         geometryState.pointRoles(),
         geometryState.closed(),
         FastPlaceClientInput.modifierHeld(),
         geometryState.polyhedronShapeVariant(),
         geometryState.coneShapeVariant(),
         geometryState.compoundShapeVariant(),
         geometryState.polyhedronSizeMode(),
         geometryState.extrusion(),
         geometryState.conePlaneMode(),
         geometryState.coneRadius(),
         geometryState.coneScaleX(),
         geometryState.coneScaleZ(),
         geometryState.coneTopScaleOffset(),
         geometryState.coneTopOffset(),
         geometryState.coneRotationRadians(),
         geometryState.coneGizmoLocal(),
         geometryState.rotation(),
         geometryState.polyhedronLocalScale(),
         geometryState.polyhedronWorldScale(),
         geometryState.polyhedronGizmoLocal(),
         geometryState.fillMode(),
         view,
         geometryState.selectedPointIndex()
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
      return snapshot.faceMode() == FaceMode.POLYGON && !snapshot.polygonClosed()
         ? WallGenerator.generate(
            points,
            false,
            BlockPos.ZERO,
            FastPlaceGeometry.PREVIEW_MAX_BLOCKS,
            progress == null ? io.github.fastformer.fastplace.geometry.generation.BlockGenerationObserver.NONE : progress
         )
         : FastPlaceGeometry.blocks(
            points,
            modes,
            polygonHeightConfirmed,
            snapshot.polygonVolumeShape(),
            FastPlaceGeometry.PREVIEW_MAX_BLOCKS,
            progress == null ? io.github.fastformer.fastplace.geometry.generation.BlockGenerationObserver.NONE : progress
          );
   }

   private static Set<BlockPos> buildingPreviewBlocksCached(
      BuildingPreviewPayload snapshot, List<BlockPos> points, boolean polygonHeightConfirmed
   ) {
      BuildingPreviewKey key = new BuildingPreviewKey(
         buildingStateVersion,
         List.copyOf(points),
         polygonHeightConfirmed,
         FastPlaceClientInput.modifierHeld()
      );
      if (!key.equals(cachedBuildingPreviewKey)) {
         cachedBuildingPreviewKey = key;
         cachedBuildingFallbackBlocks = Set.of();
         cachedBuildingPreviewAtLimit = false;
         cachedBuildingPreviewResultVersion++;
         if (cachedBuildingPreviewProgress != null) {
            cachedBuildingPreviewProgress.cancel();
            cachedBuildingPreviewProgress = null;
         }
         if (cachedBuildingPreviewFuture != null) {
            cachedBuildingPreviewFuture.cancel(true);
         }
         PREVIEW_GENERATION_EXECUTOR.getQueue().clear();
         FastPlaceGeometry.Modes modes = effectiveBuildingModes(snapshot);
         PreviewAsyncPolicy.Workload workload = buildingPreviewWorkload(snapshot, key.points(), polygonHeightConfirmed);
         if (PreviewAsyncPolicy.generateSynchronously(key.points(), workload)) {
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
            cachedBuildingPreviewFuture = null;
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
               PreviewAsyncPolicy.estimateScanCells(key.points(), workload)
            );
            ProgressiveBlockGeneration progress = cachedBuildingPreviewProgress;
            cachedBuildingPreviewFuture = PREVIEW_GENERATION_EXECUTOR.submit(
               () -> {
                  Set<BlockPos> blocks = buildingPreviewBlocks(
                     snapshot, key.points(), polygonHeightConfirmed, modes, progress
                  );
                  progress.complete();
                  return new BuildingBlockResult(key, blocks);
               }
            );
         }
      }
      if (cachedBuildingPreviewFuture != null && cachedBuildingPreviewFuture.isDone()) {
         try {
            BuildingBlockResult result = cachedBuildingPreviewFuture.get();
            if (result.key().equals(cachedBuildingPreviewKey)) {
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
            cachedBuildingPreviewFuture = null;
            cachedBuildingPreviewProgress = null;
         }
      } else if (cachedBuildingPreviewProgress != null) {
         ProgressiveBlockGeneration.Snapshot progress = cachedBuildingPreviewProgress.snapshot();
         if (cachedBuildingPreviewAtLimit || progress.generated() >= FastPlaceGeometry.PREVIEW_MAX_BLOCKS) {
            holdBuildingPreviewAtFallback(snapshot, key.points(), polygonHeightConfirmed);
         } else {
            List<ProgressiveBlockGeneration.SectionBatch> batches = cachedBuildingPreviewProgress.drainPublished();
            long published = 0L;
            for (ProgressiveBlockGeneration.SectionBatch batch : batches) {
               published += batch.blocks().size();
            }
            if (published >= FastPlaceGeometry.PREVIEW_MAX_BLOCKS - (long)cachedBuildingPreviewBlocks.size()) {
               holdBuildingPreviewAtFallback(snapshot, key.points(), polygonHeightConfirmed);
            } else if (!batches.isEmpty()) {
               HashSet<BlockPos> progressive = new HashSet<>(cachedBuildingPreviewBlocks);
               for (ProgressiveBlockGeneration.SectionBatch batch : batches) {
                  progressive.addAll(batch.blocks());
               }
               cachedBuildingPreviewBlocks = Set.copyOf(progressive);
               cachedBuildingPreviewResultVersion++;
            }
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
         cachedBuildingPreviewProgress.drainPublished();
      }
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
            modes.withFillMode(FillMode.OUTLINE)
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
      if (cachedBuildingPreviewFuture != null) {
         cachedBuildingPreviewFuture.cancel(true);
         cachedBuildingPreviewFuture = null;
      }
      if (cachedBuildingPreviewProgress != null) {
         cachedBuildingPreviewProgress.cancel();
         cachedBuildingPreviewProgress = null;
      }
      cachedBuildingPreviewAtLimit = false;
      PREVIEW_GENERATION_EXECUTOR.getQueue().clear();
      cachedBuildingFallbackBlocks = Set.of();
   }

   private static BuildingRenderLayers buildingRenderLayers(
      BuildingPreviewPayload snapshot,
      Set<BlockPos> previewBlocks,
      Set<BlockPos> candidateBlocks,
      BlockPos candidate,
      BlockPos hoveredPoint
   ) {
      BuildingRenderKey key = new BuildingRenderKey(
         buildingStateVersion,
         cachedBuildingPreviewKey,
         cachedBuildingPreviewResultVersion,
         candidate,
         hoveredPoint
      );
      if (!key.equals(cachedBuildingRenderKey)) {
         GeometryPreviewBlocks.Layers split = GeometryPreviewBlocks.layersPreservingConfirmed(
            confirmedBuildingBlocks(snapshot), previewBlocks
         );
         HashSet<BlockPos> pending = new HashSet<>(split.pending());
         pending.addAll(candidateBlocks);
         Set<BlockPos> all = unionBlocks(split.confirmed(), pending);
         HashSet<BlockPos> confirmedMarkers = new HashSet<>(snapshot.points());
         confirmedMarkers.addAll(candidateBlocks);
         HashSet<BlockPos> pendingMarkers = new HashSet<>(snapshot.points());
         if (candidate != null) {
            pendingMarkers.add(candidate);
         }
         cachedBuildingRenderKey = key;
         cachedBuildingRenderLayers = new BuildingRenderLayers(
            withoutBlocks(split.confirmed(), confirmedMarkers),
            withoutBlocks(pending, pendingMarkers),
            all
         );
      }
      return cachedBuildingRenderLayers;
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
            FastPlaceClientInput.modifierHeld() ? RaycastPlacement.EMBEDDED : modes.raycastPlacement()
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
         geometryStateVersion,
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
               workflowView, geometryState.points(), hoveredPoint, candidate, eye
            );
         } catch (RuntimeException exception) {
            logPreviewFailure("Unable to generate FastFormer geometry preview plan; using control points", exception);
            cachedGeometryPlan = GeometryPreviewPlan.controlPoints(geometryState.points(), hoveredPoint);
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
            && geometryState.mode() == GeometryMode.CONE_PRISM
            && geometryState.conePlaneMode().stageFor(geometryState.points().size()) == ConePrismStage.BODY
            && !FastPlaceClientInput.modifierHeld()) {
            pendingBlocks = coneBasePreviewFallback();
         }
         cachedGeometryRenderSource = source;
         cachedGeometryRenderLayers = new GeometryRenderLayers(
            withoutBlocks(source.ghostBlocks(), controlPoints),
            withoutBlocks(pendingBlocks, controlPoints)
         );
      }
      return cachedGeometryRenderLayers;
   }

   private static Set<BlockPos> coneBasePreviewFallback() {
      int facePointCount = Math.min(geometryState.pointLocations().size(), geometryState.conePlaneMode().facePointCount());
      if (facePointCount < geometryState.conePlaneMode().facePointCount()) {
         return Set.of();
      }
      List<Vec3> facePoints = List.copyOf(geometryState.pointLocations().subList(0, facePointCount));
      ConePrismParameters parameters = new ConePrismParameters(
         facePoints,
         Optional.empty(),
         geometryState.coneShapeVariant(),
         geometryState.conePlaneMode(),
         geometryState.coneRadius(),
         geometryState.coneScaleX(),
         geometryState.coneScaleZ(),
         geometryState.coneTopScaleOffset(),
         geometryState.coneTopOffset(),
         geometryState.coneRotationRadians()
      );
      return ConePrismGenerator.baseOutline(parameters, 16_384);
   }

   private static BlockPos geometryCandidatePoint(Minecraft minecraft, LocalPlayer player) {
      GeometryHit hit = geometryCandidateHit(minecraft, player);
      return hit == null ? null : hit.point();
   }

   private static GeometryHit geometryCandidateHit(Minecraft minecraft, LocalPlayer player) {
      if (!geometryState.active() || player == null || InteractionContext.nearVanillaBlock(minecraft)) {
         return null;
      }
      BlockHitResult hit = raycastBlocks(player);
      if (hit.getType() == Type.BLOCK) {
         return GeometryHit.from(hit);
      }
      List<BlockPos> points = geometryState.points();
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

   private static float updateWorldPreviewOpacity(Minecraft minecraft, long now) {
      boolean operation = operationState.active();
      boolean building = buildingState.active() && !operation && !geometryState.active();
      if (building) {
         return buildingPreviewOpacityForDistance(minecraft);
      }
      return WORLD_PREVIEW_OPACITY.update(
         new PreviewOpacityController.Input(
            InteractionContext.nearVanillaBlock(minecraft),
            operation && FastPlaceClientInput.modifierHeld(),
            operation ? 0.30F : building ? BUILDING_NEAR_MINIMUM_OPACITY : 0.0F
         ),
         now
      );
   }

   private static float buildingPreviewOpacityForDistance(Minecraft minecraft) {
      if (!(minecraft.hitResult instanceof BlockHitResult hit) || minecraft.player == null) {
         return 1.0F;
      }
      double reach = minecraft.player.blockInteractionRange();
      double distance = minecraft.player.getEyePosition().distanceTo(hit.getLocation());
      float fade = (float)Math.clamp((distance - reach) / BUILDING_PREVIEW_DISTANCE_FADE, 0.0, 1.0);
      return BUILDING_NEAR_MINIMUM_OPACITY + fade * (1.0F - BUILDING_NEAR_MINIMUM_OPACITY);
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
      BuildingPreviewPayload snapshot, BlockPos candidate, BlockPos hoveredPoint, Set<BlockPos> ghostBlocks
   ) {
      HashMap<BlockPos, BuildingSpecialBlock> result = new HashMap<>();
      for (ControlPoint point : buildingControlPoints(snapshot, candidate, hoveredPoint)) {
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

   private static List<ControlPoint> buildingControlPoints(BuildingPreviewPayload snapshot, BlockPos candidate, BlockPos hoveredPoint) {
      if (snapshot.points().isEmpty() && candidate == null) {
         return List.of();
      }
      boolean closing = isBuildingClosingCandidate(snapshot, candidate, hoveredPoint);
      boolean closeable = snapshot.faceMode() == FaceMode.POLYGON
         && !snapshot.polygonClosed()
         && snapshot.points().size() >= 3;
      ArrayList<ControlPoint> result = new ArrayList<>(snapshot.points().size() + (candidate == null ? 0 : 1));
      if (!snapshot.points().isEmpty()) {
         result.add(closeable
            ? ControlPoint.primary(snapshot.points().getFirst(), closing)
            : ControlPoint.of(snapshot.points().getFirst(), ControlPointRole.PRIMARY));
         for (int i = 1; i < snapshot.points().size(); i++) {
            BlockPos point = snapshot.points().get(i);
            result.add(ControlPoint.secondary(point));
         }
      }
      if (candidate != null && !snapshot.polygonHeightConfirmed() && !closing && !snapshot.points().contains(candidate)) {
         ControlPointRole role = snapshot.points().isEmpty() ? ControlPointRole.PRIMARY : ControlPointRole.SECONDARY;
         result.add(ControlPoint.pending(Vec3.atCenterOf(candidate), role));
      }
      return result;
   }

   private static List<ControlPoint> operationControlPoints(
      OperationPreviewPayload snapshot, BlockPos candidate, boolean edgeInsertionHovered
   ) {
      List<BlockPos> points = snapshot.points();
      ArrayList<ControlPoint> result = new ArrayList<>(points.size() + (candidate == null ? 0 : 1));
      int pointIndex = 0;
      int hoveredIndex = snapshot.operationSelectionMode() == OperationSelectionMode.PRISM
         ? operationPointUnderCrosshairIndex()
         : -1;
      if (snapshot.hasFirst() && pointIndex < points.size()) {
         boolean closeable = snapshot.operationSelectionMode() == OperationSelectionMode.PRISM
            && snapshot.operationPrismBasePointCount() == 0
            && points.size() >= 3;
         boolean hovered = closeable && operationPointUnderCrosshairIndex() == pointIndex;
         ControlPoint first = closeable
            ? ControlPoint.primary(points.get(pointIndex), hovered)
            : ControlPoint.of(points.get(pointIndex), ControlPointRole.PRIMARY);
         if (hoveredIndex == pointIndex) {
            first = first.withFeedback(ControlPointFeedback.hoverable()).withHovered(true);
         }
         result.add(first);
         pointIndex++;
      }
      if (snapshot.hasSecond() && pointIndex < points.size()) {
         ControlPoint second = ControlPoint.secondary(points.get(pointIndex));
         if (hoveredIndex == pointIndex) {
            second = second.withFeedback(ControlPointFeedback.hoverable()).withHovered(true);
         }
         result.add(second);
         pointIndex++;
      }
      while (pointIndex < points.size()) {
         ControlPoint point = ControlPoint.secondary(points.get(pointIndex));
         if (hoveredIndex == pointIndex) {
            point = point.withFeedback(ControlPointFeedback.hoverable()).withHovered(true);
         }
         result.add(point);
         pointIndex++;
      }
      if (candidate != null && !points.contains(candidate)) {
         ControlPointRole role = snapshot.hasFirst() ? ControlPointRole.SECONDARY : ControlPointRole.PRIMARY;
         ControlPoint pending = ControlPoint.pending(Vec3.atCenterOf(candidate), role);
         result.add(edgeInsertionHovered
            ? pending.withFeedback(ControlPointFeedback.hoverable()).withHovered(true)
            : pending);
      }
      return List.copyOf(result);
   }

   private static Set<BlockPos> buildingCandidateBlocks(
      BuildingPreviewPayload snapshot, BlockPos candidate, BlockPos hoveredPoint
   ) {
      return candidate == null
         || snapshot.polygonHeightConfirmed()
         || snapshot.points().contains(candidate)
         || isBuildingClosingCandidate(snapshot, candidate, hoveredPoint)
         ? Set.of()
         : Set.of(candidate.immutable());
   }

   private static boolean isBuildingClosingCandidate(
      BuildingPreviewPayload snapshot, BlockPos candidate, BlockPos hoveredPoint
   ) {
      return snapshot.faceMode() == FaceMode.POLYGON
         && !snapshot.polygonClosed()
         && snapshot.points().size() >= 3
         && snapshot.points().getFirst().equals(hoveredPoint);
   }

   private static void renderGeometryControlPoints(PoseStack poseStack, BufferSource buffers, Vec3 camera, GeometryPreviewPlan plan) {
      renderControlPoints(poseStack, buffers, camera, plan.controlPoints());
   }

   private static void renderControlPoints(PoseStack poseStack, BufferSource buffers, Vec3 camera, List<ControlPoint> points) {
      if (points.isEmpty()) {
         return;
      }

      // BufferSource has one active builder. Finish one render type before switching
      // between the hidden and visible passes; interleaving consumers crashes.
      buffers.endLastBatch();
      VertexConsumer occludedBoxes = buffers.getBuffer(OCCLUDED_CONTROL_POINTS);
      for (ControlPoint point : points) {
         if (!point.confirmed()) {
            continue;
         }
         ControlPointStyle style = point.feedback().style(point.role(), point.hovered());
         addControlPointBox(
            poseStack,
            occludedBoxes,
            camera,
            point,
            style.alpha() * worldPreviewOpacity * OCCLUDED_POINT_ALPHA
         );
      }
      buffers.endBatch(OCCLUDED_CONTROL_POINTS);

      VertexConsumer boxes = buffers.getBuffer(RenderType.debugFilledBox());
      for (ControlPoint point : points) {
         if (point.confirmed()) {
            ControlPointStyle style = point.feedback().style(point.role(), point.hovered());
            addControlPointBox(poseStack, boxes, camera, point, style.alpha() * worldPreviewOpacity);
         }
      }
      buffers.endBatch(RenderType.debugFilledBox());

      VertexConsumer occludedLines = buffers.getBuffer(PENDING_XRAY_LINES);
      renderConfirmedControlPointOutlines(poseStack, occludedLines, camera, points, OCCLUDED_POINT_ALPHA);
      renderPendingControlPoints(poseStack, occludedLines, camera, points, OCCLUDED_POINT_ALPHA);
      buffers.endBatch(PENDING_XRAY_LINES);

      VertexConsumer lines = buffers.getBuffer(PENDING_LINES);
      renderConfirmedControlPointOutlines(poseStack, lines, camera, points, 1.0F);
      renderPendingControlPoints(poseStack, lines, camera, points, 1.0F);
      buffers.endBatch(PENDING_LINES);
   }

   private static void renderConfirmedControlPointOutlines(
      PoseStack poseStack,
      VertexConsumer lines,
      Vec3 camera,
      List<ControlPoint> points,
      float alphaScale
   ) {
      poseStack.pushPose();
      poseStack.translate(-camera.x, -camera.y, -camera.z);
      for (ControlPoint point : points) {
         if (!point.confirmed()) {
            continue;
         }
         Vec3 center = point.center();
         Vec3 halfExtents = point.shape().visualHalfExtents(point.center());
         ControlPointStyle style = point.feedback().style(point.role(), point.hovered());
         LevelRenderer.renderLineBox(
            poseStack,
            lines,
            new AABB(
               center.x - halfExtents.x,
               center.y - halfExtents.y,
               center.z - halfExtents.z,
               center.x + halfExtents.x,
               center.y + halfExtents.y,
               center.z + halfExtents.z
            ).inflate(CONTROL_POINT_OUTLINE_INFLATE),
            style.red(),
            style.green(),
            style.blue(),
            style.alpha() * alphaScale * worldPreviewOpacity
         );
      }
      poseStack.popPose();
   }

   private static void renderPendingControlPoints(
      PoseStack poseStack,
      VertexConsumer lines,
      Vec3 camera,
      List<ControlPoint> points,
      float alphaScale
   ) {
      poseStack.pushPose();
      poseStack.translate(-camera.x, -camera.y, -camera.z);
      double offset = pendingGridDashOffset();
      for (ControlPoint point : points) {
         if (point.confirmed()) {
            continue;
         }
         ControlPointStyle style = point.feedback().style(point.role(), point.hovered());
         renderFlowingDashedBox(
            poseStack,
            lines,
            point.center(),
            point.shape().visualHalfExtents(point.center()),
            offset,
            style.alpha() * alphaScale
         );
      }
      poseStack.popPose();
   }

   private static void addControlPointBox(
      PoseStack poseStack, VertexConsumer consumer, Vec3 camera, ControlPoint point, float alpha
   ) {
      Vec3 center = point.center();
      Vec3 halfExtents = point.shape().visualHalfExtents(point.center());
      ControlPointStyle style = point.feedback().style(point.role(), point.hovered());
      LevelRenderer.addChainedFilledBoxVertices(
         poseStack,
         consumer,
         center.x - halfExtents.x - camera.x,
         center.y - halfExtents.y - camera.y,
         center.z - halfExtents.z - camera.z,
         center.x + halfExtents.x - camera.x,
         center.y + halfExtents.y - camera.y,
         center.z + halfExtents.z - camera.z,
         style.red(),
         style.green(),
         style.blue(),
         alpha
      );
   }

   private static void renderFlowingDashedBox(
      PoseStack poseStack,
      VertexConsumer consumer,
      Vec3 center,
      Vec3 halfExtents,
      double offset,
      float alpha
   ) {
      renderFlowingDashedBox(poseStack, consumer, center, halfExtents, null, offset, alpha);
   }

   private static void renderFlowingDashedBox(
      PoseStack poseStack,
      VertexConsumer consumer,
      Vec3 center,
      Vec3 halfExtents,
      Vec3 camera,
      double offset,
      float alpha
   ) {
      double x0 = center.x - halfExtents.x;
      double y0 = center.y - halfExtents.y;
      double z0 = center.z - halfExtents.z;
      double x1 = center.x + halfExtents.x;
      double y1 = center.y + halfExtents.y;
      double z1 = center.z + halfExtents.z;
      Vec3[] corners = {
         new Vec3(x0, y0, z0), new Vec3(x1, y0, z0), new Vec3(x1, y1, z0), new Vec3(x0, y1, z0),
         new Vec3(x0, y0, z1), new Vec3(x1, y0, z1), new Vec3(x1, y1, z1), new Vec3(x0, y1, z1)
      };
      if (camera != null) {
         for (int index = 0; index < corners.length; index++) {
            corners[index] = GhostOutlineDepthBias.towardCamera(corners[index], camera, GHOST_OUTLINE_CAMERA_BIAS);
         }
      }
      int[][] edges = {
         {0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4}, {0, 4}, {1, 5}, {2, 6}, {3, 7}
      };
      for (int[] edge : edges) {
         renderAlternatingDashedLine(
            poseStack, consumer, corners[edge[0]], corners[edge[1]], alpha, offset
         );
      }
   }

   private static void renderOperationOutlineLine(PoseStack poseStack, VertexConsumer consumer, Vec3 from, Vec3 to, float alpha) {
      renderAlternatingDashedLine(poseStack, consumer, from, to, alpha, selectionDashOffset());
   }

   private static void renderStaticOperationGuideLine(
      PoseStack poseStack, VertexConsumer consumer, Vec3 from, Vec3 to, float alpha
   ) {
      renderAlternatingDashedLine(poseStack, consumer, from, to, alpha, 0.0);
   }

   private static void renderAlternatingDashedLine(
      PoseStack poseStack, VertexConsumer consumer, Vec3 from, Vec3 to, float alpha, double offset
   ) {
      Vec3 vector = to.subtract(from);
      double length = vector.length();
      if (length < EPSILON) {
         return;
      }
      Vec3 direction = vector.scale(1.0 / length);
      int index = (int)Math.floor(-offset / SELECTION_DASH_LENGTH) - 1;
      for (double start = index * SELECTION_DASH_LENGTH + offset;
           start < length;
           start += SELECTION_DASH_LENGTH, index++) {
         double clippedStart = Math.max(0.0, start);
         double clippedEnd = Math.min(length, start + SELECTION_DASH_LENGTH);
         if (clippedEnd <= clippedStart) {
            continue;
         }
         float color = Math.floorMod(index, 2) == 0 ? 1.0F : 0.0F;
         renderLine(
            poseStack,
            consumer,
            from.add(direction.scale(clippedStart)),
            from.add(direction.scale(clippedEnd)),
            color,
            color,
            color,
            alpha
         );
      }
   }

   private static void renderGeometryGizmo(PoseStack poseStack, BufferSource buffers, Vec3 camera, AxisGizmo gizmo) {
      renderGeometryGizmo(poseStack, buffers, camera, gizmo, 1.0F);
   }

   private static void renderGeometryGizmo(
      PoseStack poseStack, BufferSource buffers, Vec3 camera, AxisGizmo gizmo, float alphaScale
   ) {
      Vec3 center = gizmo.center();
      double axisLength = gizmo.axisLength();

      poseStack.pushPose();
      poseStack.translate(-camera.x, -camera.y, -camera.z);

      VertexConsumer lines = buffers.getBuffer(GIZMO_LINES);
      for (AxisGizmo.Axis gizmoAxis : AxisGizmo.Axis.values()) {
         Vec3 axis = gizmo.axisVector(gizmoAxis);
         float[] color = gizmoAxisColor(gizmoAxis);
         AxisGizmo.Handle positiveMove = gizmo.handles().stream()
            .filter(handle -> handle.operation() == AxisGizmo.Operation.MOVE
               && handle.axis() == gizmoAxis && handle.direction() == AxisGizmo.Direction.POSITIVE)
            .findFirst().orElse(null);
         AxisGizmo.Handle negativeMove = gizmo.handles().stream()
            .filter(handle -> handle.operation() == AxisGizmo.Operation.MOVE
               && handle.axis() == gizmoAxis && handle.direction() == AxisGizmo.Direction.NEGATIVE)
            .findFirst().orElse(null);
         double positiveLength = positiveMove == null ? axisLength : gizmo.endpointDistance(positiveMove);
         double negativeLength = negativeMove == null ? axisLength : gizmo.endpointDistance(negativeMove);
         renderLine(
            poseStack, lines,
            center.subtract(axis.scale(negativeLength)), center.add(axis.scale(positiveLength)),
            color[0], color[1], color[2], 0.96F * alphaScale
         );
      }
      renderGizmoLineHandles(poseStack, lines, gizmo, false, alphaScale);
      buffers.endBatch(GIZMO_LINES);

      VertexConsumer hoverLines = buffers.getBuffer(GIZMO_HOVER_LINES);
      renderGizmoLineHandles(poseStack, hoverLines, gizmo, true, alphaScale);
      buffers.endBatch(GIZMO_HOVER_LINES);

      VertexConsumer solids = buffers.getBuffer(GIZMO_SOLIDS);
      for (AxisGizmo.Handle handle : gizmo.handles()) {
         if (handle.operation() == AxisGizmo.Operation.SCALE) {
            float[] color = gizmoHandleColor(handle);
            renderScaleHandle(poseStack, solids, gizmo, handle, color, gizmoHandleAlpha(handle) * alphaScale);
         }
      }
      buffers.endBatch(GIZMO_SOLIDS);
      poseStack.popPose();
   }

   /** Low-contrast local UVW guide: repeat is authored before the subsequent rotation. */
   private static void renderLocalWorkspaceGizmo(
      PoseStack poseStack,
      BufferSource buffers,
      Vec3 camera,
      Vec3 center,
      double axisLength,
      Vec3 rotation,
      AxisGizmo.Axis highlightedAxis,
      float alphaScale
   ) {
      poseStack.pushPose();
      poseStack.translate(-camera.x, -camera.y, -camera.z);
      VertexConsumer lines = buffers.getBuffer(GIZMO_LINES);
      for (AxisGizmo.Axis axis : AxisGizmo.Axis.values()) {
         Vec3 localAxis = rotateLocalAxis(axis, rotation);
         boolean highlighted = axis == highlightedAxis;
         float[] color = softLocalAxisColor(axis);
         float alpha = (highlighted ? 0.90F : 0.26F) * alphaScale;
         double length = highlighted ? axisLength * 1.15 : axisLength;
         renderColoredDashedLine(
            poseStack, lines, center.subtract(localAxis.scale(length)), center.add(localAxis.scale(length)),
            color[0], color[1], color[2], alpha
         );
      }
      buffers.endBatch(GIZMO_LINES);
      poseStack.popPose();
   }

   private static boolean hasNonOrthogonalRotation(Vec3 rotation) {
      double quarterTurn = Math.PI * 0.5;
      return Math.abs(rotation.x - Math.rint(rotation.x / quarterTurn) * quarterTurn) > 1.0E-5
         || Math.abs(rotation.y - Math.rint(rotation.y / quarterTurn) * quarterTurn) > 1.0E-5
         || Math.abs(rotation.z - Math.rint(rotation.z / quarterTurn) * quarterTurn) > 1.0E-5;
   }

   private static Vec3 rotateLocalAxis(AxisGizmo.Axis axis, Vec3 rotation) {
      Vec3 value = switch (axis) {
         case X -> new Vec3(1.0, 0.0, 0.0);
         case Y -> new Vec3(0.0, 1.0, 0.0);
         case Z -> new Vec3(0.0, 0.0, 1.0);
      };
      double xSin = Math.sin(rotation.x);
      double xCos = Math.cos(rotation.x);
      value = new Vec3(value.x, value.y * xCos - value.z * xSin, value.y * xSin + value.z * xCos);
      double ySin = Math.sin(rotation.y);
      double yCos = Math.cos(rotation.y);
      value = new Vec3(value.x * yCos + value.z * ySin, value.y, -value.x * ySin + value.z * yCos);
      double zSin = Math.sin(rotation.z);
      double zCos = Math.cos(rotation.z);
      return new Vec3(value.x * zCos - value.y * zSin, value.x * zSin + value.y * zCos, value.z);
   }

   private static float[] softLocalAxisColor(AxisGizmo.Axis axis) {
      return switch (axis) {
         case X -> new float[]{1.0F, 0.54F, 0.58F};
         case Y -> new float[]{0.56F, 0.92F, 0.62F};
         case Z -> new float[]{0.54F, 0.70F, 1.0F};
      };
   }

   private static void renderColoredDashedLine(
      PoseStack poseStack, VertexConsumer consumer, Vec3 from, Vec3 to, float red, float green, float blue, float alpha
   ) {
      Vec3 vector = to.subtract(from);
      double length = vector.length();
      if (length < EPSILON) {
         return;
      }
      Vec3 direction = vector.scale(1.0 / length);
      double dashLength = 0.18;
      for (double start = 0.0; start < length; start += dashLength * 2.0) {
         double end = Math.min(length, start + dashLength);
         renderLine(poseStack, consumer, from.add(direction.scale(start)), from.add(direction.scale(end)), red, green, blue, alpha);
      }
   }

   private static void renderGizmoLineHandles(
      PoseStack poseStack,
      VertexConsumer consumer,
      AxisGizmo gizmo,
      boolean highlighted,
      float alphaScale
   ) {
      for (AxisGizmo.Handle handle : gizmo.handles()) {
         if (handle.operation() == AxisGizmo.Operation.SCALE
            || highlighted != (handle.hovered() || handle.active())) {
            continue;
         }

         float[] color = gizmoHandleColor(handle);
         if (handle.drawsRing()) {
            renderRotationRing(
               poseStack,
               consumer,
               gizmo.center(),
               gizmo.axisVector(handle.axis()),
               gizmo.rotationRingRadius(handle),
               color[0],
               color[1],
               color[2],
               gizmoRingAlpha(handle) * alphaScale
            );
         } else if (handle.operation() == AxisGizmo.Operation.MOVE) {
            if (highlighted) {
               Vec3 direction = gizmo.axisVector(handle.axis());
               if (handle.direction() == AxisGizmo.Direction.NEGATIVE) {
                  direction = direction.scale(-1.0);
               }
               renderLine(
                  poseStack,
                  consumer,
                  gizmo.center(),
                  gizmo.handleCenter(handle),
                  color[0],
                  color[1],
                  color[2],
                  gizmoHandleAlpha(handle) * alphaScale
               );
            }
            renderMoveArrow(poseStack, consumer, gizmo, handle, color, gizmoHandleAlpha(handle) * alphaScale);
         }
      }
   }

   private static float operationGizmoAlpha(AxisGizmo gizmo) {
      Minecraft minecraft = Minecraft.getInstance();
      if (minecraft.player == null || gizmo == null) {
         return 0.5F;
      }
      Vec3 eye = minecraft.player.getEyePosition();
      Vec3 view = minecraft.player.getViewVector(1.0F).normalize();
      double rayDistance = Math.max(0.0, gizmo.center().subtract(eye).dot(view));
      double distanceSqr = eye.add(view.scale(rayDistance)).distanceToSqr(gizmo.center());
      boolean nearCenter = distanceSqr <= Math.pow(gizmo.handleRadius() * 1.5, 2.0);
      return OperationGizmoPresentation.alpha(nearCenter);
   }

   private static float[] gizmoAxisColor(AxisGizmo.Axis axis) {
      int color = AxisGizmo.axisColor(axis);
      return new float[]{
         ((color >>> 16) & 0xFF) / 255.0F,
         ((color >>> 8) & 0xFF) / 255.0F,
         (color & 0xFF) / 255.0F
      };
   }

   private static float[] gizmoHandleColor(AxisGizmo.Handle handle) {
      int color = handle.hoverFeedback().color(handle.hovered(), handle.active());
      return new float[]{
         ((color >>> 16) & 0xFF) / 255.0F,
         ((color >>> 8) & 0xFF) / 255.0F,
         (color & 0xFF) / 255.0F
      };
   }

   private static float gizmoHandleAlpha(AxisGizmo.Handle handle) {
      if (handle.active()) {
         return 1.0F;
      }
      if (handle.hovered()) {
         return 1.0F;
      }
      return handle.direction() == AxisGizmo.Direction.NEGATIVE ? 0.86F : 0.98F;
   }

   private static float gizmoRingAlpha(AxisGizmo.Handle handle) {
      if (handle.active()) {
         return 1.0F;
      }
      if (handle.hovered()) {
         return 1.0F;
      }
      return 0.88F;
   }

   private static void renderRotationRing(
      PoseStack poseStack,
      VertexConsumer consumer,
      Vec3 center,
      Vec3 normal,
      double radius,
      float red,
      float green,
      float blue,
      float alpha
   ) {
      PlaneAxes planeAxes = PlaneAxes.fromNormal(normal);
      Vec3 u = planeAxes.horizontal();
      Vec3 v = planeAxes.vertical();
      int segments = 64;
      Vec3 previous = center.add(u.scale(radius));
      for (int i = 1; i <= segments; i++) {
         double angle = Math.PI * 2.0 * (double)i / (double)segments;
         Vec3 next = center.add(u.scale(Math.cos(angle) * radius)).add(v.scale(Math.sin(angle) * radius));
         renderLine(poseStack, consumer, previous, next, red, green, blue, alpha);
         previous = next;
      }
   }

   private static void renderMoveArrow(
      PoseStack poseStack,
      VertexConsumer consumer,
      AxisGizmo gizmo,
      AxisGizmo.Handle handle,
      float[] color,
      float alpha
   ) {
      Vec3 direction = gizmo.axisVector(handle.axis());
      if (handle.direction() == AxisGizmo.Direction.NEGATIVE) {
         direction = direction.scale(-1.0);
      }
      direction = normalize(direction);
      Vec3 tip = gizmo.handleCenter(handle);
      double radius = gizmo.visualRadius(handle);
      Vec3 base = tip.subtract(direction.scale(radius * 2.8));
      PlaneAxes axes = PlaneAxes.fromNormal(direction);
      Vec3 u = axes.horizontal().scale(radius * 1.35);
      Vec3 v = axes.vertical().scale(radius * 1.35);
      Vec3[] rim = {base.add(u), base.add(v), base.subtract(u), base.subtract(v)};
      for (int i = 0; i < rim.length; i++) {
         renderLine(poseStack, consumer, tip, rim[i], color[0], color[1], color[2], alpha);
         renderLine(poseStack, consumer, rim[i], rim[(i + 1) % rim.length], color[0], color[1], color[2], alpha);
      }
   }

   private static void renderScaleHandle(
      PoseStack poseStack,
      VertexConsumer consumer,
      AxisGizmo gizmo,
      AxisGizmo.Handle handle,
      float[] color,
      float alpha
   ) {
      Vec3 center = gizmo.handleCenter(handle);
      double radius = gizmo.visualRadius(handle);
      float[] brightColor = gizmoHandleColor(handle);
      renderSolidBox(poseStack, consumer, center, radius, brightColor[0], brightColor[1], brightColor[2], alpha);
   }

   private static void renderSolidBox(
      PoseStack poseStack, VertexConsumer consumer, Vec3 center, double radius, float red, float green, float blue, float alpha
   ) {
      double x0 = center.x - radius;
      double y0 = center.y - radius;
      double z0 = center.z - radius;
      double x1 = center.x + radius;
      double y1 = center.y + radius;
      double z1 = center.z + radius;
      Vec3 p000 = new Vec3(x0, y0, z0);
      Vec3 p001 = new Vec3(x0, y0, z1);
      Vec3 p010 = new Vec3(x0, y1, z0);
      Vec3 p011 = new Vec3(x0, y1, z1);
      Vec3 p100 = new Vec3(x1, y0, z0);
      Vec3 p101 = new Vec3(x1, y0, z1);
      Vec3 p110 = new Vec3(x1, y1, z0);
      Vec3 p111 = new Vec3(x1, y1, z1);
      addGhostQuad(poseStack, consumer, p000, p100, p110, p010, red, green, blue, alpha);
      addGhostQuad(poseStack, consumer, p101, p001, p011, p111, red, green, blue, alpha);
      addGhostQuad(poseStack, consumer, p001, p000, p010, p011, red, green, blue, alpha);
      addGhostQuad(poseStack, consumer, p100, p101, p111, p110, red, green, blue, alpha);
      addGhostQuad(poseStack, consumer, p010, p110, p111, p011, red, green, blue, alpha);
      addGhostQuad(poseStack, consumer, p001, p101, p100, p000, red, green, blue, alpha);
   }

   @SubscribeEvent
   public static void onLoggingOut(LoggingOut event) {
      buildingState = BuildingPreviewPayload.inactive();
      operationState = OperationPreviewPayload.inactive();
      geometryState = GeometryPreviewPayload.inactive();
      ClientOperationController.clearWorkspace();
      WORKSPACE_RESOLVED_CACHE.clear();
      activityState = FastPlaceActivity.NONE;
      SCROLL_FEEDBACK.clear();
      GIZMO_FEEDBACK.clear();
      lastGizmoFeedbackAxis = null;
      lastGizmoFeedbackOperation = null;
      lastGizmoFeedbackSteps = 0;
      lastGizmoFeedbackBaseValue = 0.0;
      geometryTransformBaseline = null;
      WORLD_PREVIEW_OPACITY.reset(true);
      OPERATION_FACE_INTERPOLATOR.reset();
      worldPreviewOpacity = 1.0F;
      raycastDebug = null;
      cachedRaycast = null;
      cachedRaycastStart = null;
      cachedRaycastDirection = null;
      cachedRaycastAt = 0L;
      cachedConfirmedBuildingState = null;
      cachedConfirmedBuildingBias = LineTieBias.DEFAULT;
      cachedConfirmedBuildingBlocks = Set.of();
      cachedBuildingPreviewKey = null;
      cachedBuildingPreviewBlocks = Set.of();
      cancelBuildingPreviewGeneration();
      cachedBuildingRenderKey = null;
      cachedBuildingRenderLayers = BuildingRenderLayers.empty();
      cachedGeometryPlanKey = null;
      cachedGeometryPlan = null;
      cachedGeometryRenderSource = null;
      cachedGeometryRenderLayers = GeometryRenderLayers.empty();
      CONFIRMED_GHOST_CACHE.clear();
      CONFIRMED_OUTLINE_CACHE.clear();
      CONFIRMED_BUILDING_SHELL_CACHE.clear();
      PENDING_BUILDING_SHELL_CACHE.clear();
      PENDING_GHOST_CACHE.clear();
      PENDING_GHOST_BUFFER_CACHE.clear();
      SmoothReticlePostEffect.reset();
      smoothReticleFrame = false;
   }

   private static BlockHitResult raycastBlocks(LocalPlayer player) {
      Vec3 start = player.getEyePosition();
      Vec3 direction = player.getViewVector(1.0F);
      long now = System.nanoTime();
      if (cachedRaycast != null
         && start.equals(cachedRaycastStart)
         && direction.equals(cachedRaycastDirection)
         && now - cachedRaycastAt <= 16_000_000L) {
         return cachedRaycast.hit();
      }
      cachedRaycast = LongRangeBlockRaycast.clip(player.level(), player, start, direction);
      cachedRaycastStart = start;
      cachedRaycastDirection = direction;
      cachedRaycastAt = now;
      raycastDebug = cachedRaycast;
      return cachedRaycast.hit();
   }

   private static Set<BlockPos> withoutBlocks(Set<BlockPos> blocks, Set<BlockPos> excluded) {
      if (blocks.isEmpty() || excluded.isEmpty() || excluded.stream().noneMatch(blocks::contains)) {
         return blocks;
      }
      HashSet<BlockPos> result = new HashSet<>(blocks);
      result.removeAll(excluded);
      return result;
   }

   private static Set<BlockPos> unionBlocks(Set<BlockPos> first, Set<BlockPos> second) {
      if (first.isEmpty()) {
         return second;
      }
      if (second.isEmpty()) {
         return first;
      }
      HashSet<BlockPos> result = new HashSet<>(first);
      result.addAll(second);
      return result;
   }

   private static void renderBuildingShells(
      LocalPlayer player,
      PoseStack poseStack,
      BufferSource buffers,
      Vec3 camera,
      BlockState state,
      Set<BlockPos> confirmedBlocks,
      Set<BlockPos> pendingBlocks,
      Set<BlockPos> shapeEnvironment,
      Map<BlockPos, BuildingSpecialBlock> specialStyles
   ) {
      BlockGetter previewLevel = state == null
         ? player.level()
         : PreviewBlockOcclusion.level(shapeEnvironment, state);
      net.minecraft.world.phys.shapes.CollisionContext collision = net.minecraft.world.phys.shapes.CollisionContext.of(player);
      ShapeShellMesh.Mesh confirmed = CONFIRMED_BUILDING_SHELL_CACHE.mesh(
         previewLevel, state, collision, confirmedBlocks, shapeEnvironment, specialStyles, player.isShiftKeyDown()
      );
      ShapeShellMesh.Mesh pending = PENDING_BUILDING_SHELL_CACHE.mesh(
         previewLevel, state, collision, pendingBlocks, shapeEnvironment, Map.of(), player.isShiftKeyDown()
      );

      renderShapeShellFaces(
         poseStack, buffers.getBuffer(GHOST_FACES), camera, confirmed.faces(), 0.80F * worldPreviewOpacity
      );
      float pendingFaceAlpha = (0.30F + 0.20F * ghostBreathPulse()) * worldPreviewOpacity;
      renderShapeShellFaces(poseStack, buffers.getBuffer(GHOST_FACES), camera, pending.faces(), pendingFaceAlpha);
      buffers.endBatch(GHOST_FACES);

      renderShapeShellEdges(
         poseStack, buffers.getBuffer(PENDING_XRAY_LINES), camera, confirmed.edges(), 0.16F * worldPreviewOpacity
      );
      renderShapeShellEdges(
         poseStack, buffers.getBuffer(PENDING_XRAY_LINES), camera, pending.edges(), 0.12F * worldPreviewOpacity
      );
      buffers.endBatch(PENDING_XRAY_LINES);

      renderShapeShellEdges(
         poseStack, buffers.getBuffer(GHOST_OUTLINE_LINES), camera, confirmed.edges(), 0.92F * worldPreviewOpacity
      );
      renderShapeShellEdges(
         poseStack, buffers.getBuffer(GHOST_OUTLINE_LINES), camera, pending.edges(), 0.82F * worldPreviewOpacity
      );
      buffers.endBatch(GHOST_OUTLINE_LINES);
   }

   private static void renderShapeShellFaces(
      PoseStack poseStack,
      VertexConsumer consumer,
      Vec3 camera,
      List<ShapeShellMesh.Face> faces,
      float alpha
   ) {
      poseStack.pushPose();
      poseStack.translate(-camera.x, -camera.y, -camera.z);
      for (ShapeShellMesh.Face face : faces) {
         Vec3 normal = Vec3.atLowerCornerOf(face.direction().getNormal()).scale(GHOST_FACE_OFFSET);
         List<Vec3> vertices = face.vertices();
         ShapeShellMesh.Color color = face.color();
         addGhostQuad(
            poseStack,
            consumer,
            vertices.get(0).add(normal),
            vertices.get(1).add(normal),
            vertices.get(2).add(normal),
            vertices.get(3).add(normal),
            color.red(),
            color.green(),
            color.blue(),
            alpha
         );
      }
      poseStack.popPose();
   }

   private static void renderShapeShellEdges(
      PoseStack poseStack,
      VertexConsumer consumer,
      Vec3 camera,
      List<ShapeShellMesh.StyledEdge> edges,
      float alpha
   ) {
      poseStack.pushPose();
      poseStack.translate(-camera.x, -camera.y, -camera.z);
      for (ShapeShellMesh.StyledEdge edge : edges) {
         ShapeShellMesh.Color color = edge.color();
         renderLine(
            poseStack,
            consumer,
            edge.from(),
            edge.to(),
            color.red(),
            color.green(),
            color.blue(),
            alpha
         );
      }
      poseStack.popPose();
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

   private static void renderOperationVolume(
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

   private static double pendingGridDashOffset() {
      double seconds = (System.nanoTime() % 10_000_000_000L) / 1_000_000_000.0;
      return seconds * PENDING_DASH_SPEED % SELECTION_DASH_PERIOD;
   }

   private static double selectionDashOffset() {
      double seconds = (System.nanoTime() % 10_000_000_000L) / 1_000_000_000.0;
      double distance = seconds * PENDING_DASH_SPEED;
      double snappedDistance = Math.floor(distance / PENDING_DASH_UNIT) * PENDING_DASH_UNIT;
      return snappedDistance % SELECTION_DASH_PERIOD;
   }

   private static float ghostBreathPulse() {
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

   private static void addGhostQuad(
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
      Pose pose = poseStack.last();
      float visibleAlpha = alpha * worldPreviewOpacity;
      consumer.addVertex(pose, (float)a.x, (float)a.y, (float)a.z).setColor(red, green, blue, visibleAlpha);
      consumer.addVertex(pose, (float)b.x, (float)b.y, (float)b.z).setColor(red, green, blue, visibleAlpha);
      consumer.addVertex(pose, (float)c.x, (float)c.y, (float)c.z).setColor(red, green, blue, visibleAlpha);
      consumer.addVertex(pose, (float)d.x, (float)d.y, (float)d.z).setColor(red, green, blue, visibleAlpha);
   }

   private static void renderGuidePlaneGrid(PoseStack poseStack, VertexConsumer lineConsumer, Vec3 camera, List<GuidePlane> planes) {
      renderGuidePlaneGrid(poseStack, lineConsumer, camera, planes, false);
   }

   private static void renderBuildingGuidePlaneGrid(
      PoseStack poseStack, VertexConsumer lineConsumer, Vec3 camera, List<GuidePlane> planes
   ) {
      renderGuidePlaneGrid(poseStack, lineConsumer, camera, planes, true);
   }

   private static void renderGuidePlaneGrid(
      PoseStack poseStack,
      VertexConsumer lineConsumer,
      Vec3 camera,
      List<GuidePlane> planes,
      boolean buildingStyle
   ) {
      poseStack.pushPose();
      poseStack.translate(-camera.x, -camera.y, -camera.z);

      for (GuidePlane plane : planes) {
         Vec3 normal = normalize(plane.normal());
         if (normal.lengthSqr() < 1.0E-7 || (!plane.showWhenAxisAligned() && isAxisAligned(normal))) {
            continue;
         }
         PlaneAxes axes = PlaneAxes.fromNormal(normal);
         Vec3 u = axes.horizontal();
         Vec3 v = axes.vertical();
         Vec3 center = plane.center();
         double minRadius = Math.max(MIN_PLANE_RADIUS, camera.distanceTo(center) * PLANE_DISTANCE_SCALE);
         double radius = minRadius;
         for (Vec3 bound : plane.bounds()) {
            double distU = Math.abs(bound.subtract(center).dot(u));
            double distV = Math.abs(bound.subtract(center).dot(v));
            radius = Math.max(radius, distU + 1.0);
            radius = Math.max(radius, distV + 1.0);
         }
         renderGrid(poseStack, lineConsumer, center, u, v, radius, buildingStyle);
      }

      poseStack.popPose();
   }

   private static boolean isAxisAligned(Vec3 normal) {
      return Math.max(Math.abs(normal.x), Math.max(Math.abs(normal.y), Math.abs(normal.z))) > 0.9999;
   }

   private static void renderGrid(
      PoseStack poseStack,
      VertexConsumer consumer,
      Vec3 center,
      Vec3 u,
      Vec3 v,
      double radius,
      boolean buildingStyle
   ) {
      int gridSize = (int) Math.ceil(radius);
      int spacing = Math.max(1, (int)Math.ceil((double)gridSize / 24.0));
      int gridLines = (int)Math.ceil((double)gridSize / (double)spacing);
      float gridAlpha = buildingStyle ? 0.14F : 0.35F;
      float axisAlpha = buildingStyle ? 0.28F : 0.68F;
      for (int i = -gridLines; i <= gridLines; i++) {
         int coordinate = i * spacing;
         Vec3 offset = v.scale(coordinate);
         Vec3 from = center.add(offset).subtract(u.scale(radius));
         Vec3 to = center.add(offset).add(u.scale(radius));
         float alpha = i == 0 ? axisAlpha : gridAlpha;
         if (buildingStyle) {
            renderGridPointContrastLine(poseStack, consumer, from, to, center, spacing, alpha);
         } else {
            renderLine(poseStack, consumer, from, to, PLANE_RED, PLANE_GREEN, PLANE_BLUE, alpha);
         }
      }
      for (int j = -gridLines; j <= gridLines; j++) {
         int coordinate = j * spacing;
         Vec3 offset = u.scale(coordinate);
         Vec3 from = center.add(offset).subtract(v.scale(radius));
         Vec3 to = center.add(offset).add(v.scale(radius));
         float alpha = j == 0 ? axisAlpha : gridAlpha;
         if (buildingStyle) {
            renderGridPointContrastLine(poseStack, consumer, from, to, center, spacing, alpha);
         } else {
            renderLine(poseStack, consumer, from, to, PLANE_RED, PLANE_GREEN, PLANE_BLUE, alpha);
         }
      }
   }

   private static void renderGuideLines(PoseStack poseStack, VertexConsumer consumer, Vec3 camera, List<GuideLine> lines) {
      poseStack.pushPose();
      poseStack.translate(-camera.x, -camera.y, -camera.z);

      for (GuideLine line : lines) {
         renderLine(poseStack, consumer, line.from(), line.to());
      }

      poseStack.popPose();
   }

   private static void renderBuildingGuideLines(
      PoseStack poseStack, VertexConsumer consumer, Vec3 camera, List<GuideLine> lines
   ) {
      poseStack.pushPose();
      poseStack.translate(-camera.x, -camera.y, -camera.z);
      for (GuideLine line : lines) {
         renderStaticOperationGuideLine(
            poseStack, consumer, line.from(), line.to(), 0.72F
         );
      }
      poseStack.popPose();
   }

   private static void renderLine(PoseStack poseStack, VertexConsumer consumer, Vec3 from, Vec3 to) {
      renderLine(poseStack, consumer, from, to, 0.18F, 0.78F, 1.0F, 0.68F);
   }

   private static void renderLine(PoseStack poseStack, VertexConsumer consumer, Vec3 from, Vec3 to, float red, float green, float blue, float alpha) {
      Vec3 normal = normalize(to.subtract(from));
      if (!(normal.lengthSqr() < 1.0E-7)) {
         Pose pose = poseStack.last();
         consumer.addVertex(pose, (float)from.x, (float)from.y, (float)from.z)
            .setColor(red, green, blue, alpha * worldPreviewOpacity)
            .setNormal(pose, (float)normal.x, (float)normal.y, (float)normal.z);
         consumer.addVertex(pose, (float)to.x, (float)to.y, (float)to.z)
            .setColor(red, green, blue, alpha * worldPreviewOpacity)
            .setNormal(pose, (float)normal.x, (float)normal.y, (float)normal.z);
      }
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
               snapshot.raycastPlacement() == io.github.fastformer.fastplace.RaycastPlacement.EMBEDDED
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

   private static GhostMesh buildGhostMesh(
      Set<BlockPos> blocks, boolean includeFaces, boolean cancelCoplanarSharedEdges, boolean mergeEdges
   ) {
      // Cancel only inside one oriented plane. A global parity pass would also erase
      // the crease where two planes meet, including the inner edge of an L-shaped notch.
      Map<GhostPlane, Set<FaceCell>> planes = includeFaces ? new HashMap<>() : null;
      Map<GhostPlane, Set<GhostEdge>> planeBoundaryEdges = new HashMap<>();
      for (BlockPos pos : blocks) {
         for (Direction direction : Direction.values()) {
            if (blocks.contains(pos.relative(direction))) {
               continue;
            }
            GhostPlane plane = GhostPlane.of(pos, direction);
            if (planes != null) {
               planes.computeIfAbsent(plane, ignored -> new HashSet<>()).add(FaceCell.of(pos, direction));
            }
            collectSurfaceEdges(
               planeBoundaryEdges.computeIfAbsent(plane, ignored -> new HashSet<>()),
               pos,
               direction,
               cancelCoplanarSharedEdges
            );
         }
      }

      ArrayList<GhostQuad> faces = new ArrayList<>();
      if (planes != null) {
         for (Map.Entry<GhostPlane, Set<FaceCell>> entry : planes.entrySet()) {
            mergeGhostPlane(entry.getKey(), entry.getValue(), faces);
         }
      }
      HashSet<GhostEdge> boundaryEdges = new HashSet<>();
      for (Set<GhostEdge> edges : planeBoundaryEdges.values()) {
         boundaryEdges.addAll(edges);
      }
      List<GhostEdge> edges = mergeEdges ? mergeCollinearEdges(boundaryEdges) : List.copyOf(boundaryEdges);
      return new GhostMesh(List.copyOf(faces), edges);
   }

   private static PendingGhostMesh buildPendingGhostMesh(Set<BlockPos> blocks) {
      return new PendingGhostMesh(PendingPreviewGrid.build(blocks));
   }

   private static void mergeGhostPlane(GhostPlane plane, Set<FaceCell> cells, List<GhostQuad> output) {
      ArrayList<FaceCell> ordered = new ArrayList<>(cells);
      ordered.sort(Comparator.comparingInt(FaceCell::v).thenComparingInt(FaceCell::u));
      HashSet<FaceCell> remaining = new HashSet<>(cells);
      for (FaceCell start : ordered) {
         if (!remaining.remove(start)) {
            continue;
         }
         int width = 1;
         while (remaining.contains(new FaceCell(start.u() + width, start.v()))) {
            width++;
         }
         for (int u = 1; u < width; u++) {
            remaining.remove(new FaceCell(start.u() + u, start.v()));
         }

         int height = 1;
         while (containsGhostRow(remaining, start.u(), start.v() + height, width)) {
            for (int u = 0; u < width; u++) {
               remaining.remove(new FaceCell(start.u() + u, start.v() + height));
            }
            height++;
         }
         output.add(GhostQuad.of(plane, start.u(), start.v(), width, height));
      }
   }

   private static void renderGridPointContrastLine(
      PoseStack poseStack,
      VertexConsumer consumer,
      Vec3 from,
      Vec3 to,
      Vec3 gridOrigin,
      double spacing,
      float alpha
   ) {
      Vec3 direction = normalize(to.subtract(from));
      if (direction.lengthSqr() < EPSILON) {
         return;
      }
      double start = from.subtract(gridOrigin).dot(direction);
      double end = to.subtract(gridOrigin).dot(direction);
      if (end < start) {
         Vec3 swapPoint = from;
         from = to;
         to = swapPoint;
         double swap = start;
         start = end;
         end = swap;
         direction = direction.scale(-1.0);
      }
      double halfSpacing = spacing * 0.5;
      double cursor = start;
      while (cursor < end - EPSILON) {
         double boundary = Math.min(end, (Math.floor(cursor / halfSpacing + 1.0 + 1.0E-9)) * halfSpacing);
         if (boundary <= cursor + EPSILON) {
            boundary = Math.min(end, cursor + halfSpacing);
         }
         float fromTone = gridPointTone(cursor, spacing);
         float toTone = gridPointTone(boundary, spacing);
         renderGradientLine(
            poseStack,
            consumer,
            from.add(direction.scale(cursor - start)),
            from.add(direction.scale(boundary - start)),
            fromTone,
            toTone,
            alpha
         );
         cursor = boundary;
      }
   }

   private static float gridPointTone(double coordinate, double spacing) {
      double nearestGrid = Math.rint(coordinate / spacing) * spacing;
      return (float)Math.clamp(Math.abs(coordinate - nearestGrid) * 2.0 / spacing, 0.0, 1.0);
   }

   private static void renderGradientLine(
      PoseStack poseStack,
      VertexConsumer consumer,
      Vec3 from,
      Vec3 to,
      float fromTone,
      float toTone,
      float alpha
   ) {
      Vec3 normal = normalize(to.subtract(from));
      if (normal.lengthSqr() < EPSILON) {
         return;
      }
      Pose pose = poseStack.last();
      float visibleAlpha = alpha * worldPreviewOpacity;
      consumer.addVertex(pose, (float)from.x, (float)from.y, (float)from.z)
         .setColor(fromTone, fromTone, fromTone, visibleAlpha)
         .setNormal(pose, (float)normal.x, (float)normal.y, (float)normal.z);
      consumer.addVertex(pose, (float)to.x, (float)to.y, (float)to.z)
         .setColor(toTone, toTone, toTone, visibleAlpha)
         .setNormal(pose, (float)normal.x, (float)normal.y, (float)normal.z);
   }

   private static boolean containsGhostRow(Set<FaceCell> cells, int startU, int v, int width) {
      for (int u = 0; u < width; u++) {
         if (!cells.contains(new FaceCell(startU + u, v))) {
            return false;
         }
      }
      return true;
   }

   private static void collectSurfaceEdges(
      Set<GhostEdge> boundaryEdges, BlockPos pos, Direction direction, boolean cancelSharedEdges
   ) {
      for (GhostEdge edge : surfaceEdges(pos, direction)) {
         collectEdge(boundaryEdges, edge, cancelSharedEdges);
      }
   }

   private static GhostEdge[] surfaceEdges(BlockPos pos, Direction direction) {
      int x0 = pos.getX();
      int y0 = pos.getY();
      int z0 = pos.getZ();
      int x1 = x0 + 1;
      int y1 = y0 + 1;
      int z1 = z0 + 1;
      GridPoint a;
      GridPoint b;
      GridPoint c;
      GridPoint d;
      switch (direction) {
         case DOWN -> {
            a = new GridPoint(x0, y0, z0); b = new GridPoint(x1, y0, z0);
            c = new GridPoint(x1, y0, z1); d = new GridPoint(x0, y0, z1);
         }
         case UP -> {
            a = new GridPoint(x0, y1, z0); b = new GridPoint(x0, y1, z1);
            c = new GridPoint(x1, y1, z1); d = new GridPoint(x1, y1, z0);
         }
         case NORTH -> {
            a = new GridPoint(x0, y0, z0); b = new GridPoint(x0, y1, z0);
            c = new GridPoint(x1, y1, z0); d = new GridPoint(x1, y0, z0);
         }
         case SOUTH -> {
            a = new GridPoint(x0, y0, z1); b = new GridPoint(x1, y0, z1);
            c = new GridPoint(x1, y1, z1); d = new GridPoint(x0, y1, z1);
         }
         case WEST -> {
            a = new GridPoint(x0, y0, z0); b = new GridPoint(x0, y0, z1);
            c = new GridPoint(x0, y1, z1); d = new GridPoint(x0, y1, z0);
         }
         case EAST -> {
            a = new GridPoint(x1, y0, z0); b = new GridPoint(x1, y1, z0);
            c = new GridPoint(x1, y1, z1); d = new GridPoint(x1, y0, z1);
         }
         default -> throw new IllegalStateException("Unknown direction " + direction);
      }
      return new GhostEdge[]{GhostEdge.of(a, b), GhostEdge.of(b, c), GhostEdge.of(c, d), GhostEdge.of(d, a)};
   }

   private static void collectEdge(Set<GhostEdge> edges, GhostEdge edge, boolean cancelSharedEdges) {
      if (!edges.add(edge) && cancelSharedEdges) {
         edges.remove(edge);
      }
   }

   private static List<GhostEdge> mergeCollinearEdges(Set<GhostEdge> edges) {
      Map<GhostEdgeLine, List<GhostInterval>> lines = new HashMap<>();
      for (GhostEdge edge : edges) {
         GhostEdgeLine line = GhostEdgeLine.of(edge);
         lines.computeIfAbsent(line, ignored -> new ArrayList<>()).add(line.interval(edge));
      }

      ArrayList<GhostEdge> merged = new ArrayList<>();
      for (Map.Entry<GhostEdgeLine, List<GhostInterval>> entry : lines.entrySet()) {
         List<GhostInterval> intervals = entry.getValue();
         intervals.sort(Comparator.comparingInt(GhostInterval::start));
         int start = intervals.getFirst().start();
         int end = intervals.getFirst().end();
         for (int i = 1; i < intervals.size(); i++) {
            GhostInterval next = intervals.get(i);
            if (next.start() <= end) {
               end = Math.max(end, next.end());
            } else {
               merged.add(entry.getKey().edge(start, end));
               start = next.start();
               end = next.end();
            }
         }
         merged.add(entry.getKey().edge(start, end));
      }
      return List.copyOf(merged);
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

   private static final class GhostMeshCache {
      private final boolean includeFaces;
      private final boolean cancelCoplanarSharedEdges;
      private final boolean mergeEdges;
      private Set<BlockPos> blocks = Set.of();
      private GhostMesh mesh = GhostMesh.empty();

      GhostMeshCache(boolean includeFaces, boolean cancelCoplanarSharedEdges, boolean mergeEdges) {
         this.includeFaces = includeFaces;
         this.cancelCoplanarSharedEdges = cancelCoplanarSharedEdges;
         this.mergeEdges = mergeEdges;
      }

      GhostMesh mesh(Set<BlockPos> blocks) {
         if (!this.blocks.equals(blocks)) {
            this.blocks = Set.copyOf(blocks);
            this.mesh = buildGhostMesh(this.blocks, this.includeFaces, this.cancelCoplanarSharedEdges, this.mergeEdges);
         }
         return this.mesh;
      }

      void clear() {
         this.blocks = Set.of();
         this.mesh = GhostMesh.empty();
      }
   }

   private static final class BuildingShellCache {
      private final boolean pending;
      private BlockState state;
      private Set<BlockPos> blocks = Set.of();
      private Set<BlockPos> shapeEnvironment = Set.of();
      private Map<BlockPos, BuildingSpecialBlock> specialStyles = Map.of();
      private boolean playerShift;
      private ShapeShellMesh.Mesh mesh = ShapeShellMesh.Mesh.empty();

      BuildingShellCache(boolean pending) {
         this.pending = pending;
      }

      ShapeShellMesh.Mesh mesh(
         BlockGetter previewLevel,
         BlockState state,
         net.minecraft.world.phys.shapes.CollisionContext collision,
         Set<BlockPos> blocks,
         Set<BlockPos> shapeEnvironment,
         Map<BlockPos, BuildingSpecialBlock> specialStyles,
         boolean playerShift
      ) {
         if (this.state == state
            && this.blocks.equals(blocks)
            && this.shapeEnvironment.equals(shapeEnvironment)
            && this.specialStyles.equals(specialStyles)
            && this.playerShift == playerShift) {
            return this.mesh;
         }

         this.state = state;
         this.blocks = Set.copyOf(blocks);
         this.shapeEnvironment = Set.copyOf(shapeEnvironment);
         this.specialStyles = Map.copyOf(specialStyles);
         this.playerShift = playerShift;

         ArrayList<ShapeShellMesh.Part> parts = new ArrayList<>(blocks.size());
         for (BlockPos pos : blocks) {
            List<AABB> boxes = state == null
               ? List.of(new AABB(pos))
               : state.getShape(previewLevel, pos, collision).toAabbs().stream()
                  .map(box -> box.move(pos))
                  .toList();
            if (boxes.isEmpty()) {
               continue;
            }

            BuildingSpecialBlock special = this.pending ? null : specialStyles.get(pos);
            ShapeShellMesh.Color faceColor = special == null
               ? ShapeShellMesh.Color.WHITE
               : new ShapeShellMesh.Color(special.style().red(), special.style().green(), special.style().blue());
            ShapeShellMesh.Color outlineColor = this.pending
               ? ShapeShellMesh.Color.WHITE
               : ShapeShellMesh.Color.BLACK;
            parts.add(new ShapeShellMesh.Part(boxes, faceColor, outlineColor, true));
         }
         this.mesh = ShapeShellMesh.build(parts);
         return this.mesh;
      }

      void clear() {
         this.state = null;
         this.blocks = Set.of();
         this.shapeEnvironment = Set.of();
         this.specialStyles = Map.of();
         this.playerShift = false;
         this.mesh = ShapeShellMesh.Mesh.empty();
      }
   }

   private static final class PendingGhostMeshCache {
      private Set<BlockPos> blocks = Set.of();
      private PendingGhostMesh mesh = PendingGhostMesh.empty();
      private Future<PendingMeshResult> future;
      private long version;

      PendingGhostMesh mesh(Set<BlockPos> blocks) {
         if (this.blocks != blocks && !this.blocks.equals(blocks)) {
            this.blocks = Set.copyOf(blocks);
            this.version++;
            if (this.future != null) {
               this.future.cancel(true);
            }
            PREVIEW_MESH_EXECUTOR.getQueue().clear();
            if (PreviewAsyncPolicy.meshSynchronously(this.blocks.size())) {
               this.mesh = buildPendingGhostMesh(this.blocks);
               this.future = null;
            } else {
               // Keep the last completed partial mesh visible while the next
               // larger progressive snapshot is merged in the background.
               long requestedVersion = this.version;
               Set<BlockPos> requestedBlocks = this.blocks;
               this.future = PREVIEW_MESH_EXECUTOR.submit(
                  () -> new PendingMeshResult(requestedVersion, buildPendingGhostMesh(requestedBlocks))
               );
            }
         }
         if (this.future != null && this.future.isDone()) {
            try {
               PendingMeshResult result = this.future.get();
               if (result.version() == this.version) {
                  this.mesh = result.mesh();
               }
            } catch (CancellationException ignored) {
               // A newer snapped preview superseded this mesh.
            } catch (InterruptedException exception) {
               Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException exception) {
               if (!(exception.getCause() instanceof CancellationException)) {
                  logPreviewFailure("Unable to build FastFormer preview mesh; suppressing pending mesh", exception.getCause());
                  this.mesh = PendingGhostMesh.empty();
               }
            } finally {
               this.future = null;
            }
         }
         return this.mesh;
      }

      void clearPreview() {
         if (this.blocks.isEmpty() && this.mesh.gridEdges().isEmpty() && this.future == null) {
            return;
         }
         this.blocks = Set.of();
         this.mesh = PendingGhostMesh.empty();
         this.version++;
         if (this.future != null) {
            this.future.cancel(true);
            this.future = null;
         }
         PREVIEW_MESH_EXECUTOR.getQueue().clear();
      }

      void clear() {
         this.clearPreview();
      }
   }

   private static final class PendingGhostBufferCache {
      private PendingGhostMesh mesh = PendingGhostMesh.empty();
      private VertexBuffer buffer;

      VertexBuffer buffer(PendingGhostMesh mesh) {
         if (this.mesh != mesh) {
            this.clear();
            this.mesh = mesh;
            if (!mesh.gridEdges().isEmpty()) {
               this.buffer = uploadPendingGrid(mesh.gridEdges());
            }
         }
         return this.buffer;
      }

      void clear() {
         if (this.buffer != null) {
            this.buffer.close();
            this.buffer = null;
         }
         this.mesh = PendingGhostMesh.empty();
      }
   }

   private record GhostMesh(List<GhostQuad> faces, List<GhostEdge> edges) {
      static GhostMesh empty() {
         return new GhostMesh(List.of(), List.of());
      }
   }

   private record PendingGhostMesh(List<PendingPreviewGrid.Segment> gridEdges) {
      private PendingGhostMesh {
         gridEdges = List.copyOf(gridEdges);
      }

      static PendingGhostMesh empty() {
         return new PendingGhostMesh(List.of());
      }
   }

   private record PendingMeshResult(long version, PendingGhostMesh mesh) {
   }

   private record GhostPlane(Direction direction, int coordinate) {
      static GhostPlane of(BlockPos pos, Direction direction) {
         int coordinate = switch (direction) {
            case DOWN -> pos.getY();
            case UP -> pos.getY() + 1;
            case NORTH -> pos.getZ();
            case SOUTH -> pos.getZ() + 1;
            case WEST -> pos.getX();
            case EAST -> pos.getX() + 1;
         };
         return new GhostPlane(direction, coordinate);
      }
   }

   private record FaceCell(int u, int v) {
      static FaceCell of(BlockPos pos, Direction direction) {
         return switch (direction.getAxis()) {
            case Y -> new FaceCell(pos.getX(), pos.getZ());
            case Z -> new FaceCell(pos.getX(), pos.getY());
            case X -> new FaceCell(pos.getZ(), pos.getY());
         };
      }
   }

   private record GhostQuad(Direction direction, double x0, double y0, double z0, double x1, double y1, double z1) {
      static GhostQuad of(GhostPlane plane, int u, int v, int width, int height) {
         return switch (plane.direction().getAxis()) {
            case Y -> new GhostQuad(plane.direction(), u, plane.coordinate(), v, u + width, plane.coordinate(), v + height);
            case Z -> new GhostQuad(plane.direction(), u, v, plane.coordinate(), u + width, v + height, plane.coordinate());
            case X -> new GhostQuad(plane.direction(), plane.coordinate(), v, u, plane.coordinate(), v + height, u + width);
         };
      }
   }

   private record GridPoint(int x, int y, int z) implements Comparable<GridPoint> {
      Vec3 vec3() {
         return new Vec3(this.x, this.y, this.z);
      }

      @Override
      public int compareTo(GridPoint other) {
         int xComparison = Integer.compare(this.x, other.x);
         if (xComparison != 0) {
            return xComparison;
         }
         int yComparison = Integer.compare(this.y, other.y);
         return yComparison != 0 ? yComparison : Integer.compare(this.z, other.z);
      }
   }

   private record GhostEdge(GridPoint from, GridPoint to) {
      static GhostEdge of(GridPoint first, GridPoint second) {
         return first.compareTo(second) <= 0 ? new GhostEdge(first, second) : new GhostEdge(second, first);
      }
   }

   private record GhostInterval(int start, int end) {
   }

   private record ScrollFeedbackData(List<AxisFeedback> axes, String text) {
      private ScrollFeedbackData {
         axes = axes == null ? List.of() : List.copyOf(axes);
         text = text == null ? "" : text;
      }
   }

   private record AxisFeedback(String label, String value, int color) {
   }

   private record GizmoHudInput(
      AxisGizmo gizmo,
      AxisGizmo.Axis dragAxis,
      AxisGizmo.Operation dragOperation,
      double dragBaseValue,
      int dragSteps,
      boolean allowNearBlock,
      boolean operationSelection
   ) {
   }

   private record TransformStatus(Vec3 position, Vec3 scale, Vec3 rotationDegrees) {
   }

   private record BuildingPreviewKey(long stateVersion, List<BlockPos> points, boolean heightConfirmed, boolean modifierHeld) {
      private BuildingPreviewKey {
         points = List.copyOf(points);
      }
   }

   private record BuildingBlockResult(BuildingPreviewKey key, Set<BlockPos> blocks) {
      private BuildingBlockResult {
         blocks = GenerationLimitExceeded.is(blocks) || GenerationFailed.is(blocks) ? blocks : Set.copyOf(blocks);
      }
   }

   private record GeometryPlanKey(
      long stateVersion,
      boolean modifierHeld,
      Vec3 eye,
      Vec3 view,
      GeometryHit candidate,
      BlockPos hoveredPoint
   ) {
   }

   private record BuildingRenderKey(
      long stateVersion,
      BuildingPreviewKey previewKey,
      long previewResultVersion,
      BlockPos candidate,
      BlockPos hoveredPoint
   ) {
   }

   private record BuildingRenderLayers(
      Set<BlockPos> confirmedRenderBlocks,
      Set<BlockPos> pendingRenderBlocks,
      Set<BlockPos> allBlocks
   ) {
      private BuildingRenderLayers {
         confirmedRenderBlocks = Set.copyOf(confirmedRenderBlocks);
         pendingRenderBlocks = Set.copyOf(pendingRenderBlocks);
         allBlocks = Set.copyOf(allBlocks);
      }

      static BuildingRenderLayers empty() {
         return new BuildingRenderLayers(Set.of(), Set.of(), Set.of());
      }
   }

   private record BuildingSpecialBlock(ControlPointStyle style, boolean confirmed) {
   }

   private record GeometryRenderLayers(Set<BlockPos> confirmed, Set<BlockPos> pending) {
      private GeometryRenderLayers {
         confirmed = Set.copyOf(confirmed);
         pending = Set.copyOf(pending);
      }

      static GeometryRenderLayers empty() {
         return new GeometryRenderLayers(Set.of(), Set.of());
      }
   }

   private record GhostEdgeLine(Direction.Axis axis, int fixedA, int fixedB) {
      static GhostEdgeLine of(GhostEdge edge) {
         GridPoint from = edge.from();
         GridPoint to = edge.to();
         if (from.x() != to.x()) {
            return new GhostEdgeLine(Direction.Axis.X, from.y(), from.z());
         }
         if (from.y() != to.y()) {
            return new GhostEdgeLine(Direction.Axis.Y, from.x(), from.z());
         }
         return new GhostEdgeLine(Direction.Axis.Z, from.x(), from.y());
      }

      GhostInterval interval(GhostEdge edge) {
         return switch (this.axis) {
            case X -> new GhostInterval(edge.from().x(), edge.to().x());
            case Y -> new GhostInterval(edge.from().y(), edge.to().y());
            case Z -> new GhostInterval(edge.from().z(), edge.to().z());
         };
      }

      GhostEdge edge(int start, int end) {
         return switch (this.axis) {
            case X -> GhostEdge.of(new GridPoint(start, this.fixedA, this.fixedB), new GridPoint(end, this.fixedA, this.fixedB));
            case Y -> GhostEdge.of(new GridPoint(this.fixedA, start, this.fixedB), new GridPoint(this.fixedA, end, this.fixedB));
            case Z -> GhostEdge.of(new GridPoint(this.fixedA, this.fixedB, start), new GridPoint(this.fixedA, this.fixedB, end));
         };
      }
   }
}
