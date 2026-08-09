package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.geometry.GuideLine;
import io.github.fastformer.fastplace.geometry.GuidePlane;
import io.github.fastformer.fastplace.geometry.GeometryConstraints;
import io.github.fastformer.fastplace.geometry.PlaneAxes;
import io.github.fastformer.fastplace.geometry.generation.LineGenerator;
import io.github.fastformer.fastplace.geometry.generation.LineTieBias;
import io.github.fastformer.fastplace.geometry.generation.BlockGenerationObserver;
import io.github.fastformer.fastplace.geometry.generation.PlanarFaceGeometry;
import io.github.fastformer.fastplace.geometry.generation.PolygonFaceGenerator;
import io.github.fastformer.fastplace.geometry.generation.PrismGenerator;
import io.github.fastformer.fastplace.geometry.generation.PyramidGenerator;
import io.github.fastformer.fastplace.geometry.generation.QuadFaceGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;

public final class FastPlaceGeometry {
   public static final int PREVIEW_MAX_BLOCKS = 100000;
   private static final double EPSILON = 1.0E-7;
   private static final double COORDINATE_PLANE_RADIUS = 128.0;
   private static final double FACE_RAY_LIMIT = 256.0;
   private static final double VOLUME_RAY_LIMIT = 128.0;
   private static final double VOLUME_GUIDE_AXIS_HALF_LENGTH = 256.0;

   private FastPlaceGeometry() {
   }

   public static BlockPos resolveCandidate(
      List<BlockPos> points,
      boolean polygonClosed,
      BlockPos hitBlock,
      BlockPos surfaceBlock,
      Vec3 faceBaseOffset,
      Vec3 volumeBaseOffset,
      BlockPos perpendicularAnchor,
      Vec3 eye,
      Vec3 view,
      BlockPos freeScrollOffset,
      FastPlaceGeometry.Modes modes
   ) {
      if (points.isEmpty()) {
         return (modes.raycastPlacement() == RaycastPlacement.EMBEDDED ? hitBlock : surfaceBlock).immutable();
      } else {
         Vec3 raw = Vec3.atCenterOf(modes.raycastPlacement() == RaycastPlacement.EMBEDDED ? hitBlock : surfaceBlock);
         if (stageFor(points) == FastPlaceStage.LINE && modes.lineMode() == LineMode.FREE_SCROLL) {
            BlockPos effectiveOffset = freeScrollOffset == null ? BlockPos.ZERO : freeScrollOffset;
            raw = Vec3.atCenterOf((Vec3i)points.getFirst()).add(effectiveOffset.getX(), effectiveOffset.getY(), effectiveOffset.getZ());
         }
         Vec3 resolved = switch (effectiveStage(points, modes.faceMode(), polygonClosed)) {
            case LINE -> resolveLine(points, raw, eye, view, modes);
            case FACE -> resolveFace(points, raw, faceBaseOffset, perpendicularAnchor, eye, view, modes);
            case VOLUME -> resolveVolume(points, raw, volumeBaseOffset, perpendicularAnchor, eye, view, modes);
            case POINT -> raw;
         };
         return BlockPos.containing(resolved);
      }
   }

   public static Vec3 anglePoint(Vec3 eye, Vec3 view, int distance) {
      return eye.add(normalize(view).scale((double)distance));
   }

   public static Set<BlockPos> blocks(List<BlockPos> points, FastPlaceGeometry.Modes modes) {
      return blocks(points, modes, false, PolygonVolumeShape.EXTRUDE, 100000);
   }

   public static Set<BlockPos> blocks(
      List<BlockPos> points,
      FastPlaceGeometry.Modes modes,
      boolean polygonHeightConfirmed,
      PolygonVolumeShape polygonVolumeShape,
      int maxBlocks
   ) {
      return blocks(points, modes, polygonHeightConfirmed, polygonVolumeShape, maxBlocks, BlockGenerationObserver.NONE);
   }

   public static Set<BlockPos> blocks(
      List<BlockPos> points,
      FastPlaceGeometry.Modes modes,
      boolean polygonHeightConfirmed,
      PolygonVolumeShape polygonVolumeShape,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      if (points.isEmpty()) {
         return Set.of();
      } else if (points.size() == 1) {
         return LineGenerator.generate(points.getFirst(), points.getFirst(), maxBlocks, observer);
      } else if (points.size() == 2) {
         return LineGenerator.generate(points.getFirst(), points.get(1), maxBlocks, observer);
      } else if (modes.faceMode() == FaceMode.POLYGON) {
         return polygonHeightConfirmed && points.size() >= 4
            ? polygonVolume(points, modes, polygonVolumeShape, maxBlocks, observer)
            : PolygonFaceGenerator.generateFromBlockPoints(points, modes.fillMode(), maxBlocks, observer);
      } else if (points.size() == 3) {
         return QuadFaceGenerator.generate(
            PlanarFaceGeometry.vertices(points, modes.faceMode()),
            modes.fillMode(),
            maxBlocks,
            observer,
            modes.faceTieBias(),
            modes.faceRasterizationMode()
         );
      } else {
         return prism(points, modes, maxBlocks, observer);
      }
   }

