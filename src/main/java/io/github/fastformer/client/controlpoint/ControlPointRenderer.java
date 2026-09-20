package io.github.fastformer.client.controlpoint;

import static io.github.fastformer.client.render.type.PreviewRenderTypes.*;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.fastformer.client.render.WorkspacePreviewRenderer;
import io.github.fastformer.client.render.geometry.DashedBoxRenderer;
import io.github.fastformer.fastplace.geometry.ControlPoint;
import io.github.fastformer.fastplace.geometry.ControlPointStyle;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Draws confirmed and candidate control points from a completed display list. */
public final class ControlPointRenderer {
   private static final double CONTROL_POINT_OUTLINE_INFLATE = 0.003;
   private static final float OCCLUDED_POINT_ALPHA = 0.38F;
   private final float worldPreviewOpacity;
   private final double dashOffset;
   private final double dashLength;

   public ControlPointRenderer(float opacity, double dashOffset, double dashLength) {
      this.worldPreviewOpacity = opacity;
      this.dashOffset = dashOffset;
      this.dashLength = dashLength;
   }

   public void render(PoseStack poseStack, BufferSource buffers, Vec3 camera, List<ControlPoint> points) {
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

      Minecraft minecraft = Minecraft.getInstance();
      for (ControlPoint point : points) {
         if (!point.hovered()) {
            continue;
         }
         WorkspacePreviewRenderer.renderHintLabel(
            poseStack,
            buffers,
            minecraft,
            camera,
            point.center().add(0.0, point.shape().visualHalfExtents(point.center()).y + 0.08, 0.0),
            point.hoverText().getString()
         );
      }
   }

   private void renderConfirmedControlPointOutlines(
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

   private void renderPendingControlPoints(
      PoseStack poseStack,
      VertexConsumer lines,
      Vec3 camera,
      List<ControlPoint> points,
      float alphaScale
   ) {
      poseStack.pushPose();
      poseStack.translate(-camera.x, -camera.y, -camera.z);
      double offset = dashOffset;
      for (ControlPoint point : points) {
         if (point.confirmed()) {
            continue;
         }
         ControlPointStyle style = point.feedback().style(point.role(), point.hovered());
         renderPendingBox(
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

   private void addControlPointBox(
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

   private void renderPendingBox(PoseStack poseStack, VertexConsumer lines, Vec3 center,
      Vec3 halfExtents, double offset, float alpha) {
      DashedBoxRenderer.render(poseStack, lines, center, halfExtents, null, 0.0,
         offset, alpha, dashLength, worldPreviewOpacity);
   }
}
