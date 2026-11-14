package io.github.fastformer.fastplace.geometry.generation;

import static io.github.fastformer.common.math.BlockQuads.isParallelogram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;

/** Rasterizes an authored quad using fixed edges and the normal-plane height field. */
final class QuadFaceRasterizer {
   private QuadFaceRasterizer() {
   }

   static BresenhamFaceSweep.Attempt attempt(
      ProjectedBresenhamFace.Frame frame,
      LineTieBias tieBias,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      if (frame == null || frame.vertices().size() != 4 || maxBlocks <= 0) {
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.NOT_APPLICABLE);
      }
      BlockGenerationObserver effectiveObserver = observer == null ? BlockGenerationObserver.NONE : observer;
      List<BlockPos> corners = frame.vertices().stream().map(frame::restore).toList();
      if (!isParallelogram(corners)) {
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.NOT_APPLICABLE);
      }
      for (int index : new int[]{1, 3}) {
         if (LineGenerator.estimateBlocks(corners.get(0), corners.get(index)) > maxBlocks) {
            return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.LIMIT_EXCEEDED);
         }
      }

      List<BlockPos> firstEdge = orientedPath(corners.get(0), corners.get(1), tieBias);
      List<BlockPos> secondEdge = orientedPath(corners.get(0), corners.get(3), tieBias);
      if (!hasRepresentableSymmetry(frame, corners)) {
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.NOT_APPLICABLE);
      }
      LinkedHashSet<BlockPos> boundarySamples = new LinkedHashSet<>();
      boundarySamples.addAll(firstEdge);
      boundarySamples.addAll(secondEdge);
      translateInto(boundarySamples, firstEdge, delta(corners.get(0), corners.get(3)));
      translateInto(boundarySamples, secondEdge, delta(corners.get(0), corners.get(1)));

      LinkedHashSet<Pixel> domain = new LinkedHashSet<>();
      for (BlockPos block : boundarySamples) {
         effectiveObserver.checkCancelled();
         domain.add(project(frame, block));
      }
      domain = fillScanRows(frame, domain, maxBlocks, effectiveObserver);
      if (domain.isEmpty()) {
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.NO_VALID_CANDIDATE);
      }
      if (domain.size() > maxBlocks) {
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.LIMIT_EXCEEDED);
      }

      return NormalPlaneFaceRasterizer.attempt(frame, domain, boundarySamples, maxBlocks, effectiveObserver);
   }

   private static LinkedHashSet<Pixel> fillScanRows(
      ProjectedBresenhamFace.Frame frame,
      Set<Pixel> source,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      TreeMap<Integer, int[]> rows = new TreeMap<>();
      for (Pixel pixel : source) {
         rows.compute(pixel.v(), (ignored, span) -> {
            if (span == null) {
               return new int[]{pixel.u(), pixel.u()};
            }
            span[0] = Math.min(span[0], pixel.u());
            span[1] = Math.max(span[1], pixel.u());
            return span;
         });
      }
      connectDiagonalRows(frame, rows);
      LinkedHashSet<Pixel> result = new LinkedHashSet<>();
      for (Map.Entry<Integer, int[]> entry : rows.entrySet()) {
         int[] span = entry.getValue();
         for (long u = span[0]; u <= span[1]; u++) {
            observer.checkCancelled();
            result.add(new Pixel((int)u, entry.getKey()));
            if (result.size() > maxBlocks) {
               return result;
            }
         }
      }
      return result;
   }

   private static void connectDiagonalRows(
      ProjectedBresenhamFace.Frame frame,
      NavigableMap<Integer, int[]> rows
   ) {
      Map.Entry<Integer, int[]> previous = null;
      for (Map.Entry<Integer, int[]> current : rows.entrySet()) {
         if (previous != null && (long)previous.getKey() + 1L == current.getKey()) {
            int[] first = previous.getValue();
            int[] second = current.getValue();
            if ((long)first[1] + 1L == second[0]) {
               bridgeDiagonal(frame, previous.getKey(), first, current.getKey(), second, first[1], second[0]);
            } else if ((long)second[1] + 1L == first[0]) {
               bridgeDiagonal(frame, previous.getKey(), first, current.getKey(), second, first[0], second[1]);
            }
         }
         previous = current;
      }
   }

   private static void bridgeDiagonal(
      ProjectedBresenhamFace.Frame frame,
      int firstV,
      int[] first,
      int secondV,
      int[] second,
      int firstU,
      int secondU
   ) {
      Pixel onFirstRow = new Pixel(secondU, firstV);
      Pixel onSecondRow = new Pixel(firstU, secondV);
      Pixel selected = distanceToProjectedCenter(frame, onFirstRow)
         <= distanceToProjectedCenter(frame, onSecondRow)
         ? onFirstRow
         : onSecondRow;
      int[] row = selected.v() == firstV ? first : second;
      row[0] = Math.min(row[0], selected.u());
      row[1] = Math.max(row[1], selected.u());
   }

   private static double distanceToProjectedCenter(ProjectedBresenhamFace.Frame frame, Pixel pixel) {
      long twiceU = 2L * pixel.u();
      long twiceV = 2L * pixel.v();
      long sumU = 0L;
      long sumV = 0L;
      for (ProjectedBresenhamFace.Int3 vertex : frame.vertices()) {
         sumU += vertex.x();
         sumV += vertex.y();
      }
      long deltaU = twiceU * frame.vertices().size() - 2L * sumU;
      long deltaV = twiceV * frame.vertices().size() - 2L * sumV;
      return (double)deltaU * deltaU + (double)deltaV * deltaV;
   }

   private static void translateInto(Set<BlockPos> output, List<BlockPos> source, BlockPos offset) {
      source.forEach(point -> output.add(point.offset(offset)));
   }

   private static List<BlockPos> orientedPath(BlockPos from, BlockPos to, LineTieBias tieBias) {
      ArrayList<BlockPos> result = new ArrayList<>(LineGenerator.path(from, to, tieBias));
      if (!result.isEmpty() && !result.getFirst().equals(from)) {
         Collections.reverse(result);
      }
      return List.copyOf(result);
   }

   private static BlockPos delta(BlockPos from, BlockPos to) {
      return new BlockPos(to.getX() - from.getX(), to.getY() - from.getY(), to.getZ() - from.getZ());
   }

   private static Pixel project(ProjectedBresenhamFace.Frame frame, BlockPos position) {
      long[] delta = {
         (long)position.getX() - frame.anchor().getX(),
         (long)position.getY() - frame.anchor().getY(),
         (long)position.getZ() - frame.anchor().getZ()
      };
      return new Pixel(
         safeInt(delta[frame.order()[0]] * frame.signs()[0]),
         safeInt(delta[frame.order()[1]] * frame.signs()[1])
      );
   }

   private static int localHeight(ProjectedBresenhamFace.Frame frame, BlockPos position) {
      long[] delta = {
         (long)position.getX() - frame.anchor().getX(),
         (long)position.getY() - frame.anchor().getY(),
         (long)position.getZ() - frame.anchor().getZ()
      };
      return safeInt(delta[frame.order()[2]] * frame.signs()[2]);
   }

   private static int safeInt(long value) {
      return Math.toIntExact(value);
   }

   record Pixel(int u, int v) {
      Pixel add(Pixel other) {
         return new Pixel(Math.addExact(this.u, other.u), Math.addExact(this.v, other.v));
      }
   }

   private static boolean hasRepresentableSymmetry(ProjectedBresenhamFace.Frame frame, List<BlockPos> corners) {
      Pixel first = project(frame, corners.get(0));
      Pixel opposite = project(frame, corners.get(2));
      Pixel second = project(frame, corners.get(1));
      Pixel otherOpposite = project(frame, corners.get(3));
      long sumU = (long)first.u() + opposite.u();
      long sumV = (long)first.v() + opposite.v();
      long sumHeight = (long)localHeight(frame, corners.get(0)) + localHeight(frame, corners.get(2));
      if (sumU != (long)second.u() + otherOpposite.u()
         || sumV != (long)second.v() + otherOpposite.v()
         || sumHeight != (long)localHeight(frame, corners.get(1)) + localHeight(frame, corners.get(3))) {
         return false;
      }
      // Symmetric coordinates must remain representable in the integer frame.
      Math.toIntExact(sumU);
      Math.toIntExact(sumV);
      Math.toIntExact(sumHeight);
      return true;
   }
}