   private static Set<BlockPos> prism(
      List<BlockPos> points, FastPlaceGeometry.Modes modes, int maxBlocks, BlockGenerationObserver observer
   ) {
      List<Vec3> base = PlanarFaceGeometry.vertices(points, modes.faceMode());
      Vec3 anchor = Vec3.atCenterOf(points.get(2));
      Vec3 extrusion = Vec3.atCenterOf(points.get(3)).subtract(anchor);
      return PrismGenerator.generateQuad(
         base, extrusion, modes.fillMode(), maxBlocks, observer, modes.faceTieBias(), modes.faceRasterizationMode()
      );
   }

   private static Set<BlockPos> polygonVolume(
      List<BlockPos> points, FastPlaceGeometry.Modes modes, PolygonVolumeShape shape, int maxBlocks, BlockGenerationObserver observer
   ) {
      List<Vec3> base = points.subList(0, points.size() - 1).stream().map(Vec3::atCenterOf).toList();
      Vec3 anchor = Vec3.atCenterOf(points.get(Math.min(2, points.size() - 2)));
      Vec3 extrusion = Vec3.atCenterOf(points.getLast()).subtract(anchor);
      return shape == PolygonVolumeShape.APEX
         ? PyramidGenerator.generate(base, extrusion, modes.fillMode(), maxBlocks, observer)
         : PrismGenerator.generatePolygon(base, extrusion, modes.fillMode(), maxBlocks, observer);
   }

   public static List<GuidePlane> guidePlanes(
      List<BlockPos> points,
      boolean polygonClosed,
      BlockPos candidate,
      Set<BlockPos> structureBlocks,
      Vec3 faceBaseOffset,
      Vec3 volumeBaseOffset,
      BlockPos perpendicularAnchor,
      Vec3 eye,
      Vec3 view,
      FastPlaceGeometry.Modes modes
   ) {
      if (points.isEmpty()) {
         return List.of();
      } else {
         FastPlaceStage stage = effectiveStage(points, modes.faceMode(), polygonClosed);
         List<Vec3> bounds = structureFootprint(structureBlocks, points, candidate);
         if (stage == FastPlaceStage.FACE && modes.faceMode() == FaceMode.COORDINATE_PLANE && points.size() >= 2) {
            Vec3 a = Vec3.atCenterOf((Vec3i)points.getFirst());
            Vec3 normal = previewFaceNormal(points, candidate, modes.faceMode());
            return normal.lengthSqr() < 1.0E-7 ? List.of() : List.of(new GuidePlane(a, normal, faceStageBounds(points, candidate)));
         } else if (stage == FastPlaceStage.FACE && modes.faceMode() == FaceMode.POLYGON && points.size() >= 2) {
            Vec3 a = Vec3.atCenterOf((Vec3i)points.getFirst());
            Vec3 normal = candidate == null ? Vec3.ZERO : previewFaceNormal(points, candidate, modes.faceMode());
            return normal.lengthSqr() < 1.0E-7 ? List.of() : List.of(new GuidePlane(a, normal, faceStageBounds(points, candidate), true));
         } else if (stage == FastPlaceStage.FACE && modes.faceMode() == FaceMode.PARALLELOGRAM_BASE_PLANE && points.size() >= 2) {
            Vec3 a = Vec3.atCenterOf((Vec3i)points.getFirst());
            Vec3 b = Vec3.atCenterOf((Vec3i)points.get(1));
            FastPlaceGeometry.PerpendicularPlaneHit hit = basePlaneHit(a, b, faceBaseOffset(points, faceBaseOffset, view), eye, view);
            if (hit != null && hit.distance() > FACE_RAY_LIMIT) {
               hit = null;
            }
            return hit == null ? List.of() : List.of(new GuidePlane(hit.planePoint(), hit.normal(), faceStageBounds(points, candidate), true));
         } else if (stage == FastPlaceStage.VOLUME && points.size() >= 3) {
            List<Vec3> base = PlanarFaceGeometry.vertices(points, modes.faceMode());
            Vec3 normal = PlanarFaceGeometry.normal(base);
            Vec3 center = Vec3.atCenterOf((Vec3i)points.get(2));
            if (usesVolumeOffset(modes)) {
               center = center.add(volumeBaseOffset);
            }

            if (normal.lengthSqr() < 1.0E-7) {
               return List.of();
            }

            if (modes.volumeMode() == VolumeMode.PERPENDICULAR_TO_FACE) {
               Vec3 measurementAnchor = anchorPoint(points, perpendicularAnchor, 2);
               Vec3 extrusionAnchor = Vec3.atCenterOf((Vec3i)points.get(2));
               return List.of(new GuidePlane(measurementAnchor, normal, bounds));
            }

            return List.of(new GuidePlane(center, normal, bounds, true));
         } else {
            return List.of();
         }
      }
   }

