package io.github.fastformer.fastplace.geometry.generation;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import net.minecraft.core.BlockPos;
import io.github.fastformer.fastplace.geometry.generation.BoundaryInterpolatedFaceRasterizer.Pixel;

/** Fits one plane to fixed edge samples with the smallest required height corrections. */
final class NormalPlaneFaceRasterizer {
   private static final Pixel[] STEPS = {new Pixel(1, 0), new Pixel(-1, 0), new Pixel(0, 1), new Pixel(0, -1)};
   private NormalPlaneFaceRasterizer() {
   }

   static BresenhamFaceSweep.Attempt attempt(
      ProjectedBresenhamFace.Frame frame,
      Set<Pixel> columns,
      Set<BlockPos> boundary,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      Map<Pixel, Long> upperSeeds = new HashMap<>();
      Map<Pixel, Long> negatedLowerSeeds = new HashMap<>();
      for (BlockPos block : boundary) {
         observer.checkCancelled();
         Pixel column = new Pixel(local(frame, block, 0), local(frame, block, 1));
         long height = local(frame, block, 2);
         upperSeeds.merge(column, height, Math::min);
         negatedLowerSeeds.merge(column, -height, Math::min);
      }
      Map<Pixel, Long> upper = envelope(columns, upperSeeds, observer);
      Map<Pixel, Long> negatedLower = envelope(columns, negatedLowerSeeds, observer);
      double phase = planePhase(frame, boundary, observer);
      LinkedHashSet<BlockPos> blocks = new LinkedHashSet<>();
      for (Pixel column : columns) {
         observer.checkCancelled();
         observer.onScanned(1L);
         if (!upper.containsKey(column) || !negatedLower.containsKey(column)) {
            return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.NO_VALID_CANDIDATE);
         }
         long lowerBound = -negatedLower.get(column);
         long upperBound = upper.get(column);
         int target = planeHeight(frame, column, phase);
         // Compatible constraints select one height nearest the plane. Conflicting
         // edge samples keep their full interval instead of moving an authored edge.
         long minimum = Math.min(upperBound, Math.max(lowerBound, target));
         long maximum = Math.max(lowerBound, Math.min(upperBound, target));
         for (long height = minimum; height <= maximum; height++) {
            observer.checkCancelled();
            if (blocks.size() >= maxBlocks) {
               return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.LIMIT_EXCEEDED);
            }
            blocks.add(frame.restore(new ProjectedBresenhamFace.Int3(column.u(), column.v(), Math.toIntExact(height))));
         }
      }
      return BresenhamFaceSweep.Attempt.success(new BresenhamFaceSweep.Result(
         Collections.unmodifiableSet(blocks),
         Collections.unmodifiableSet(new LinkedHashSet<>(boundary))
      ));
   }

   private static double planePhase(
      ProjectedBresenhamFace.Frame frame, Set<BlockPos> boundary, BlockGenerationObserver observer
   ) {
      double bestPhase = 0.0;
      long bestError = Long.MAX_VALUE;
      for (double phase : new double[]{0.0, -1.0E-7, 1.0E-7}) {
         long error = 0L;
         for (BlockPos block : boundary) {
            observer.checkCancelled();
            observer.onScanned(1L);
            Pixel column = new Pixel(local(frame, block, 0), local(frame, block, 1));
            error += Math.abs((long)planeHeight(frame, column, phase) - local(frame, block, 2));
         }
         if (error < bestError) {
            bestError = error;
            bestPhase = phase;
         }
      }
      return bestPhase;
   }

   private static int planeHeight(ProjectedBresenhamFace.Frame frame, Pixel column, double phase) {
      double height = -(frame.normal().x * column.u() + frame.normal().y * column.v()) / frame.normal().z;
      return Math.toIntExact((long)Math.floor(height + 0.5 + phase));
   }

   /** The distance envelope bounds adjacent heights to a difference of at most one. */
   private static Map<Pixel, Long> envelope(Set<Pixel> domain, Map<Pixel, Long> seeds, BlockGenerationObserver observer) {
      Map<Pixel, Long> heights = new HashMap<>(seeds);
      PriorityQueue<Node> open = new PriorityQueue<>();
      seeds.forEach((pixel, height) -> open.add(new Node(pixel, height)));
      while (!open.isEmpty()) {
         observer.checkCancelled();
         observer.onScanned(1L);
         Node node = open.remove();
         if (heights.get(node.pixel()) != node.height()) continue;
         for (Pixel step : STEPS) {
            Pixel next = node.pixel().add(step);
            if (!domain.contains(next)) continue;
            long candidate = node.height() + 1L;
            if (candidate < heights.getOrDefault(next, Long.MAX_VALUE)) {
               heights.put(next, candidate);
               open.add(new Node(next, candidate));
            }
         }
      }
      return heights;
   }

   private static int local(ProjectedBresenhamFace.Frame frame, BlockPos block, int axis) {
      int worldAxis = frame.order()[axis];
      long delta = switch (worldAxis) {
         case 0 -> (long)block.getX() - frame.anchor().getX();
         case 1 -> (long)block.getY() - frame.anchor().getY();
         default -> (long)block.getZ() - frame.anchor().getZ();
      };
      return Math.toIntExact(delta * frame.signs()[axis]);
   }

   private record Node(Pixel pixel, long height) implements Comparable<Node> {
      @Override
      public int compareTo(Node other) {
         return Long.compare(this.height, other.height);
      }
   }
}
