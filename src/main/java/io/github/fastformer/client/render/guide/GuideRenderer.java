package io.github.fastformer.client.render.guide;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.PoseStack.Pose;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.fastformer.fastplace.geometry.GuideLine;
import io.github.fastformer.fastplace.geometry.GuidePlane;
import io.github.fastformer.fastplace.geometry.PlaneAxes;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Draws geometry and building guide planes and lines. */
public final class GuideRenderer {
   private static final double MIN_PLANE_RADIUS = 2.0;
   private static final double PLANE_DISTANCE_SCALE = 0.08;
   private static final float PLANE_RED = 0.18F;
   private static final float PLANE_GREEN = 0.78F;
   private static final float PLANE_BLUE = 1.0F;
   private static final double EPSILON = 1.0E-7;

   private GuideRenderer() {
   }

   public static void renderGeometryPlanes(
      PoseStack poseStack,
      VertexConsumer consumer,
      Vec3 camera,
      List<GuidePlane> planes,
      float opacity
   ) {
      renderPlanes(poseStack, consumer, camera, planes, false, opacity);
   }

   public static void renderBuildingPlanes(
      PoseStack poseStack,
      VertexConsumer consumer,
      Vec3 camera,
      List<GuidePlane> planes,
      float opacity
   ) {
      renderPlanes(poseStack, consumer, camera, planes, true, opacity);
   }

   public static void renderGeometryLines(
      PoseStack poseStack,
      VertexConsumer consumer,
      Vec3 camera,
      List<GuideLine> lines,
      float opacity
   ) {
      poseStack.pushPose();
      poseStack.translate(-camera.x, -camera.y, -camera.z);
      for (GuideLine line : lines) {
         renderLine(poseStack, consumer, line.from(), line.to(), opacity);
      }
      poseStack.popPose();
   }

   public static void renderBuildingLines(
      PoseStack poseStack,
      VertexConsumer consumer,
      Vec3 camera,
      List<GuideLine> lines,
      float dashLength,
      float opacity
   ) {
      poseStack.pushPose();
      poseStack.translate(-camera.x, -camera.y, -camera.z);
      for (GuideLine line : lines) {
         renderAlternatingDashedLine(
            poseStack, consumer, line.from(), line.to(), 0.72F, 0.0, dashLength, opacity
         );
      }
      poseStack.popPose();
   }

   public static void renderLine(
      PoseStack poseStack,
      VertexConsumer consumer,
      Vec3 from,
      Vec3 to,
      float opacity
   ) {
      renderLine(poseStack, consumer, from, to, PLANE_RED, PLANE_GREEN, PLANE_BLUE, 0.68F, opacity);
   }

   public static void renderLine(
      PoseStack poseStack,
      VertexConsumer consumer,
      Vec3 from,
      Vec3 to,
      float red,
      float green,
      float blue,
      float alpha,
      float opacity
   ) {
      Vec3 normal = normalize(to.subtract(from));
      if (normal.lengthSqr() < EPSILON) {
         return;
      }
      Pose pose = poseStack.last();
      float visibleAlpha = alpha * opacity;
      consumer.addVertex(pose, (float)from.x, (float)from.y, (float)from.z)
         .setColor(red, green, blue, visibleAlpha)
         .setNormal(pose, (float)normal.x, (float)normal.y, (float)normal.z);
      consumer.addVertex(pose, (float)to.x, (float)to.y, (float)to.z)
         .setColor(red, green, blue, visibleAlpha)
         .setNormal(pose, (float)normal.x, (float)normal.y, (float)normal.z);
   }

   public static void renderAlternatingDashedLine(
      PoseStack poseStack,
      VertexConsumer consumer,
      Vec3 from,
      Vec3 to,
      float alpha,
      double offset,
      double dashLength,
      float opacity
   ) {
      Vec3 vector = to.subtract(from);
      double length = vector.length();
      if (length < EPSILON) {
         return;
      }
      Vec3 direction = vector.scale(1.0 / length);
      int index = (int)Math.floor(-offset / dashLength) - 1;
      for (double start = index * dashLength + offset; start < length; start += dashLength, index++) {
         double clippedStart = Math.max(0.0, start);
         double clippedEnd = Math.min(length, start + dashLength);
         if (clippedEnd <= clippedStart) {
            continue;
         }
         float tone = Math.floorMod(index, 2) == 0 ? 1.0F : 0.0F;
         renderLine(
            poseStack,
            consumer,
            from.add(direction.scale(clippedStart)),
            from.add(direction.scale(clippedEnd)),
            tone,
            tone,
            tone,
            alpha,
            opacity
         );
      }
   }