   private static Vec3 previewFaceNormal(List<BlockPos> points, BlockPos candidate, FaceMode mode) {
      List<BlockPos> previewPoints = new ArrayList<>(points);
      previewPoints.add(candidate);
      return PlanarFaceGeometry.normal(PlanarFaceGeometry.vertices(previewPoints, mode));
   }

   private static List<Vec3> structureFootprint(Set<BlockPos> structureBlocks, List<BlockPos> points, BlockPos candidate) {
      if (structureBlocks.isEmpty()) {
         List<Vec3> fallback = new ArrayList<>(points.stream().map(Vec3::atCenterOf).toList());
         fallback.add(Vec3.atCenterOf(candidate));
         return fallback;
      } else {
         return structureBlocks.stream().<Vec3>map(Vec3::atCenterOf).toList();
      }
   }

   private static List<Vec3> faceStageBounds(List<BlockPos> points, BlockPos candidate) {
      ArrayList<Vec3> bounds = new ArrayList<>(points.stream().map(Vec3::atCenterOf).toList());
      if (candidate != null) {
         bounds.add(Vec3.atCenterOf(candidate));
      }
      return bounds;
   }

   public static List<GuideLine> guideLines(
      List<BlockPos> points, boolean polygonClosed, Vec3 faceBaseOffset, BlockPos perpendicularAnchor, Vec3 eye, Vec3 view, FastPlaceGeometry.Modes modes
   ) {
      FastPlaceStage stage = effectiveStage(points, modes.faceMode(), polygonClosed);
      if (stage == FastPlaceStage.VOLUME && modes.volumeMode() == VolumeMode.PERPENDICULAR_TO_FACE && points.size() >= 3) {
         List<Vec3> base = PlanarFaceGeometry.vertices(points, modes.faceMode());
         Vec3 normal = PlanarFaceGeometry.normal(base);
         if (normal.lengthSqr() < 1.0E-7) {
            return List.of();
         } else {
            Vec3 measurementAnchor = anchorPoint(points, perpendicularAnchor, 2);
            Vec3 axis = normalize(normal);
            Vec3 direction = normalize(view);
            Vec3 axisFrom = measurementAnchor.subtract(axis.scale(VOLUME_GUIDE_AXIS_HALF_LENGTH));
            Vec3 axisTo = measurementAnchor.add(axis.scale(VOLUME_GUIDE_AXIS_HALF_LENGTH));
            if (direction.lengthSqr() < EPSILON) {
               return List.of(new GuideLine(axisFrom, axisTo));
            }
            FastPlaceGeometry.RayLineClosest closest = closestRayLine(eye, direction, measurementAnchor, axis);
            Vec3 rayPoint;
            Vec3 axisPoint;
            if (closest != null && closest.rayDistance() <= VOLUME_RAY_LIMIT) {
               rayPoint = closest.rayPoint();
               axisPoint = closest.linePoint();
            } else {
               double rayDistance = closest == null
                  ? Math.min(VOLUME_RAY_LIMIT, Math.max(0.0, measurementAnchor.subtract(eye).dot(direction)))
                  : VOLUME_RAY_LIMIT;
               rayPoint = eye.add(direction.scale(rayDistance));
               axisPoint = measurementAnchor.add(axis.scale(rayPoint.subtract(measurementAnchor).dot(axis)));
            }
            return rayPoint.distanceToSqr(axisPoint) < EPSILON
               ? List.of(new GuideLine(axisFrom, axisTo))
               : List.of(new GuideLine(axisFrom, axisTo), new GuideLine(rayPoint, axisPoint));
         }
      } else {
         return List.of();
      }
   }

   public static FastPlaceStage stageFor(List<BlockPos> points) {
      return switch (points.size()) {
         case 0 -> FastPlaceStage.POINT;
         case 1 -> FastPlaceStage.LINE;
         case 2 -> FastPlaceStage.FACE;
         default -> FastPlaceStage.VOLUME;
      };
   }

