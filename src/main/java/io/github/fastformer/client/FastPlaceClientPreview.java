package io.github.fastformer.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.fastformer.FastFormer;
import io.github.fastformer.fastplace.FaceMode;
import io.github.fastformer.fastplace.VolumeMode;
import io.github.fastformer.network.PreviewStatePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.List;

@EventBusSubscriber(modid = FastFormer.MOD_ID, value = Dist.CLIENT)
public final class FastPlaceClientPreview {
    private static final double PREVIEW_REACH = 128.0;
    private static final float SHAPE_RED = 0.25F;
    private static final float SHAPE_GREEN = 0.85F;
    private static final float SHAPE_BLUE = 1.0F;
    private static final float SELECTED_RED = 0.25F;
    private static final float SELECTED_GREEN = 1.0F;
    private static final float SELECTED_BLUE = 0.45F;
    private static final float CANDIDATE_RED = 1.0F;
    private static final float CANDIDATE_GREEN = 0.82F;
    private static final float CANDIDATE_BLUE = 0.25F;
    private static final float ALPHA = 0.9F;
    private static final double EPSILON = 1.0E-6;

    private static PreviewStatePayload state = PreviewStatePayload.inactive();

    private FastPlaceClientPreview() {
    }

    public static void apply(PreviewStatePayload payload) {
        state = payload;
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        PreviewStatePayload snapshot = state;
        if (!snapshot.active()
                || snapshot.points().isEmpty()
                || event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }

        BlockPos candidate = candidatePoint(minecraft);
        Outline outline = buildOutline(snapshot, candidate);
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer consumer = buffers.getBuffer(RenderType.lines());
        PoseStack poseStack = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();

        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        for (BlockPos point : snapshot.points()) {
            renderBlockBox(poseStack, consumer, point, SELECTED_RED, SELECTED_GREEN, SELECTED_BLUE);
        }
        if (candidate != null && !snapshot.points().contains(candidate)) {
            renderBlockBox(poseStack, consumer, candidate, CANDIDATE_RED, CANDIDATE_GREEN, CANDIDATE_BLUE);
        }
        renderOutline(poseStack, consumer, outline);
        poseStack.popPose();

        buffers.endBatch(RenderType.lines());
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        state = PreviewStatePayload.inactive();
    }

    private static BlockPos candidatePoint(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        if (player == null || !(player.getMainHandItem().getItem() instanceof BlockItem)) {
            return null;
        }

        BlockHitResult hit = raycastBlocks(player, PREVIEW_REACH);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        return hit.getBlockPos().relative(hit.getDirection());
    }

