package io.github.fastformer.client.render.geometry;

import io.github.fastformer.fastplace.FaceMode;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.GuideLine;
import io.github.fastformer.fastplace.geometry.SelectionPrism;
import io.github.fastformer.fastplace.geometry.generation.PlanarFaceGeometry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Stateless geometry helpers shared by preview renderers. */
public final class PreviewGeometrySupport {
   private PreviewGeometrySupport() {
   }

   public static Set<BlockPos> withoutBlocks(Set<BlockPos> blocks, Set<BlockPos> excluded) {
      if (blocks.isEmpty() || excluded.isEmpty() || excluded.stream().noneMatch(blocks::contains)) {
         return blocks;
      }
      HashSet<BlockPos> result = new HashSet<>(blocks);
      result.removeAll(excluded);
      return result;
   }

   public static Set<BlockPos> unionBlocks(Set<BlockPos> first, Set<BlockPos> second) {
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

   public static List<GuideLine> outlineGeometryEdges(List<BlockPos> points, FaceMode faceMode) {
      if (points == null || points.size() < 2 || faceMode == FaceMode.POLYGON) {
         return List.of();
      }
      if (points.size() == 2) {
         return List.of(new GuideLine(Vec3.atCenterOf(points.getFirst()), Vec3.atCenterOf(points.getLast())));
      }
      List<Vec3> base = PlanarFaceGeometry.vertices(points, faceMode);
      if (base.size() != 4) {
         return List.of();
      }
      if (points.size() == 3) {
         return closedEdges(base);
      }
      Vec3 anchor = Vec3.atCenterOf(points.get(2));
      Vec3 extrusion = Vec3.atCenterOf(points.get(3)).subtract(anchor);
      if (extrusion.lengthSqr() < 1.0E-7) {
         return closedEdges(base);
      }
      return new SelectionPrism(base, extrusion).edges();
   }

   public static boolean hasNonOrthogonalRotation(Vec3 rotation) {
      double quarterTurn = Math.PI * 0.5;
      return Math.abs(rotation.x - Math.rint(rotation.x / quarterTurn) * quarterTurn) > 1.0E-5
         || Math.abs(rotation.y - Math.rint(rotation.y / quarterTurn) * quarterTurn) > 1.0E-5
         || Math.abs(rotation.z - Math.rint(rotation.z / quarterTurn) * quarterTurn) > 1.0E-5;
   }

   public static Vec3 rotateLocalAxis(AxisGizmo.Axis axis, Vec3 rotation) {
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

   public static float[] softLocalAxisColor(AxisGizmo.Axis axis) {
      return switch (axis) {
         case X -> new float[]{1.0F, 0.54F, 0.58F};
         case Y -> new float[]{0.56F, 0.92F, 0.62F};
         case Z -> new float[]{0.54F, 0.70F, 1.0F};
      };
   }

   private static List<GuideLine> closedEdges(List<Vec3> vertices) {
      ArrayList<GuideLine> edges = new ArrayList<>(vertices.size());
      for (int index = 0; index < vertices.size(); index++) {
         edges.add(new GuideLine(vertices.get(index), vertices.get((index + 1) % vertices.size())));
      }
      return List.copyOf(edges);
   }
}
