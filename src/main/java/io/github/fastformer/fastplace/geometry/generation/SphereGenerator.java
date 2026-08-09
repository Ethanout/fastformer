package io.github.fastformer.fastplace.geometry.generation;

import io.github.fastformer.fastplace.FillMode;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public final class SphereGenerator {
   private static final double EPSILON = 1.0E-9;

   private SphereGenerator() {
   }

   public static Set<BlockPos> generate(PolyhedronParameters parameters, FillMode fillMode, int maxBlocks) {
      if (!parameters.ready()) {
         return controlPoints(parameters);
      }
      PolyhedronGeometry.Bounds bounds = PolyhedronGeometry.bounds(parameters, false);
      double radius = parameters.radius(1.0);
      Vec3 zDirection = PolyhedronGeometry.toLocal(new Vec3(0.0, 0.0, 1.0), parameters);
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
      for (int x = bounds.minX(); x <= bounds.maxX() && result.size() < maxBlocks; x++) {
         for (int y = bounds.minY(); y <= bounds.maxY() && result.size() < maxBlocks; y++) {
            IntSpan span = zSpan(parameters, radius, zDirection, x, y);
            if (fillMode == FillMode.SOLID) {
               addRange(result, x, y, span.min(), span.max(), maxBlocks);
            } else {
               addBoundary(result, parameters, radius, zDirection, x, y, span, maxBlocks);
            }
         }
      }
      return Set.copyOf(result);
   }

   public static long estimateScanCells(PolyhedronParameters parameters) {
      if (!parameters.ready()) {
         return parameters.points().size();
      }
      PolyhedronGeometry.Bounds bounds = PolyhedronGeometry.bounds(parameters, false);
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
      int approximateRingCost = Math.max(1, (int)Math.ceil(Math.PI * 2.0 * radius * parameters.maxScale() * 1.25));
      int ringCount = Math.min(3, Math.max(1, maxBlocks / approximateRingCost));
      for (int plane = 0; plane < ringCount && result.size() < maxBlocks; plane++) {
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
      double radius,
      Vec3 zDirection,
      int x,
      int y,
      IntSpan span,
      int maxBlocks
   ) {
      if (span.empty()) {
         return;
      }
      IntSpan left = zSpan(parameters, radius, zDirection, x - 1, y);
      IntSpan right = zSpan(parameters, radius, zDirection, x + 1, y);
      IntSpan down = zSpan(parameters, radius, zDirection, x, y - 1);
      IntSpan up = zSpan(parameters, radius, zDirection, x, y + 1);
      int interiorMin = Math.max(span.min() + 1, Math.max(Math.max(left.min(), right.min()), Math.max(down.min(), up.min())));
      int interiorMax = Math.min(span.max() - 1, Math.min(Math.min(left.max(), right.max()), Math.min(down.max(), up.max())));
      if (interiorMin > interiorMax) {
         addRange(output, x, y, span.min(), span.max(), maxBlocks);
         return;
      }
      addRange(output, x, y, span.min(), interiorMin - 1, maxBlocks);
      addRange(output, x, y, interiorMax + 1, span.max(), maxBlocks);
   }

   private static IntSpan zSpan(
      PolyhedronParameters parameters, double radius, Vec3 zDirection, int x, int y
   ) {
      Vec3 center = parameters.center();
      Vec3 base = PolyhedronGeometry.toLocal(
         new Vec3(x + 0.5 - center.x, y + 0.5 - center.y, 0.0),
         parameters
      );
      double quadratic = zDirection.lengthSqr();
      double linear = 2.0 * base.dot(zDirection);
      double constant = base.lengthSqr() - radius * radius;
      double discriminant = linear * linear - 4.0 * quadratic * constant;
      if (quadratic < EPSILON || discriminant < -EPSILON) {
         return IntSpan.EMPTY;
      }
      double root = Math.sqrt(Math.max(0.0, discriminant));
      double first = (-linear - root) / (2.0 * quadratic);
      double second = (-linear + root) / (2.0 * quadratic);
      int min = (int)Math.ceil(center.z + Math.min(first, second) - 0.5 - EPSILON);
      int max = (int)Math.floor(center.z + Math.max(first, second) - 0.5 + EPSILON);
      return min > max ? IntSpan.EMPTY : new IntSpan(min, max);
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

   private record IntSpan(int min, int max) {
      private static final IntSpan EMPTY = new IntSpan(Integer.MAX_VALUE, Integer.MIN_VALUE);

      boolean empty() {
         return this.min > this.max;
      }
   }
}