    private static BlockHitResult raycastBlocks(LocalPlayer player, double range) {
        Level level = player.level();
        Vec3 start = player.getEyePosition();
        Vec3 end = start.add(player.getViewVector(1.0F).scale(range));
        ClipContext context = new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player);
        return level.clip(context);
    }

    private static Outline buildOutline(PreviewStatePayload snapshot, BlockPos candidate) {
        List<BlockPos> points = snapshot.points();
        if (points.size() == 1) {
            if (candidate == null || candidate.equals(points.getFirst())) {
                return Outline.empty();
            }
            return Outline.line(Vec3.atCenterOf(points.getFirst()), Vec3.atCenterOf(candidate));
        }

        if (points.size() == 2) {
            if (candidate == null || candidate.equals(points.getLast())) {
                return Outline.line(Vec3.atCenterOf(points.getFirst()), Vec3.atCenterOf(points.getLast()));
            }
            return buildFaceOutline(points, candidate, snapshot.faceMode());
        }

        if (snapshot.volumeMode() == VolumeMode.POLYHEDRON) {
            return buildPointChainOutline(points, candidate, true);
        }

        BlockPos extrudePoint = candidate;
        if (extrudePoint == null && points.size() >= 4) {
            extrudePoint = points.get(3);
        }
        if (extrudePoint == null) {
            return buildFaceOutline(points, null, snapshot.faceMode());
        }
        return buildVolumeOutline(points, extrudePoint, snapshot.faceMode());
    }

    private static Outline buildFaceOutline(List<BlockPos> points, BlockPos candidate, FaceMode faceMode) {
        if (faceMode == FaceMode.POLYGON) {
            return buildPointChainOutline(points, candidate, true);
        }

        if (points.size() < 2) {
            return Outline.empty();
        }

        BlockPos pPos = candidate;
        if (pPos == null && points.size() >= 3) {
            pPos = points.get(2);
        }
        if (pPos == null) {
            return Outline.line(Vec3.atCenterOf(points.getFirst()), Vec3.atCenterOf(points.get(1)));
        }

        Vec3 a = Vec3.atCenterOf(points.getFirst());
        Vec3 b = Vec3.atCenterOf(points.get(1));
        Vec3 p = Vec3.atCenterOf(pPos);
        Vec3 ab = b.subtract(a);
        if (ab.lengthSqr() < EPSILON) {
            return Outline.empty();
        }

        Vec3 c;
        Vec3 d;
        if (faceMode == FaceMode.PARALLELOGRAM) {
            Vec3 half = ab.scale(0.5);
            d = p.subtract(half);
            c = p.add(half);
        } else {
            double t = p.subtract(a).dot(ab) / ab.lengthSqr();
            Vec3 foot = a.add(ab.scale(t));
            Vec3 offset = p.subtract(foot);
            d = a.add(offset);
            c = b.add(offset);
        }
        return Outline.closed(List.of(a, b, c, d));
    }

    private static Outline buildVolumeOutline(List<BlockPos> points, BlockPos extrudePoint, FaceMode faceMode) {
        List<Vec3> base = buildBaseVertices(points, faceMode);
        if (base.size() < 3) {
            return buildPointChainOutline(points, extrudePoint, false);
        }

        BlockPos anchor = faceMode == FaceMode.POLYGON ? points.getLast() : points.get(2);
        Vec3 extrude = Vec3.atCenterOf(extrudePoint).subtract(Vec3.atCenterOf(anchor));
        if (extrude.lengthSqr() < EPSILON) {
            return Outline.closed(base);
        }

        List<Vec3> vertices = new ArrayList<>(base.size() * 2);
        vertices.addAll(base);
        for (Vec3 vertex : base) {
            vertices.add(vertex.add(extrude));
        }

        int size = base.size();
        List<int[]> edges = new ArrayList<>(size * 3);
        for (int i = 0; i < size; i++) {
            int next = (i + 1) % size;
            edges.add(new int[] { i, next });
            edges.add(new int[] { i + size, next + size });
            edges.add(new int[] { i, i + size });
        }
        return new Outline(vertices, edges);
    }

    private static List<Vec3> buildBaseVertices(List<BlockPos> points, FaceMode faceMode) {
        if (faceMode == FaceMode.POLYGON) {
            return points.stream().map(Vec3::atCenterOf).toList();
        }
        return buildFaceOutline(points, null, faceMode).vertices();
    }

    private static Outline buildPointChainOutline(List<BlockPos> points, BlockPos candidate, boolean close) {
        List<Vec3> vertices = new ArrayList<>(points.size() + 1);
        for (BlockPos point : points) {
            vertices.add(Vec3.atCenterOf(point));
        }
        if (candidate != null && (points.isEmpty() || !candidate.equals(points.getLast()))) {
            vertices.add(Vec3.atCenterOf(candidate));
        }

        if (vertices.size() < 2) {
            return Outline.empty();
        }

        List<int[]> edges = new ArrayList<>();
        for (int i = 0; i < vertices.size() - 1; i++) {
            edges.add(new int[] { i, i + 1 });
        }
        if (close && vertices.size() >= 3) {
            edges.add(new int[] { vertices.size() - 1, 0 });
        }
        return new Outline(vertices, edges);
    }

    private static void renderBlockBox(PoseStack poseStack, VertexConsumer consumer, BlockPos pos, float red, float green, float blue) {
        LevelRenderer.renderLineBox(poseStack, consumer, new AABB(pos).inflate(0.003), red, green, blue, ALPHA);
    }

    private static void renderOutline(PoseStack poseStack, VertexConsumer consumer, Outline outline) {
        for (int[] edge : outline.edges()) {
            Vec3 from = outline.vertices().get(edge[0]);
            Vec3 to = outline.vertices().get(edge[1]);
            renderLine(poseStack, consumer, from, to, SHAPE_RED, SHAPE_GREEN, SHAPE_BLUE, ALPHA);
        }
    }

    private static void renderLine(
            PoseStack poseStack,
            VertexConsumer consumer,
            Vec3 from,
            Vec3 to,
            float red,
            float green,
            float blue,
            float alpha) {
        Vec3 normal = to.subtract(from);
        double lengthSqr = normal.lengthSqr();
        if (lengthSqr < EPSILON) {
            return;
        }

        normal = normal.scale(1.0 / Math.sqrt(lengthSqr));
        PoseStack.Pose pose = poseStack.last();
        consumer.addVertex(pose, (float) from.x, (float) from.y, (float) from.z)
                .setColor(red, green, blue, alpha)
                .setNormal(pose, (float) normal.x, (float) normal.y, (float) normal.z);
        consumer.addVertex(pose, (float) to.x, (float) to.y, (float) to.z)
                .setColor(red, green, blue, alpha)
                .setNormal(pose, (float) normal.x, (float) normal.y, (float) normal.z);
    }

    private record Outline(List<Vec3> vertices, List<int[]> edges) {
        private static Outline empty() {
            return new Outline(List.of(), List.of());
        }

        private static Outline line(Vec3 a, Vec3 b) {
            return new Outline(List.of(a, b), List.of(new int[] { 0, 1 }));
        }

        private static Outline closed(List<Vec3> vertices) {
            if (vertices.size() < 2) {
                return empty();
            }

            List<int[]> edges = new ArrayList<>();
            for (int i = 0; i < vertices.size(); i++) {
                edges.add(new int[] { i, (i + 1) % vertices.size() });
            }
            return new Outline(List.copyOf(vertices), edges);
        }
    }
}
