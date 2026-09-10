package io.github.fastformer.fastplace.geometry.generation;

import io.github.fastformer.fastplace.FillMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class ConvexPolyhedronGenerator {
   private static final double EPSILON = 1.0E-9;

   private ConvexPolyhedronGenerator() {
   }

   public static Set<BlockPos> generate(PolyhedronParameters parameters, FillMode fillMode, int maxBlocks) {
      if (!parameters.ready()) {
         return controlPoints(parameters);
      }
      PolyhedronGeometry.Bounds bounds = PolyhedronGeometry.bounds(parameters, true);
      double radius = parameters.radius(1.0);
      List<Constraint> constraints = constraints(parameters.shapeVariant(), radius);
      Vec3 zDirection = PolyhedronGeometry.toLocal(new Vec3(0.0, 0.0, 1.0), parameters);
      boolean boundaryOnly = fillMode != FillMode.SOLID;
      long count = countBlocks(parameters, bounds, constraints, zDirection, boundaryOnly);
      if (count > maxBlocks) {
         return GenerationLimitExceeded.witness(maxBlocks);
      }
      return new LazyBlockSet(
         bounds.minX(), bounds.minY(), bounds.minZ(),
         bounds.maxX(), bounds.maxY(), bounds.maxZ(), (int)count,
         (x, y, z) -> {
            IntSpan span = zSpan(parameters, bounds, constraints, zDirection, x, y);
            return !span.empty() && z >= span.min() && z <= span.max()
               && (!boundaryOnly || isBoundary(
                  parameters, bounds, constraints, zDirection, new BlockPos(x, y, z)
               ));
         }
      );
   }

   public static long estimateScanCells(PolyhedronParameters parameters) {
      if (!parameters.ready()) {
         return parameters.points().size();
      }
      PolyhedronGeometry.Bounds bounds = PolyhedronGeometry.bounds(parameters, true);
      long width = (long)bounds.maxX() - bounds.minX() + 1L;
      long height = (long)bounds.maxY() - bounds.minY() + 1L;
      return GenerationMath.saturatedMultiply(width, height);
   }

   public static Set<BlockPos> previewOutline(PolyhedronParameters parameters, int maxBlocks) {
      if (!parameters.ready() || maxBlocks <= 0) {
         return controlPoints(parameters);
      }
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
      double radius = parameters.radius(1.0);
      int segments = Math.clamp((int)Math.ceil(Math.PI * 4.0 * radius * parameters.maxScale()), 24, 2048);
      for (int plane = 0; plane < 3 && result.size() < maxBlocks; plane++) {
         Vec3 previous = parameters.center().add(
            PolyhedronGeometry.toWorld(PolyhedronGeometry.ringPoint(plane, radius, 0.0), parameters)
         );
         for (int segment = 1; segment <= segments && result.size() < maxBlocks; segment++) {
            double angle = Math.PI * 2.0 * (double)segment / (double)segments;
            Vec3 next = parameters.center().add(
               PolyhedronGeometry.toWorld(PolyhedronGeometry.ringPoint(plane, radius, angle), parameters)
            );
            LineGenerator.add(result, previous, next, maxBlocks);
            previous = next;
         }
      }
      return Set.copyOf(result);
   }

   private static void addBoundary(
      Set<BlockPos> output,
      PolyhedronParameters parameters,
      PolyhedronGeometry.Bounds bounds,
      List<Constraint> constraints,
      Vec3 zDirection,
      int x,
      int y,
      IntSpan span,
      int maxBlocks
   ) {
      if (span.empty()) {
         return;
      }
      IntSpan left = zSpan(parameters, bounds, constraints, zDirection, x - 1, y);
      IntSpan right = zSpan(parameters, bounds, constraints, zDirection, x + 1, y);
      IntSpan down = zSpan(parameters, bounds, constraints, zDirection, x, y - 1);
      IntSpan up = zSpan(parameters, bounds, constraints, zDirection, x, y + 1);
      int interiorMin = Math.max(span.min() + 1, Math.max(Math.max(left.min(), right.min()), Math.max(down.min(), up.min())));
      int interiorMax = Math.min(span.max() - 1, Math.min(Math.min(left.max(), right.max()), Math.min(down.max(), up.max())));
      if (interiorMin > interiorMax) {
         addRange(output, x, y, span.min(), span.max(), maxBlocks);
         return;
      }
      addRange(output, x, y, span.min(), interiorMin - 1, maxBlocks);
      addRange(output, x, y, interiorMax + 1, span.max(), maxBlocks);
   }

   private static long countBlocks(
      PolyhedronParameters parameters,
      PolyhedronGeometry.Bounds bounds,
      List<Constraint> constraints,
      Vec3 zDirection,
      boolean boundaryOnly
   ) {
      long count = 0L;
      for (long x = bounds.minX(); x <= (long)bounds.maxX(); x++) {
         for (long y = bounds.minY(); y <= (long)bounds.maxY(); y++) {
            IntSpan span = zSpan(parameters, bounds, constraints, zDirection, (int)x, (int)y);
            if (span.empty()) {
               continue;
            }
            long column = boundaryOnly
               ? boundaryCount(parameters, bounds, constraints, zDirection, (int)x, (int)y, span)
               : span.length();
            count = count > Long.MAX_VALUE - column ? Long.MAX_VALUE : count + column;
         }
      }
      return count;
   }

   private static long boundaryCount(
      PolyhedronParameters parameters,
      PolyhedronGeometry.Bounds bounds,
      List<Constraint> constraints,
      Vec3 zDirection,
      int x,
      int y,
      IntSpan span
   ) {
      IntSpan left = zSpan(parameters, bounds, constraints, zDirection, x - 1, y);
      IntSpan right = zSpan(parameters, bounds, constraints, zDirection, x + 1, y);
      IntSpan down = zSpan(parameters, bounds, constraints, zDirection, x, y - 1);
      IntSpan up = zSpan(parameters, bounds, constraints, zDirection, x, y + 1);
      int interiorMin = Math.max(span.min() + 1, Math.max(Math.max(left.min(), right.min()), Math.max(down.min(), up.min())));
      int interiorMax = Math.min(span.max() - 1, Math.min(Math.min(left.max(), right.max()), Math.min(down.max(), up.max())));
      return interiorMin > interiorMax
         ? span.length()
         : (long)interiorMin - span.min() + (long)span.max() - interiorMax;
   }

   private static IntSpan zSpan(
      PolyhedronParameters parameters,
      PolyhedronGeometry.Bounds bounds,
      List<Constraint> constraints,
      Vec3 zDirection,
      int x,
      int y
   ) {
      if (x < bounds.minX() || x > bounds.maxX() || y < bounds.minY() || y > bounds.maxY()) {
         return IntSpan.EMPTY;
      }
      Vec3 center = parameters.center();
      Vec3 base = PolyhedronGeometry.toLocal(
         new Vec3(x + 0.5 - center.x, y + 0.5 - center.y, 0.0),
         parameters
      );
      double minimum = bounds.minZ() + 0.5 - center.z;
      double maximum = bounds.maxZ() + 0.5 - center.z;
      for (Constraint constraint : constraints) {
         double fixed = constraint.normal().dot(base);
         double coefficient = constraint.normal().dot(zDirection);
         double remaining = constraint.limit() - fixed;
         if (Math.abs(coefficient) <= EPSILON) {
            if (remaining < -EPSILON) {
               return IntSpan.EMPTY;
            }
         } else if (coefficient > 0.0) {
            maximum = Math.min(maximum, remaining / coefficient);
         } else {
            minimum = Math.max(minimum, remaining / coefficient);
         }
         if (minimum > maximum + EPSILON) {
            return IntSpan.EMPTY;
         }
      }
      int minZ = (int)Math.ceil(center.z + minimum - 0.5 - EPSILON);
      int maxZ = (int)Math.floor(center.z + maximum - 0.5 + EPSILON);
      return minZ > maxZ ? IntSpan.EMPTY : new IntSpan(minZ, maxZ);
   }

   private static List<Constraint> constraints(int shapeVariant, double radius) {
      return switch (Math.floorMod(shapeVariant, 4)) {
         case 0 -> List.of(
            new Constraint(new Vec3(1.0, 1.0, 1.0), radius),
            new Constraint(new Vec3(1.0, -1.0, -1.0), radius),
            new Constraint(new Vec3(-1.0, 1.0, -1.0), radius),
            new Constraint(new Vec3(-1.0, -1.0, 1.0), radius)
         );
         case 1 -> absoluteSumConstraints(1.0, 0.0, radius);
         case 2 -> absoluteSumConstraints(0.35, 1.0, radius * 1.35);
         default -> absoluteSumConstraints(1.0, 0.3, radius * 1.8);
      };
   }

   private static List<Constraint> absoluteSumConstraints(double sumWeight, double maximumWeight, double limit) {
      java.util.ArrayList<Constraint> result = new java.util.ArrayList<>();
      for (int sx : new int[]{-1, 1}) {
         for (int sy : new int[]{-1, 1}) {
            for (int sz : new int[]{-1, 1}) {
               Vec3 signs = new Vec3(sx, sy, sz);
               if (maximumWeight == 0.0) {
                  result.add(new Constraint(signs.scale(sumWeight), limit));
                  continue;
               }
               for (int axis = 0; axis < 3; axis++) {
                  for (int dominantSign : new int[]{-1, 1}) {
                     Vec3 dominant = switch (axis) {
                        case 0 -> new Vec3(dominantSign, 0.0, 0.0);
                        case 1 -> new Vec3(0.0, dominantSign, 0.0);
                        default -> new Vec3(0.0, 0.0, dominantSign);
                     };
                     result.add(new Constraint(signs.scale(sumWeight).add(dominant.scale(maximumWeight)), limit));
                  }
               }
            }
         }
      }
      return List.copyOf(result);
   }

   private static void addRange(Set<BlockPos> output, int x, int y, int minZ, int maxZ, int maxBlocks) {
      for (int z = minZ; z <= maxZ && output.size() < maxBlocks; z++) {
         output.add(new BlockPos(x, y, z));
      }
   }

   private static Set<BlockPos> controlPoints(PolyhedronParameters parameters) {
      return parameters.points().stream()
         .map(BlockPos::containing)
         .collect(java.util.stream.Collectors.toUnmodifiableSet());
   }

   private record Constraint(Vec3 normal, double limit) {
   }

   private record IntSpan(int min, int max) {
      private static final IntSpan EMPTY = new IntSpan(Integer.MAX_VALUE, Integer.MIN_VALUE);

      boolean empty() {
         return this.min > this.max;
      }

      long length() {
         return empty() ? 0L : (long)this.max - this.min + 1L;
      }
   }

   private static boolean isBoundary(
      PolyhedronParameters parameters,
      PolyhedronGeometry.Bounds bounds,
      List<Constraint> constraints,
      Vec3 zDirection,
      BlockPos position
   ) {
      int x = position.getX();
      int y = position.getY();
      int z = position.getZ();
      return !contains(zSpan(parameters, bounds, constraints, zDirection, x - 1, y), z)
         || !contains(zSpan(parameters, bounds, constraints, zDirection, x + 1, y), z)
         || !contains(zSpan(parameters, bounds, constraints, zDirection, x, y - 1), z)
         || !contains(zSpan(parameters, bounds, constraints, zDirection, x, y + 1), z)
         || !contains(zSpan(parameters, bounds, constraints, zDirection, x, y), z - 1)
         || !contains(zSpan(parameters, bounds, constraints, zDirection, x, y), z + 1);
   }

   private static boolean contains(IntSpan span, int value) {
      return !span.empty() && value >= span.min() && value <= span.max();
   }

}