   private static void renderPlanes(
      PoseStack poseStack,
      VertexConsumer consumer,
      Vec3 camera,
      List<GuidePlane> planes,
      boolean buildingStyle,
      float opacity
   ) {
      poseStack.pushPose();
      poseStack.translate(-camera.x, -camera.y, -camera.z);
      for (GuidePlane plane : planes) {
         Vec3 normal = normalize(plane.normal());
         if (normal.lengthSqr() < EPSILON || !plane.showWhenAxisAligned() && isAxisAligned(normal)) {
            continue;
         }
         PlaneAxes axes = PlaneAxes.fromNormal(normal);
         double radius = planeRadius(plane, camera, axes);
         renderGrid(
            poseStack,
            consumer,
            plane.center(),
            axes.horizontal(),
            axes.vertical(),
            radius,
            buildingStyle,
            opacity
         );
      }
      poseStack.popPose();
   }

   private static double planeRadius(GuidePlane plane, Vec3 camera, PlaneAxes axes) {
      Vec3 center = plane.center();
      double radius = Math.max(MIN_PLANE_RADIUS, camera.distanceTo(center) * PLANE_DISTANCE_SCALE);
      for (Vec3 bound : plane.bounds()) {
         Vec3 delta = bound.subtract(center);
         radius = Math.max(radius, Math.abs(delta.dot(axes.horizontal())) + 1.0);
         radius = Math.max(radius, Math.abs(delta.dot(axes.vertical())) + 1.0);
      }
      return radius;
   }

   private static boolean isAxisAligned(Vec3 normal) {
      return Math.max(Math.abs(normal.x), Math.max(Math.abs(normal.y), Math.abs(normal.z))) > 0.9999;
   }

   private static void renderGrid(
      PoseStack poseStack,
      VertexConsumer consumer,
      Vec3 center,
      Vec3 horizontal,
      Vec3 vertical,
      double radius,
      boolean buildingStyle,
      float opacity
   ) {
      int gridSize = (int)Math.ceil(radius);
      int spacing = Math.max(1, (int)Math.ceil((double)gridSize / 24.0));
      int gridLines = (int)Math.ceil((double)gridSize / spacing);
      float gridAlpha = buildingStyle ? 0.14F : 0.35F;
      float axisAlpha = buildingStyle ? 0.28F : 0.68F;
      for (int index = -gridLines; index <= gridLines; index++) {
         Vec3 offset = vertical.scale(index * spacing);
         renderGridLine(
            poseStack,
            consumer,
            center.add(offset).subtract(horizontal.scale(radius)),
            center.add(offset).add(horizontal.scale(radius)),
            center,
            spacing,
            index == 0 ? axisAlpha : gridAlpha,
            buildingStyle,
            opacity
         );
      }
      for (int index = -gridLines; index <= gridLines; index++) {
         Vec3 offset = horizontal.scale(index * spacing);
         renderGridLine(
            poseStack,
            consumer,
            center.add(offset).subtract(vertical.scale(radius)),
            center.add(offset).add(vertical.scale(radius)),
            center,
            spacing,
            index == 0 ? axisAlpha : gridAlpha,
            buildingStyle,
            opacity
         );
      }
   }

   private static void renderGridLine(
      PoseStack poseStack,
      VertexConsumer consumer,
      Vec3 from,
      Vec3 to,
      Vec3 center,
      double spacing,
      float alpha,
      boolean buildingStyle,
      float opacity
   ) {
      if (buildingStyle) {
         renderGridPointContrastLine(poseStack, consumer, from, to, center, spacing, alpha, opacity);
      } else {
         renderLine(poseStack, consumer, from, to, PLANE_RED, PLANE_GREEN, PLANE_BLUE, alpha, opacity);
      }
   }

   private static void renderGridPointContrastLine(
      PoseStack poseStack,
      VertexConsumer consumer,
      Vec3 from,
      Vec3 to,
      Vec3 gridOrigin,
      double spacing,
      float alpha,
      float opacity
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
         double boundary = Math.min(end, Math.floor(cursor / halfSpacing + 1.0 + 1.0E-9) * halfSpacing);
         if (boundary <= cursor + EPSILON) {
            boundary = Math.min(end, cursor + halfSpacing);
         }
         renderGradientLine(
            poseStack,
            consumer,
            from.add(direction.scale(cursor - start)),
            from.add(direction.scale(boundary - start)),
            gridPointTone(cursor, spacing),
            gridPointTone(boundary, spacing),
            alpha,
            opacity
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
      float alpha,
      float opacity
   ) {
      Vec3 normal = normalize(to.subtract(from));
      if (normal.lengthSqr() < EPSILON) {
         return;
      }
      Pose pose = poseStack.last();
      float visibleAlpha = alpha * opacity;
      consumer.addVertex(pose, (float)from.x, (float)from.y, (float)from.z)
         .setColor(fromTone, fromTone, fromTone, visibleAlpha)
         .setNormal(pose, (float)normal.x, (float)normal.y, (float)normal.z);
      consumer.addVertex(pose, (float)to.x, (float)to.y, (float)to.z)
         .setColor(toTone, toTone, toTone, visibleAlpha)
         .setNormal(pose, (float)normal.x, (float)normal.y, (float)normal.z);
   }

   private static Vec3 normalize(Vec3 vector) {
      double length = vector.length();
      return length < EPSILON ? Vec3.ZERO : vector.scale(1.0 / length);
   }
}