   public static FastPlaceStage effectiveStage(List<BlockPos> points, FaceMode faceMode, boolean polygonClosed) {
      if (faceMode == FaceMode.POLYGON && points.size() >= 2 && !polygonClosed) {
         return FastPlaceStage.FACE;
      }
      return stageFor(points);
   }

   private static Vec3 resolveLine(List<BlockPos> points, Vec3 raw, Vec3 eye, Vec3 view, FastPlaceGeometry.Modes modes) {
      if (modes.lineMode() == LineMode.RAYCAST || modes.lineMode() == LineMode.FREE_SCROLL) {
         return raw;
      } else if (modes.modifierHeld()) {
         Vec3 a = Vec3.atCenterOf((Vec3i)points.getFirst());
         return selectLineCoordinatePlane(a, eye, view, raw).point();
      } else {
         Vec3 a = Vec3.atCenterOf((Vec3i)points.getFirst());
         return nearestAxisPointToRay(eye, normalize(view), a, raw);
      }
   }

   private static Vec3 resolveFace(
      List<BlockPos> points, Vec3 raw, Vec3 faceBaseOffset, BlockPos perpendicularAnchor, Vec3 eye, Vec3 view, FastPlaceGeometry.Modes modes
   ) {
      FaceMode mode = modes.faceMode();
      if (mode == FaceMode.POLYGON) {
         return resolvePolygonFace(points, raw, eye, view);
      } else {
         Vec3 a = Vec3.atCenterOf((Vec3i)points.getFirst());
         Vec3 b = Vec3.atCenterOf((Vec3i)points.get(1));
         Vec3 ab = b.subtract(a);
         if (ab.lengthSqr() < 1.0E-7) {
            return raw;
         } else if (mode == FaceMode.COORDINATE_PLANE) {
            FastPlaceGeometry.PlaneHit plane = selectFaceCoordinatePlane(a, ab, eye, view, raw);
            Vec3 inPlane = plane.point();
            return a.add(inPlane.subtract(a).subtract(ab.scale(inPlane.subtract(a).dot(ab) / ab.lengthSqr())));
         } else {
            return resolveClampParallelogram(a, b, eye, view, raw).add(faceBaseOffset(points, faceBaseOffset, view));
         }
      }
   }

   private static Vec3 resolvePolygonFace(List<BlockPos> points, Vec3 raw, Vec3 eye, Vec3 view) {
      if (points.size() < 3) {
         return raw;
      }
      Vec3 origin = Vec3.atCenterOf(points.getFirst());
      Vec3 normal = PlanarFaceGeometry.normal(
         points.subList(0, 3).stream().map(Vec3::atCenterOf).toList()
      );
      if (normal.lengthSqr() < EPSILON) {
         return raw;
      }
      Vec3 rayHit = intersectRayPlane(eye, normalize(view), origin, normal);
      if (rayHit != null && rayHit.distanceToSqr(eye) <= FACE_RAY_LIMIT * FACE_RAY_LIMIT) {
         return rayHit;
      }
      return raw.subtract(normal.scale(raw.subtract(origin).dot(normal)));
   }

   private static Vec3 resolveVolume(
      List<BlockPos> points, Vec3 raw, Vec3 volumeBaseOffset, BlockPos perpendicularAnchor, Vec3 eye, Vec3 view, FastPlaceGeometry.Modes modes
   ) {
      List<Vec3> base = PlanarFaceGeometry.vertices(points, modes.faceMode());
      Vec3 normal = PlanarFaceGeometry.normal(base);
      if (normal.lengthSqr() < 1.0E-7) {
         Vec3 a = Vec3.atCenterOf((Vec3i)points.getFirst());
         Vec3 b = Vec3.atCenterOf((Vec3i)points.get(1));
         Vec3 extrusionAnchor = Vec3.atCenterOf((Vec3i)points.get(2));
         if (a.distanceToSqr(b) < 1.0E-7 && points.size() > 2) {
            b = extrusionAnchor;
         }

         return a.distanceToSqr(b) < 1.0E-7 ? raw : extrusionAnchor.add(perpendicularPointFromLine(a, b, eye, view, raw).subtract(a));
      } else {
         Vec3 measurementAnchor = anchorPoint(points, perpendicularAnchor, 2);
         Vec3 extrusionAnchor = Vec3.atCenterOf((Vec3i)points.get(2));
         if (usesVolumeOffset(modes)) {
            return extrusionAnchor.add(volumeBaseOffset);
         } else {
            return perpendicularVolumePoint(measurementAnchor, extrusionAnchor, normal, eye, view);
         }
      }
   }

