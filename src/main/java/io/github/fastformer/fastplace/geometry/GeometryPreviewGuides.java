package io.github.fastformer.fastplace.geometry;

import io.github.fastformer.fastplace.ConePlaneMode;
import io.github.fastformer.fastplace.PolyhedronSizeMode;
import java.util.ArrayList;
import java.util.List;
import io.github.fastformer.fastplace.geometry.generation.ConePrismGenerator;
import io.github.fastformer.fastplace.geometry.generation.ConePrismGeometry;
import io.github.fastformer.fastplace.geometry.generation.ConePrismParameters;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class GeometryPreviewGuides {
   private GeometryPreviewGuides() {
   }

   public static List<GuideLine> polyline(List<BlockPos> points, boolean closed) {
      if (points.size() < 2) {
         return List.of();
      }
      ArrayList<GuideLine> lines = new ArrayList<>(points.size());
      for (int i = 1; i < points.size(); i++) {
         lines.add(new GuideLine(Vec3.atCenterOf(points.get(i - 1)), Vec3.atCenterOf(points.get(i))));
      }
      if (closed && points.size() >= 3) {
         lines.add(new GuideLine(Vec3.atCenterOf(points.getLast()), Vec3.atCenterOf(points.getFirst())));
      }
      return List.copyOf(lines);
   }

   public static List<GuideLine> coneHeight(ConePrismParameters parameters) {
      if (parameters.heightPoint().isEmpty()) {
         return List.of();
      }
      ConePrismGeometry.Base base = ConePrismGenerator.baseInfo(parameters);
      if (base == null) {
         return List.of();
      }
      return List.of(new GuideLine(base.center(), parameters.heightPoint().orElseThrow()));
   }

   public static List<ControlPoint> confirmedPoints(List<Vec3> points, List<ControlPointRole> roles) {
      ArrayList<ControlPoint> result = new ArrayList<>(points.size());
      for (int index = 0; index < points.size(); index++) {
         Vec3 point = points.get(index);
         ControlPointRole role = index < roles.size()
            ? roles.get(index)
            : index == 0 ? ControlPointRole.PRIMARY : ControlPointRole.SECONDARY;
         result.add(ControlPoint.precise(point, role));
      }
      return List.copyOf(result);
   }

   public static List<ControlPoint> polyhedronPoints(
      List<Vec3> points,
      List<ControlPointRole> confirmedRoles,
      PolyhedronSizeMode mode,
      int confirmedCount
   ) {
      if (points.isEmpty()) {
         return List.of();
      }
      ArrayList<ControlPoint> result = new ArrayList<>(points.size());
      PolyhedronSizeMode effectiveMode = mode == null ? PolyhedronSizeMode.RADIUS : mode;
      for (int i = 0; i < points.size(); i++) {
         Vec3 point = points.get(i);
         ControlPointRole role = i < confirmedCount && i < confirmedRoles.size()
            ? confirmedRoles.get(i)
            : polyhedronRole(effectiveMode, i);
         ControlPoint controlPoint = i < confirmedCount
            ? ControlPoint.precise(point, role)
            : ControlPoint.pending(point, role);
         result.add(controlPoint);
      }
      if (effectiveMode == PolyhedronSizeMode.DIAMETER && points.size() >= 2) {
         Vec3 center = points.getFirst().add(points.get(1)).scale(0.5);
         result.add(ControlPoint.derived(center, ControlPointRole.DERIVED_CENTER));
      }
      return List.copyOf(result);
   }

   public static List<ControlPoint> conePoints(
      ConePrismParameters parameters, List<ControlPointRole> confirmedRoles, int confirmedCount, boolean pointOnly
   ) {
      List<Vec3> points = parameters.points();
      if (points.isEmpty()) {
         return List.of();
      }
      ArrayList<ControlPoint> result = new ArrayList<>(points.size());
      ConePlaneMode mode = parameters.planeMode();
      int facePoints = mode.facePointCount();
      for (int i = 0; i < points.size(); i++) {
         ControlPointRole role = i < confirmedCount && i < confirmedRoles.size()
            ? confirmedRoles.get(i)
            : i < facePoints ? coneFaceRole(mode, i) : ControlPointRole.HEIGHT;
         Vec3 point = points.get(i);
         Vec3 visualPoint = pointOnly && i >= confirmedCount
            ? Vec3.atCenterOf(BlockPos.containing(point))
            : point;
         ControlPoint controlPoint = i < confirmedCount
            ? ControlPoint.precise(visualPoint, role)
            : ControlPoint.pending(visualPoint, role);
         if (pointOnly && controlPoint.shape() != ControlPointShape.BLOCK) {
            controlPoint = controlPoint.withShape(ControlPointShape.POINT);
         }
         result.add(controlPoint);
      }
      if (mode != ConePlaneMode.RADIUS && points.size() >= facePoints) {
         ConePrismGeometry.Base base = ConePrismGenerator.baseInfo(parameters);
         if (base != null) {
            result.add(ControlPoint.derived(base.center(), ControlPointRole.DERIVED_CENTER));
         }
      }
      return List.copyOf(result);
   }

   private static ControlPointRole coneFaceRole(ConePlaneMode mode, int index) {
      return switch (mode) {
         case RADIUS -> index == 0 ? ControlPointRole.BASE_CENTER : ControlPointRole.RADIUS;
         case DIAMETER -> index == 0 ? ControlPointRole.DIAMETER_A : ControlPointRole.DIAMETER_B;
         case THREE_POINT -> index == 0 ? ControlPointRole.PRIMARY : ControlPointRole.BASE_FACE;
      };
   }

   private static ControlPointRole polyhedronRole(PolyhedronSizeMode mode, int index) {
      return mode == PolyhedronSizeMode.RADIUS
         ? (index == 0 ? ControlPointRole.CENTER : ControlPointRole.RADIUS)
         : (index == 0 ? ControlPointRole.DIAMETER_A : ControlPointRole.DIAMETER_B);
   }
}
