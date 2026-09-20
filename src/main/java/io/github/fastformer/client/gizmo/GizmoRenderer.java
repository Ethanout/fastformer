package io.github.fastformer.client.gizmo;

import static io.github.fastformer.client.render.type.PreviewRenderTypes.*;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.fastformer.client.render.geometry.PreviewGeometrySupport;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.PlaneAxes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource.BufferSource;
import net.minecraft.world.phys.Vec3;

public final class GizmoRenderer {
   private static final double EPSILON = 1.0E-7;
   private final float opacity;

   public GizmoRenderer(float opacity) {
      this.opacity = opacity;
   }

   private void renderLine(PoseStack poses, VertexConsumer vertices, Vec3 from, Vec3 to,
      float red, float green, float blue, float alpha) {
      io.github.fastformer.client.render.guide.GuideRenderer.renderLine(
         poses, vertices, from, to, red, green, blue, alpha, opacity);
   }

   private void addGhostQuad(PoseStack poses, VertexConsumer vertices, Vec3 a, Vec3 b, Vec3 c, Vec3 d,
      float red, float green, float blue, float alpha) {
      io.github.fastformer.client.render.geometry.PreviewQuads.write(
         poses, vertices, a, b, c, d, red, green, blue, alpha * opacity);
   }

   public void renderGeometryGizmo(PoseStack poseStack, BufferSource buffers, Vec3 camera, AxisGizmo gizmo) {
      renderGeometryGizmo(poseStack, buffers, camera, gizmo, 1.0F);
   }

   /**
    * Draws the two workspace handle sets. The world gizmo owns the axis shafts,
    * the move handles, and the rotation rings. The local gizmo contributes only
    * its scale handles, so the scale axes follow the part rotation while the move
    * and rotate axes stay in world space.
    */
   public void renderWorkspaceGizmo(
      PoseStack poseStack,
      BufferSource buffers,
      Vec3 camera,
      AxisGizmo worldGizmo,
      AxisGizmo scaleGizmo,
      float alphaScale
   ) {
      renderGeometryGizmo(poseStack, buffers, camera, worldGizmo, alphaScale);
      renderGizmoScaleHandles(poseStack, buffers, camera, scaleGizmo, alphaScale);
   }

   /** Draws only the scale solids of one gizmo; the world gizmo already drew every axis. */
   private void renderGizmoScaleHandles(
      PoseStack poseStack, BufferSource buffers, Vec3 camera, AxisGizmo gizmo, float alphaScale
   ) {
      if (gizmo == null || gizmo.handles().isEmpty()) {
         return;
      }
      poseStack.pushPose();
      poseStack.translate(-camera.x, -camera.y, -camera.z);
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

   public void renderGeometryGizmo(
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
   public void renderLocalWorkspaceGizmo(
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
         Vec3 localAxis = PreviewGeometrySupport.rotateLocalAxis(axis, rotation);
         boolean highlighted = axis == highlightedAxis;
         float[] color = PreviewGeometrySupport.softLocalAxisColor(axis);
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

   private void renderColoredDashedLine(
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

   private void renderGizmoLineHandles(
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

   public static float operationGizmoAlpha(AxisGizmo gizmo) {
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

   private void renderRotationRing(
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

   private void renderMoveArrow(
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
      double length = direction.length();
      direction = length < EPSILON ? Vec3.ZERO : direction.scale(1.0 / length);
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

   private void renderScaleHandle(
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

   private void renderSolidBox(
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

}