   private static Vec3 perpendicularVolumePoint(
      Vec3 measurementAnchor, Vec3 extrusionAnchor, Vec3 normal, Vec3 eye, Vec3 view
   ) {
      Vec3 direction = normalize(view);
      if (direction.lengthSqr() < EPSILON) {
         return extrusionAnchor;
      }
      double height = GeometryConstraints.rayAxisOffset(
         eye,
         direction,
         measurementAnchor,
         normal,
         VOLUME_RAY_LIMIT,
         VOLUME_RAY_LIMIT
      );
      return extrusionAnchor.add(normal.scale(height));
   }

   private static Vec3 anchorPoint(List<BlockPos> points, BlockPos anchor, int fallbackIndex) {
      return Vec3.atCenterOf((Vec3i)(anchor != null ? anchor : (Vec3i)points.get(Math.min(fallbackIndex, points.size() - 1))));
   }

   public static boolean usesVolumeOffset(FastPlaceGeometry.Modes modes) {
      return modes.volumeMode() == VolumeMode.FREE;
   }

   private static Vec3 closestPointOnLine(Vec3 point, Vec3 a, Vec3 b) {
      Vec3 ab = b.subtract(a);
      return ab.lengthSqr() < 1.0E-7 ? a : a.add(ab.scale(point.subtract(a).dot(ab) / ab.lengthSqr()));
   }

   private static Vec3 intersectRayPlane(Vec3 origin, Vec3 direction, Vec3 point, Vec3 normal) {
      double denominator = direction.dot(normal);
      if (Math.abs(denominator) < 1.0E-7) {
         return null;
      } else {
         double distance = point.subtract(origin).dot(normal) / denominator;
         return distance < 0.0 ? null : origin.add(direction.scale(distance));
      }
   }

   private static Vec3 closestPointOnSecondLine(Vec3 firstPoint, Vec3 firstDirection, Vec3 secondPoint, Vec3 secondDirection) {
      Vec3 w0 = firstPoint.subtract(secondPoint);
      double a = firstDirection.dot(firstDirection);
      double b = firstDirection.dot(secondDirection);
      double c = secondDirection.dot(secondDirection);
      double d = firstDirection.dot(w0);
      double e = secondDirection.dot(w0);
      double denominator = a * c - b * b;
      if (Math.abs(denominator) < 1.0E-7) {
         return null;
      } else {
         double secondDistance = (a * e - b * d) / denominator;
         return secondPoint.add(secondDirection.scale(secondDistance));
      }
   }

   private static Vec3 resolveClampParallelogram(Vec3 a, Vec3 b, Vec3 eye, Vec3 view, Vec3 fallback) {
      Vec3 direction = normalize(view);
      FastPlaceGeometry.PerpendicularPlaneHit hit = basePlaneHit(a, b, eye, direction);
      Vec3 rayPoint = hit == null || hit.distance() > FACE_RAY_LIMIT ? null : hit.point();
      if (rayPoint == null) {
         Vec3 closest = perpendicularPointFromLine(a, b, eye, view, fallback);
         Vec3 linePoint = closestPointOnLine(closest, a, b);
         rayPoint = b.add(closest.subtract(linePoint));
      }

      Vec3 offset = rayPoint.subtract(closestPointOnLine(rayPoint, a, b));
      return b.add(offset);
   }

   private static FastPlaceGeometry.PlaneHit selectLocalLineCoordinatePlane(Vec3 a, Vec3 b, Vec3 eye, Vec3 view, Vec3 fallback) {
      Vec3 localZ = normalize(b.subtract(a));
      Vec3 localX = normalize(localZ.cross(new Vec3(0.0, 1.0, 0.0)));
      if (localX.lengthSqr() < 1.0E-7) {
         localX = new Vec3(1.0, 0.0, 0.0);
      }
      Vec3 localY = normalize(localX.cross(localZ));
      return selectCoordinatePlane(a, eye, view, fallback, List.of(localX, localY, localZ));
   }

   private static Vec3 perpendicularPointFromLine(Vec3 a, Vec3 b, Vec3 eye, Vec3 view, Vec3 fallback) {
      Vec3 localZ = normalize(b.subtract(a));
      Vec3 rayDirection = normalize(view);
      FastPlaceGeometry.RayLineClosest closest = closestRayLine(eye, rayDirection, a, localZ);
      Vec3 rayPoint = closest == null || closest.rayDistance() > FACE_RAY_LIMIT ? fallback : closest.rayPoint();
      Vec3 linePoint = closestPointOnLine(rayPoint, a, b);
      Vec3 offset = rayPoint.subtract(linePoint);
      if (offset.lengthSqr() < 1.0E-7) {
         Vec3 fallbackOffset = fallback.subtract(closestPointOnLine(fallback, a, b));
         offset = fallbackOffset.subtract(localZ.scale(fallbackOffset.dot(localZ)));
      }
      return a.add(offset);
   }

