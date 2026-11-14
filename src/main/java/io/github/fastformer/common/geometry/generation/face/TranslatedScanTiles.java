package io.github.fastformer.common.geometry.generation.face;

import io.github.fastformer.common.geometry.LineTieBias;
import io.github.fastformer.common.geometry.generation.source.LineGenerator;
import io.github.fastformer.common.geometry.raster.BlockGenerationObserver;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;

import static io.github.fastformer.common.math.BlockQuads.isParallelogram;
import static io.github.fastformer.common.geometry.generation.face.TranslatedScanFaceRasterizer.*;
import static io.github.fastformer.common.geometry.generation.face.TranslatedScanModel.*;
import static io.github.fastformer.common.geometry.generation.face.TranslatedScanConnections.*;
import static io.github.fastformer.common.geometry.generation.face.TranslatedScanTopology.*;

/** Builds repeated micro-tiles and fills their equal-height sections. */
final class TranslatedScanTiles {
   private TranslatedScanTiles() {
   }

   /**
    * Treats the two owned edge paths as periodic mechanical words.  Their
    * prefix sums form a discrete parameter grid; each adjacent pair of steps
    * defines one tiny parallelogram which is filled in the canonical primary
    * projection.  Consequently a repeated pair of edge-step events always
    * receives exactly the same local patch instead of an unrelated hole fix.
    */
   static BresenhamFaceSweep.Attempt periodicMicroTileAttempt(
      ProjectedBresenhamFace.Frame frame,
      LineTieBias tieBias,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      if (frame == null || frame.vertices().size() != 4) {
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.NOT_APPLICABLE);
      }
      List<BlockPos> vertices = frame.vertices().stream().map(frame::restore).toList();
      BlockPos p00 = vertices.get(0);
      BlockPos p10 = vertices.get(1);
      BlockPos p11 = vertices.get(2);
      BlockPos p01 = vertices.get(3);
      if (!isParallelogram(p00, p10, p11, p01)) {
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.NOT_APPLICABLE);
      }

      long estimateU = LineGenerator.estimateBlocks(p00, p10);
      long estimateV = LineGenerator.estimateBlocks(p00, p01);
      if (estimateU > maxBlocks || estimateV > maxBlocks) {
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.LIMIT_EXCEEDED);
      }
      long cellsU = estimateU - 1L;
      long cellsV = estimateV - 1L;
      if (cellsU <= 0L || cellsV <= 0L) {
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.NOT_APPLICABLE);
      }
      if (cellsU > MAX_RAW_PAIRS / cellsV) {
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.NO_VALID_CANDIDATE);
      }
      List<BlockPos> edgeU = orientedPath(p00, p10, tieBias);
      List<BlockPos> edgeV = orientedPath(p00, p01, tieBias);

      LinkedHashSet<BlockPos> outline = new LinkedHashSet<>();
      for (int edge = 0; edge < vertices.size(); edge++) {
         observer.checkCancelled();
         outline.addAll(LineGenerator.path(
            vertices.get(edge),
            vertices.get((edge + 1) % vertices.size()),
            tieBias
         ));
      }
      if (outline.size() > maxBlocks) {
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.LIMIT_EXCEEDED);
      }

      ModelBuild coordinateBuild = Model.createAnalytic(frame, tieBias, maxBlocks, observer, true);
      if (coordinateBuild.model() == null
         && coordinateBuild.status() == BresenhamFaceSweep.Status.NO_VALID_CANDIDATE) {
         coordinateBuild = Model.createAnalytic(frame, tieBias, maxBlocks, observer, false);
      }
      if (coordinateBuild.model() == null) {
         return BresenhamFaceSweep.Attempt.failed(coordinateBuild.status());
      }
      Model coordinates = coordinateBuild.model();
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>(outline);
      for (int j = 0; j + 1 < edgeV.size(); j++) {
         observer.checkCancelled();
         for (int i = 0; i + 1 < edgeU.size(); i++) {
            if ((i & 1023) == 0) {
               observer.checkCancelled();
            }
            BlockPos first = translatedGridPoint(p00, edgeU.get(i), edgeV.get(j));
            BlockPos second = translatedGridPoint(p00, edgeU.get(i + 1), edgeV.get(j));
            BlockPos fourth = translatedGridPoint(p00, edgeU.get(i), edgeV.get(j + 1));
            BlockPos third = translatedGridPoint(p00, edgeU.get(i + 1), edgeV.get(j + 1));
            if (!fillMicroTile(
               coordinates,
               List.of(first, second, third, fourth),
               result,
               maxBlocks,
               observer
            )) {
               return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.LIMIT_EXCEEDED);
            }
         }
      }
      LinkedHashSet<BlockPos> beforeSections = new LinkedHashSet<>(result);
      LinkedHashSet<BlockPos> sectionAdditions = new LinkedHashSet<>();
      int workspaceLimit = (int)Math.min(
         Integer.MAX_VALUE,
         (long)maxBlocks + Math.min(512L, Math.max(16L, maxBlocks / 8L))
      );
      BresenhamFaceSweep.Status sectionStatus = fillEqualHeightSections(
         coordinates,
         result,
         sectionAdditions,
         workspaceLimit,
         observer
      );
      if (sectionStatus != BresenhamFaceSweep.Status.SUCCESS) {
         return BresenhamFaceSweep.Attempt.failed(sectionStatus);
      }
      Set<BlockPos> connectionSupports = minimumSixConnectionSupports(coordinates, result, observer);
      if (connectionSupports == null) {
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.NO_VALID_CANDIDATE);
      }
      for (BlockPos support : connectionSupports) {
         if (!addWithinLimit(result, support, workspaceLimit)) {
            return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.LIMIT_EXCEEDED);
         }
      }
      trimReplacedBlocks(
         coordinates,
         result,
         beforeSections,
         sectionAdditions,
         outline,
         observer
      );
      if (result.size() > maxBlocks) {
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.LIMIT_EXCEEDED);
      }
      if (!analyze(coordinates, result, observer).valid()) {
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.NO_VALID_CANDIDATE);
      }
      return BresenhamFaceSweep.Attempt.success(new BresenhamFaceSweep.Result(
         Collections.unmodifiableSet(result),
         Collections.unmodifiableSet(outline)
      ));
   }

   private static List<BlockPos> orientedPath(BlockPos from, BlockPos to, LineTieBias tieBias) {
      ArrayList<BlockPos> result = new ArrayList<>(LineGenerator.path(from, to, tieBias));
      if (!result.isEmpty() && !result.getFirst().equals(from)) {
         Collections.reverse(result);
      }
      return List.copyOf(result);
   }

   private static BlockPos translatedGridPoint(BlockPos origin, BlockPos alongU, BlockPos alongV) {
      return new BlockPos(
         safeInt((long)alongU.getX() + alongV.getX() - origin.getX()),
         safeInt((long)alongU.getY() + alongV.getY() - origin.getY()),
         safeInt((long)alongU.getZ() + alongV.getZ() - origin.getZ())
      );
   }

   static boolean fillMicroTile(
      Model coordinates,
      List<BlockPos> corners,
      Set<BlockPos> result,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      LinkedHashSet<BlockPos> tile = new LinkedHashSet<>(corners);
      Pixel p00 = coordinates.project(corners.get(0));
      Pixel p10 = coordinates.project(corners.get(1));
      Pixel p11 = coordinates.project(corners.get(2));
      Pixel p01 = coordinates.project(corners.get(3));
      long duU = (long)p10.u() - p00.u();
      long duV = (long)p10.v() - p00.v();
      long dvU = (long)p01.u() - p00.u();
      long dvV = (long)p01.v() - p00.v();
      long determinant = duU * dvV - duV * dvU;
      if (determinant != 0L) {
         LinkedHashSet<Pixel> domain = Model.scanConvexDomain(
            List.of(p00, p10, p11, p01),
            16,
            observer
         );
         if (domain != null) {
            long w00 = coordinates.height(corners.get(0));
            long duW = (long)coordinates.height(corners.get(1)) - w00;
            long dvW = (long)coordinates.height(corners.get(3)) - w00;
            long normalU = duV * dvW - duW * dvV;
            long normalV = duW * dvU - duU * dvW;
            long normalW = determinant;
            for (Pixel pixel : sorted(domain)) {
               observer.checkCancelled();
               long numerator = normalW * w00
                  - normalU * ((long)pixel.u() - p00.u())
                  - normalV * ((long)pixel.v() - p00.v());
               for (long height : nearestQuotients(numerator, normalW)) {
                  tile.add(coordinates.restore(pixel, safeInt(height)));
               }
            }
         }
      } else {
         Map<Pixel, NavigableSet<Integer>> degenerateColumns = columns(coordinates, tile);
         for (Map.Entry<Pixel, NavigableSet<Integer>> entry : degenerateColumns.entrySet()) {
            NavigableSet<Integer> heights = entry.getValue();
            for (int height = heights.first(); height <= heights.last(); height++) {
               tile.add(coordinates.restore(entry.getKey(), height));
            }
         }
      }

      for (BlockPos block : tile) {
         if (!addWithinLimit(result, block, maxBlocks)) {
            return false;
         }
      }
      return true;
   }

   /** Closes and fills every equal-height fragment set in its own two-dimensional slice. */
   private static BresenhamFaceSweep.Status fillEqualHeightSections(
      Model coordinates,
      Set<BlockPos> result,
      Set<BlockPos> addedBySections,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      TreeMap<Integer, LinkedHashSet<Pixel>> layers = new TreeMap<>();
      for (BlockPos block : result) {
         layers.computeIfAbsent(coordinates.height(block), ignored -> new LinkedHashSet<>())
            .add(coordinates.project(block));
      }
      LinkedHashSet<BlockPos> additions = new LinkedHashSet<>();
      long scanned = 0L;
      for (Map.Entry<Integer, LinkedHashSet<Pixel>> layer : layers.entrySet()) {
         observer.checkCancelled();
         int layerHeight = layer.getKey();
         List<Pixel> sectionFragments = layer.getValue().stream()
            .filter(coordinates.domain()::contains)
            .toList();
         List<Pixel> hull = convexHull(sectionFragments);
         if (hull.size() == 2) {
            BlockPos first = coordinates.restore(hull.get(0), layerHeight);
            BlockPos second = coordinates.restore(hull.get(1), layerHeight);
            for (BlockPos block : LineGenerator.path(first, second)) {
               Pixel pixel = coordinates.project(block);
               if (coordinates.domain().contains(pixel)) {
                  additions.add(block);
               }
            }
            continue;
         }
         if (hull.size() < 3) {
            continue;
         }
         int minimumU = hull.stream().mapToInt(Pixel::u).min().orElseThrow();
         int maximumU = hull.stream().mapToInt(Pixel::u).max().orElseThrow();
         int minimumV = hull.stream().mapToInt(Pixel::v).min().orElseThrow();
         int maximumV = hull.stream().mapToInt(Pixel::v).max().orElseThrow();
         long width = (long)maximumU - minimumU + 1L;
         long height = (long)maximumV - minimumV + 1L;
         if (width <= 0L || height <= 0L || width > (MAX_DOMAIN_SCAN - scanned) / height) {
            return BresenhamFaceSweep.Status.NO_VALID_CANDIDATE;
         }
         scanned += width * height;
         for (int v = minimumV; v <= maximumV; v++) {
            for (int u = minimumU; u <= maximumU; u++) {
               if (((u - minimumU) & 1023) == 0) {
                  observer.checkCancelled();
               }
               Pixel pixel = new Pixel(u, v);
               if (coordinates.domain().contains(pixel) && Model.insideConvex(pixel, hull)) {
                  additions.add(coordinates.restore(pixel, layerHeight));
               }
               if (u == Integer.MAX_VALUE) {
                  break;
               }
            }
            if (v == Integer.MAX_VALUE) {
               break;
            }
         }
      }
      for (BlockPos block : additions) {
         boolean added = !result.contains(block);
         if (!addWithinLimit(result, block, maxBlocks)) {
            return BresenhamFaceSweep.Status.LIMIT_EXCEEDED;
         }
         if (added) {
            addedBySections.add(block);
         }
      }
      return BresenhamFaceSweep.Status.SUCCESS;
   }

   private static void trimReplacedBlocks(
      Model model,
      LinkedHashSet<BlockPos> result,
      Set<BlockPos> beforeSections,
      Set<BlockPos> sectionAdditions,
      Set<BlockPos> owned,
      BlockGenerationObserver observer
   ) {
      Map<Pixel, NavigableSet<Integer>> addedColumns = columns(model, sectionAdditions);
      LinkedHashSet<BlockPos> lowerCandidates = new LinkedHashSet<>();
      LinkedHashSet<BlockPos> upperCandidates = new LinkedHashSet<>();
      for (BlockPos block : beforeSections) {
         if (owned.contains(block)) {
            continue;
         }
         Pixel pixel = model.project(block);
         int height = model.height(block);
         NavigableSet<Integer> additions = addedColumns.get(pixel);
         if (additions == null) {
            continue;
         }
         double oldResidual = Math.abs(model.realHeight(pixel) - height);
         for (int addedHeight : additions) {
            if (Math.abs(model.realHeight(pixel) - addedHeight) + 1.0E-9 >= oldResidual) {
               continue;
            }
            if ((long)addedHeight == (long)height + 1L) {
               lowerCandidates.add(block);
            } else if ((long)addedHeight == (long)height - 1L) {
               upperCandidates.add(block);
            }
         }
      }
      observer.checkCancelled();
      LinkedHashSet<BlockPos> union = new LinkedHashSet<>(lowerCandidates);
      union.addAll(upperCandidates);
      Set<BlockPos> selected = validRemoval(model, result, union, observer)
         ? union
         : chooseSymmetricRemoval(model, result, lowerCandidates, upperCandidates, observer);
      result.removeAll(selected);
   }

   private static Set<BlockPos> chooseSymmetricRemoval(
      Model model,
      Set<BlockPos> result,
      Set<BlockPos> lower,
      Set<BlockPos> upper,
      BlockGenerationObserver observer
   ) {
      boolean lowerValid = validRemoval(model, result, lower, observer);
      boolean upperValid = validRemoval(model, result, upper, observer);
      if (lowerValid != upperValid) {
         return lowerValid ? lower : upper;
      }
      if (!lowerValid) {
         return Set.of();
      }
      int sizeComparison = Integer.compare(lower.size(), upper.size());
      if (sizeComparison != 0) {
         return sizeComparison > 0 ? lower : upper;
      }
      double lowerResidual = removalResidual(model, lower);
      double upperResidual = removalResidual(model, upper);
      int residualComparison = Double.compare(lowerResidual, upperResidual);
      return residualComparison > 0 ? lower : residualComparison < 0 ? upper : Set.of();
   }

   private static boolean validRemoval(
      Model model,
      Set<BlockPos> result,
      Set<BlockPos> removal,
      BlockGenerationObserver observer
   ) {
      if (removal.isEmpty()) {
         return false;
      }
      LinkedHashSet<BlockPos> candidate = new LinkedHashSet<>(result);
      candidate.removeAll(removal);
      return analyze(model, candidate, observer).valid();
   }

   private static double removalResidual(Model model, Set<BlockPos> removal) {
      double result = 0.0;
      for (BlockPos block : removal) {
         Pixel pixel = model.project(block);
         result += Math.abs(model.realHeight(pixel) - model.height(block));
      }
      return result;
   }

   private static List<Pixel> convexHull(Collection<Pixel> source) {
      List<Pixel> points = source.stream().distinct().sorted(PIXEL_ORDER).toList();
      if (points.size() <= 2) {
         return points;
      }
      ArrayList<Pixel> lower = new ArrayList<>();
      for (Pixel point : points) {
         while (lower.size() >= 2
            && orientation(lower.get(lower.size() - 2), lower.getLast(), point) <= 0) {
            lower.removeLast();
         }
         lower.add(point);
      }
      ArrayList<Pixel> upper = new ArrayList<>();
      for (int index = points.size() - 1; index >= 0; index--) {
         Pixel point = points.get(index);
         while (upper.size() >= 2
            && orientation(upper.get(upper.size() - 2), upper.getLast(), point) <= 0) {
            upper.removeLast();
         }
         upper.add(point);
      }
      lower.removeLast();
      upper.removeLast();
      lower.addAll(upper);
      return List.copyOf(lower);
   }

   private static int orientation(Pixel first, Pixel second, Pixel third) {
      BigInteger ax = BigInteger.valueOf((long)second.u() - first.u());
      BigInteger ay = BigInteger.valueOf((long)second.v() - first.v());
      BigInteger bx = BigInteger.valueOf((long)third.u() - first.u());
      BigInteger by = BigInteger.valueOf((long)third.v() - first.v());
      return ax.multiply(by).subtract(ay.multiply(bx)).signum();
   }

   private static boolean addWithinLimit(Set<BlockPos> result, BlockPos block, int maxBlocks) {
      return result.contains(block) || result.size() < maxBlocks && result.add(block);
   }

}
