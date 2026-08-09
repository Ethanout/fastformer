package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.geometry.ControlPointRole;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

final class GeometryPoints {
   private GeometryPoints() {
   }

   static Polyhedron polyhedron(List<Vec3> points, List<ControlPointRole> roles, PolyhedronSizeMode mode) {
      return new Polyhedron(points, roles, mode);
   }

   static Cone cone(List<GeometryPoint> points, ConePlaneMode mode) {
      return new Cone(points, mode);
   }

   record Polyhedron(List<Vec3> points, List<ControlPointRole> roles, PolyhedronSizeMode mode) {
      Polyhedron {
         points = List.copyOf(points);
         mode = mode == null ? PolyhedronSizeMode.RADIUS : mode;
         java.util.ArrayList<ControlPointRole> normalizedRoles = new java.util.ArrayList<>(roles);
         while (normalizedRoles.size() < points.size()) {
            int index = normalizedRoles.size();
            normalizedRoles.add(mode == PolyhedronSizeMode.RADIUS
               ? (index == 0 ? ControlPointRole.CENTER : ControlPointRole.RADIUS)
               : (index == 0 ? ControlPointRole.DIAMETER_A : ControlPointRole.DIAMETER_B));
         }
         roles = List.copyOf(normalizedRoles.subList(0, Math.min(normalizedRoles.size(), points.size())));
      }

      boolean hasFirstInput() {
         return this.mode == PolyhedronSizeMode.RADIUS
            ? this.point(ControlPointRole.CENTER).isPresent()
            : this.point(ControlPointRole.DIAMETER_A).isPresent();
      }

      Optional<Vec3> center() {
         if (this.mode == PolyhedronSizeMode.RADIUS) {
            return this.point(ControlPointRole.CENTER);
         }
         return this.point(ControlPointRole.DIAMETER_A).flatMap(first ->
            this.point(ControlPointRole.DIAMETER_B).map(second -> first.add(second).scale(0.5))
         );
      }

      Optional<Vec3> radiusPoint() {
         if (this.mode == PolyhedronSizeMode.RADIUS) {
            return this.center().flatMap(center -> this.point(ControlPointRole.RADIUS).map(point ->
               quantizedRadiusPoint(center, point)
            ));
         }
         return this.point(ControlPointRole.DIAMETER_A).flatMap(first ->
            this.point(ControlPointRole.DIAMETER_B).flatMap(second -> this.center().map(center ->
               quantizedRadiusPoint(center, second)
            ))
         );
      }

      boolean canGenerate() {
         return this.center().isPresent() && this.radiusPoint().isPresent();
      }

      double radius(double fallback) {
         return this.center()
            .flatMap(center -> this.radiusPoint().map(center::distanceTo))
            .orElse(fallback);
      }

      private Optional<Vec3> point(ControlPointRole role) {
         for (int index = 0; index < Math.min(this.points.size(), this.roles.size()); index++) {
            if (this.roles.get(index) == role) {
               return Optional.of(this.points.get(index));
            }
         }
         return Optional.empty();
      }

      private static Vec3 quantizedRadiusPoint(Vec3 center, Vec3 point) {
         Vec3 direction = point.subtract(center);
         double radius = Math.max(0.5, Math.round(direction.length() * 2.0) * 0.5);
         Vec3 normalized = direction.lengthSqr() < 1.0E-12 ? new Vec3(1.0, 0.0, 0.0) : direction.normalize();
         return center.add(normalized.scale(radius));
      }
   }

   record Cone(List<GeometryPoint> points, ConePlaneMode mode) {
      Cone {
         points = List.copyOf(points);
         mode = mode == null ? ConePlaneMode.RADIUS : mode;
      }

      ConePrismStage stage() {
         if (!this.faceComplete()) {
            return ConePrismStage.FACE;
         }
         return this.heightPoint().isPresent() ? ConePrismStage.ADJUST : ConePrismStage.BODY;
      }

      int facePointCount() {
         return this.mode.facePointCount();
      }

      int requiredPointCount() {
         return this.mode.requiredPoints();
      }

      int pointCount() {
         return this.points.size();
      }

      List<BlockPos> facePoints() {
         return this.points.stream()
            .filter(point -> point.role() != ControlPointRole.HEIGHT)
            .limit(this.facePointCount())
            .map(GeometryPoint::block)
            .toList();
      }

      List<Vec3> facePointLocations() {
         return this.points.stream()
            .filter(point -> point.role() != ControlPointRole.HEIGHT)
            .limit(this.facePointCount())
            .map(GeometryPoint::location)
            .toList();
      }

      boolean hasFacePoints() {
         return !this.facePoints().isEmpty();
      }

      boolean faceComplete() {
         return this.facePointLocations().size() >= this.facePointCount();
      }

      Optional<BlockPos> heightPoint() {
         return this.points.stream()
            .filter(point -> point.role() == ControlPointRole.HEIGHT)
            .map(GeometryPoint::block)
            .findFirst();
      }

      Optional<Vec3> heightPointLocation() {
         return this.points.stream()
            .filter(point -> point.role() == ControlPointRole.HEIGHT)
            .map(GeometryPoint::location)
            .findFirst();
      }

      boolean bodyComplete() {
         return this.faceComplete() && this.heightPoint().isPresent();
      }
   }
}