   private static FastPlaceGeometry.RayLineClosest closestRayLine(Vec3 rayOrigin, Vec3 rayDirection, Vec3 lineOrigin, Vec3 lineDirection) {
      Vec3 w0 = rayOrigin.subtract(lineOrigin);
      double a = rayDirection.dot(rayDirection);
      double b = rayDirection.dot(lineDirection);
      double c = lineDirection.dot(lineDirection);
      double d = rayDirection.dot(w0);
      double e = lineDirection.dot(w0);
      double denominator = a * c - b * b;
      if (Math.abs(denominator) < 1.0E-7) {
         return null;
      }
      double rayDistance = (b * e - c * d) / denominator;
      if (rayDistance < 0.0) {
         rayDistance = 0.0;
      }
      double lineDistance = (a * e - b * d) / denominator;
      Vec3 rayPoint = rayOrigin.add(rayDirection.scale(rayDistance));
      Vec3 linePoint = lineOrigin.add(lineDirection.scale(lineDistance));
      return new FastPlaceGeometry.RayLineClosest(rayPoint, linePoint, rayDistance);
   }

   private static FastPlaceGeometry.PerpendicularPlaneHit basePlaneHit(Vec3 a, Vec3 b, Vec3 eye, Vec3 view) {
      return basePlaneHit(a, b, Vec3.ZERO, eye, view);
   }

   private static FastPlaceGeometry.PerpendicularPlaneHit basePlaneHit(Vec3 a, Vec3 b, Vec3 offset, Vec3 eye, Vec3 view) {
      Vec3 ab = b.subtract(a);
      double length = ab.length();
      if (length < 1.0E-7) {
         return null;
      }

      Vec3 lineDirection = ab.scale(1.0 / length);
      Vec3 rayDirection = normalize(view);
      Vec3 planePoint = b.add(offset);
      Vec3 hit = intersectRayPlane(eye, rayDirection, planePoint, lineDirection);
      return hit == null
         ? null
         : new FastPlaceGeometry.PerpendicularPlaneHit(hit, planePoint, lineDirection, hit.subtract(eye).dot(rayDirection));
   }

   private static Vec3 dominantAxis(Vec3 vector) {
      double x = Math.abs(vector.x);
      double y = Math.abs(vector.y);
      double z = Math.abs(vector.z);
      if (x >= y && x >= z) {
         return new Vec3(1.0, 0.0, 0.0);
      } else {
         return y >= z ? new Vec3(0.0, 1.0, 0.0) : new Vec3(0.0, 0.0, 1.0);
      }
   }

   public static Vec3 volumeBaseAxis(List<BlockPos> points, FastPlaceGeometry.Modes modes, Vec3 view) {
      Vec3 normal = PlanarFaceGeometry.normal(PlanarFaceGeometry.vertices(points, modes.faceMode()));
      return modes.volumeMode() == VolumeMode.FREE ? freeVolumeAxis(normal, view) : projectedVolumeAxis(normal, view);
   }

   public static Vec3 faceBaseAxis(List<BlockPos> points, Vec3 view) {
      if (points.size() < 2) {
         return Vec3.ZERO;
      } else {
         Vec3 axis = normalize(Vec3.atCenterOf((Vec3i)points.get(1)).subtract(Vec3.atCenterOf((Vec3i)points.getFirst())));
         return axis.dot(normalize(view)) < 0.0 ? axis.scale(-1.0) : axis;
      }
   }

   public static Vec3 faceBaseOffset(List<BlockPos> points, Vec3 offset, Vec3 view) {
      Vec3 axis = faceBaseAxis(points, view);
      return axis.scale(offset.dot(axis));
   }

   public static double faceBaseOffsetValue(List<BlockPos> points, Vec3 offset, Vec3 view) {
      return offset.dot(faceBaseAxis(points, view));
   }

   private static Vec3 viewAxis(Vec3 view) {
      Direction direction = Direction.getNearest(view.x, view.y, view.z);
      return new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ());
   }

   private static Vec3 projectedVolumeAxis(Vec3 normal, Vec3 view) {
      Vec3 localNormal = normalize(normal);
      Vec3 direction = normalize(view);
      double normalDot = direction.dot(localNormal);
      Vec3 planeProjection = direction.subtract(localNormal.scale(normalDot));
      if (planeProjection.lengthSqr() > normalDot * normalDot) {
         return normalize(planeProjection);
      }

      return normalDot < 0.0 ? localNormal.scale(-1.0) : localNormal;
   }

   private static Vec3 freeVolumeAxis(Vec3 normal, Vec3 view) {
      PlaneAxes axes = PlaneAxes.fromNormal(normal);
      Vec3 direction = normalize(view);
      Vec3 selected = axes.normal();
      double selectedDot = direction.dot(axes.normal());
      double bestAlignment = Math.abs(selectedDot);
      double xDot = direction.dot(axes.horizontal());
      if (Math.abs(xDot) > bestAlignment) {
         selected = axes.horizontal();
         selectedDot = xDot;
         bestAlignment = Math.abs(xDot);
      }

      double zDot = direction.dot(axes.vertical());
      if (Math.abs(zDot) > bestAlignment) {
         selected = axes.vertical();
         selectedDot = zDot;
      }

      return selectedDot < 0.0 ? selected.scale(-1.0) : selected;
   }

   private static Vec3 nearestAxisPointToRay(Vec3 eye, Vec3 view, Vec3 origin, Vec3 fallbackPoint) {
      List<Vec3> axes = List.of(new Vec3(1.0, 0.0, 0.0), new Vec3(0.0, 1.0, 0.0), new Vec3(0.0, 0.0, 1.0));
      FastPlaceGeometry.AxisRayCandidate best = null;

      for (Vec3 axis : axes) {
         FastPlaceGeometry.AxisRayCandidate candidate = closestAxisPointToRay(eye, view, origin, axis, fallbackPoint);
         if (best == null || candidate.distanceSqr() < best.distanceSqr()) {
            best = candidate;
         }
      }

      return best == null ? origin : best.axisPoint();
   }

   private static FastPlaceGeometry.AxisRayCandidate closestAxisPointToRay(Vec3 eye, Vec3 view, Vec3 axisOrigin, Vec3 axis, Vec3 fallbackPoint) {
      Vec3 eyeFromAxis = eye.subtract(axisOrigin);
      double parallel = view.dot(axis);
      double denominator = 1.0 - parallel * parallel;
      if (denominator < 1.0E-7) {
         Vec3 axisPoint = axisOrigin.add(axis.scale(fallbackPoint.subtract(axisOrigin).dot(axis)));
         return new FastPlaceGeometry.AxisRayCandidate(axisPoint, eye.subtract(axisPoint).cross(axis).lengthSqr());
      } else {
         double viewDistance = (parallel * axis.dot(eyeFromAxis) - view.dot(eyeFromAxis)) / denominator;
         if (viewDistance < 0.0) {
            viewDistance = 0.0;
         }

         Vec3 rayPoint = eye.add(view.scale(viewDistance));
         Vec3 axisPoint = axisOrigin.add(axis.scale(rayPoint.subtract(axisOrigin).dot(axis)));
         return new FastPlaceGeometry.AxisRayCandidate(axisPoint, rayPoint.distanceToSqr(axisPoint));
      }
   }

   private static FastPlaceGeometry.PlaneHit selectFaceCoordinatePlane(Vec3 anchor, Vec3 requiredLine, Vec3 eye, Vec3 view, Vec3 target) {
      List<Vec3> validNormals = List.of(new Vec3(1.0, 0.0, 0.0), new Vec3(0.0, 1.0, 0.0), new Vec3(0.0, 0.0, 1.0))
         .stream()
         .filter(candidate -> Math.abs(requiredLine.dot(candidate)) <= EPSILON)
         .toList();
      return selectCoordinatePlane(anchor, eye, view, target, validNormals);
   }

   private static FastPlaceGeometry.PlaneHit selectLineCoordinatePlane(Vec3 anchor, Vec3 eye, Vec3 view, Vec3 fallback) {
      return selectCoordinatePlane(
         anchor, eye, view, fallback, List.of(new Vec3(1.0, 0.0, 0.0), new Vec3(0.0, 1.0, 0.0), new Vec3(0.0, 0.0, 1.0))
      );
   }

   private static FastPlaceGeometry.PlaneHit selectCoordinatePlane(Vec3 anchor, Vec3 eye, Vec3 view, Vec3 fallback, List<Vec3> normals) {
      Vec3 direction = normalize(view);
      FastPlaceGeometry.PlaneHit selected = null;
      double bestAlignment = Double.NEGATIVE_INFINITY;

      for (Vec3 normal : normals) {
         Vec3 intersection = intersectRayPlane(eye, direction, anchor, normal);
         if (intersection != null) {
            double alignment = Math.abs(direction.dot(normal));
            if (alignment > bestAlignment) {
               bestAlignment = alignment;
               selected = new FastPlaceGeometry.PlaneHit(normal, intersection);
            }
         }
      }

      if (selected != null) {
         return selected;
      }

      Vec3 normal = normals.stream()
         .max((left, right) -> Double.compare(Math.abs(direction.dot(left)), Math.abs(direction.dot(right))))
         .orElseGet(() -> dominantAxis(direction));
      return new FastPlaceGeometry.PlaneHit(normal, fallback.subtract(normal.scale(fallback.subtract(anchor).dot(normal))));
   }

   private static Vec3 normalize(Vec3 vector) {
      double length = vector.length();
      return length < 1.0E-7 ? Vec3.ZERO : vector.scale(1.0 / length);
   }

   private static record AxisRayCandidate(Vec3 axisPoint, double distanceSqr) {
   }

   private static record RayLineClosest(Vec3 rayPoint, Vec3 linePoint, double rayDistance) {
   }

   private static record PerpendicularPlaneHit(Vec3 point, Vec3 planePoint, Vec3 normal, double distance) {
   }

   public static record Modes(
      PointMode pointMode,
      RaycastPlacement raycastPlacement,
      LineMode lineMode,
      FaceMode faceMode,
      VolumeMode volumeMode,
      FillMode fillMode,
      double angleDegrees,
      boolean modifierHeld,
      LineTieBias faceTieBias,
      FaceRasterizationMode faceRasterizationMode
   ) {
      public Modes(
         PointMode pointMode,
         RaycastPlacement raycastPlacement,
         LineMode lineMode,
         FaceMode faceMode,
         VolumeMode volumeMode,
         FillMode fillMode,
         double angleDegrees,
         boolean modifierHeld
      ) {
         this(
            pointMode, raycastPlacement, lineMode, faceMode, volumeMode, fillMode, angleDegrees, modifierHeld,
            LineTieBias.DEFAULT, FaceRasterizationMode.POINT_SWEEP
         );
      }

      public Modes(
         PointMode pointMode,
         RaycastPlacement raycastPlacement,
         LineMode lineMode,
         FaceMode faceMode,
         VolumeMode volumeMode,
         FillMode fillMode,
         double angleDegrees,
         boolean modifierHeld,
         LineTieBias faceTieBias
      ) {
         this(
            pointMode, raycastPlacement, lineMode, faceMode, volumeMode, fillMode, angleDegrees, modifierHeld,
            faceTieBias, FaceRasterizationMode.POINT_SWEEP
         );
      }

      public Modes {
         faceTieBias = faceTieBias == null ? LineTieBias.DEFAULT : faceTieBias;
         faceRasterizationMode = faceRasterizationMode == null
            ? FaceRasterizationMode.POINT_SWEEP
            : faceRasterizationMode;
      }

      public FastPlaceGeometry.Modes withRaycastPlacement(RaycastPlacement placement) {
         return new FastPlaceGeometry.Modes(this.pointMode, placement, this.lineMode, this.faceMode, this.volumeMode, this.fillMode, this.angleDegrees, this.modifierHeld, this.faceTieBias, this.faceRasterizationMode);
      }

      public FastPlaceGeometry.Modes withModifierHeld(boolean value) {
         return new FastPlaceGeometry.Modes(this.pointMode, this.raycastPlacement, this.lineMode, this.faceMode, this.volumeMode, this.fillMode, this.angleDegrees, value, this.faceTieBias, this.faceRasterizationMode);
      }

      public FastPlaceGeometry.Modes withVolumeMode(VolumeMode value) {
         return new FastPlaceGeometry.Modes(this.pointMode, this.raycastPlacement, this.lineMode, this.faceMode, value, this.fillMode, this.angleDegrees, this.modifierHeld, this.faceTieBias, this.faceRasterizationMode);
      }

      public FastPlaceGeometry.Modes withFillMode(FillMode value) {
         return new FastPlaceGeometry.Modes(this.pointMode, this.raycastPlacement, this.lineMode, this.faceMode, this.volumeMode, value, this.angleDegrees, this.modifierHeld, this.faceTieBias, this.faceRasterizationMode);
      }

      public FastPlaceGeometry.Modes withFaceTieBias(LineTieBias value) {
         return new FastPlaceGeometry.Modes(this.pointMode, this.raycastPlacement, this.lineMode, this.faceMode, this.volumeMode, this.fillMode, this.angleDegrees, this.modifierHeld, value, this.faceRasterizationMode);
      }

      public FastPlaceGeometry.Modes withFaceRasterizationMode(FaceRasterizationMode value) {
         return new FastPlaceGeometry.Modes(
            this.pointMode, this.raycastPlacement, this.lineMode, this.faceMode, this.volumeMode, this.fillMode,
            this.angleDegrees, this.modifierHeld, this.faceTieBias, value
         );
      }
   }

   private static record PlaneHit(Vec3 normal, Vec3 point) {
   }
}


