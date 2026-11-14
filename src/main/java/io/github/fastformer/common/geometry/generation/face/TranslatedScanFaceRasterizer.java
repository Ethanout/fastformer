package io.github.fastformer.common.geometry.generation.face;

import io.github.fastformer.common.geometry.LineTieBias;
import io.github.fastformer.common.geometry.raster.BlockGenerationObserver;
import java.math.BigInteger;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;

import static io.github.fastformer.common.geometry.generation.face.TranslatedScanTiles.*;
import static io.github.fastformer.common.geometry.generation.face.TranslatedScanConnections.*;
import static io.github.fastformer.common.geometry.generation.face.TranslatedScanTopology.*;
import static io.github.fastformer.common.geometry.generation.face.TranslatedScanModel.*;

/** Builds a periodic micro-tiled face while preserving four exact nested-Bresenham edges. */
final class TranslatedScanFaceRasterizer {
   private static final int MAX_CUT_COLUMNS = 8_192;
   static final long MAX_RAW_PAIRS = 4_000_000L;
   static final long MAX_DOMAIN_SCAN = 4_000_000L;
   static final Comparator<Pixel> PIXEL_ORDER = Comparator
      .comparingInt(Pixel::u)
      .thenComparingInt(Pixel::v);

   private TranslatedScanFaceRasterizer() {
   }

   static BresenhamFaceSweep.Result generate(
      ProjectedBresenhamFace.Frame frame,
      LineTieBias tieBias
   ) {
      return attempt(frame, tieBias, Integer.MAX_VALUE, BlockGenerationObserver.NONE).result();
   }

   static BresenhamFaceSweep.Result generate(
      ProjectedBresenhamFace.Frame frame,
      LineTieBias tieBias,
      BlockGenerationObserver observer
   ) {
      return attempt(frame, tieBias, Integer.MAX_VALUE, observer).result();
   }

   static BresenhamFaceSweep.Attempt attempt(
      ProjectedBresenhamFace.Frame frame,
      LineTieBias tieBias,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      return attempt(frame, tieBias, maxBlocks, observer, false);
   }

   static BresenhamFaceSweep.Attempt thinAttempt(
      ProjectedBresenhamFace.Frame frame,
      LineTieBias tieBias,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      if (maxBlocks <= 0) {
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.LIMIT_EXCEEDED);
      }
      return analyticHeightBandAttempt(
         frame,
         tieBias,
         maxBlocks,
         observer == null ? BlockGenerationObserver.NONE : observer
      );
   }

   static BresenhamFaceSweep.Attempt forceEmergencyFallbackForTesting(
      ProjectedBresenhamFace.Frame frame,
      LineTieBias tieBias,
      int maxBlocks
   ) {
      return emergencyFallback(frame, tieBias, maxBlocks, BlockGenerationObserver.NONE);
   }

   static BresenhamFaceSweep.Attempt emergencyFallback(
      ProjectedBresenhamFace.Frame frame,
      LineTieBias tieBias,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      return attempt(frame, tieBias, maxBlocks, observer, true);
   }

   static Set<BlockPos> scanConvexDomainForTesting(List<BlockPos> polygon, int maxBlocks) {
      List<Pixel> projected = polygon.stream()
         .map(position -> new Pixel(position.getX(), position.getY()))
         .toList();
      Set<Pixel> result = Model.scanConvexDomain(projected, maxBlocks, BlockGenerationObserver.NONE);
      return testBlocks(result);
   }

   static Set<BlockPos> completeProjectedDomainForTesting(Set<BlockPos> source, int maxBlocks) {
      LinkedHashSet<Pixel> projected = new LinkedHashSet<>();
      source.forEach(position -> projected.add(new Pixel(position.getX(), position.getY())));
      return testBlocks(Model.completeProjectedDomain(projected, maxBlocks, BlockGenerationObserver.NONE));
   }

   static boolean insideConvexForTesting(BlockPos point, List<BlockPos> polygon) {
      Pixel projectedPoint = new Pixel(point.getX(), point.getY());
      List<Pixel> projectedPolygon = polygon.stream()
         .map(position -> new Pixel(position.getX(), position.getY()))
         .toList();
      return Model.insideConvex(projectedPoint, projectedPolygon);
   }

   static Set<BlockPos> fillMicroTileForTesting(
      ProjectedBresenhamFace.Frame frame,
      List<BlockPos> corners
   ) {
      if (frame == null || corners.size() != 4) {
         return Set.of();
      }
      Model coordinates = new Model(frame, Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), Set.of());
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
      if (!fillMicroTile(
         coordinates,
         List.copyOf(corners),
         result,
         64,
         BlockGenerationObserver.NONE
      )) {
         return Set.of();
      }
      return Collections.unmodifiableSet(result);
   }

   private static Set<BlockPos> testBlocks(Set<Pixel> source) {
      if (source == null) {
         return Set.of();
      }
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
      source.forEach(pixel -> result.add(new BlockPos(pixel.u(), pixel.v(), 0)));
      return Collections.unmodifiableSet(result);
   }

   private static BresenhamFaceSweep.Attempt attempt(
      ProjectedBresenhamFace.Frame frame,
      LineTieBias tieBias,
      int maxBlocks,
      BlockGenerationObserver observer,
      boolean emergencyOnly
   ) {
      if (maxBlocks <= 0) {
         return BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.LIMIT_EXCEEDED);
      }
      BlockGenerationObserver effectiveObserver = observer == null ? BlockGenerationObserver.NONE : observer;
      if (!emergencyOnly) {
         BresenhamFaceSweep.Attempt periodic = periodicMicroTileAttempt(
            frame,
            tieBias,
            maxBlocks,
            effectiveObserver
         );
         if (periodic.status() == BresenhamFaceSweep.Status.SUCCESS) {
            return periodic;
         }
         BresenhamFaceSweep.Attempt coordinated = coordinatedSixConnectedAttempt(
            frame,
            tieBias,
            maxBlocks,
            effectiveObserver
         );
         if (coordinated.status() == BresenhamFaceSweep.Status.SUCCESS) {
            return coordinated;
         }
         BresenhamFaceSweep.Attempt analytic = analyticHeightBandAttempt(frame, tieBias, maxBlocks, effectiveObserver);
         if (analytic.status() == BresenhamFaceSweep.Status.SUCCESS) {
            return analytic;
         }
         return BresenhamFaceSweep.Attempt.failed(
            periodic.status() == BresenhamFaceSweep.Status.LIMIT_EXCEEDED
                  || coordinated.status() == BresenhamFaceSweep.Status.LIMIT_EXCEEDED
                  || analytic.status() == BresenhamFaceSweep.Status.LIMIT_EXCEEDED
               ? BresenhamFaceSweep.Status.LIMIT_EXCEEDED
               : analytic.status()
         );
      }
      ModelBuild built = Model.create(frame, tieBias, maxBlocks, effectiveObserver);
      if (emergencyOnly && built.model() == null
         && built.status() == BresenhamFaceSweep.Status.NO_VALID_CANDIDATE) {
         built = Model.create(frame, tieBias, maxBlocks, effectiveObserver, false);
      }
      if (built.model() == null) {
         return BresenhamFaceSweep.Attempt.failed(built.status());
      }
      Model model = built.model();
      emergencyOnly |= built.analyticOnly();

      FallbackBlocks floor = graphFallback(model, HeightSnap.FLOOR, maxBlocks, effectiveObserver);
      FallbackBlocks ceil = graphFallback(model, HeightSnap.CEIL, maxBlocks, effectiveObserver);
      if (emergencyOnly) {
         Set<BlockPos> selected = !floor.blocks().isEmpty() ? floor.blocks() : ceil.blocks();
         if (selected.isEmpty()) {
            BresenhamFaceSweep.Status status = floor.limitExceeded() || ceil.limitExceeded()
               ? BresenhamFaceSweep.Status.LIMIT_EXCEEDED
               : BresenhamFaceSweep.Status.NO_VALID_CANDIDATE;
            return BresenhamFaceSweep.Attempt.failed(status);
         }
         return BresenhamFaceSweep.Attempt.success(
            new BresenhamFaceSweep.Result(
               Collections.unmodifiableSet(new LinkedHashSet<>(selected)),
               model.outline()
            )
         );
      }

      Set<BlockPos> ownedConnectors = ownedSixConnectors(model, tieBias, effectiveObserver);
      Candidate best = null;
      boolean candidateLimitExceeded = floor.limitExceeded() || ceil.limitExceeded();
      if (model.domain().size() <= MAX_CUT_COLUMNS) {
         Set<BlockPos> cut = withConnectors(
            minimumSeparator(model, effectiveObserver),
            ownedConnectors,
            maxBlocks
         );
         candidateLimitExceeded |= cut.size() > maxBlocks;
         best = choose(
            best,
            model,
            "translated-cut",
            cut,
            1,
            maxBlocks,
            effectiveObserver
         );
      }
      Set<BlockPos> preferred = withConnectors(
         materializePreferred(model, maxBlocks, effectiveObserver),
         ownedConnectors,
         maxBlocks
      );
      candidateLimitExceeded |= preferred.isEmpty();
      best = choose(
         best,
         model,
         "translated-filled",
         preferred,
         0,
         maxBlocks,
         effectiveObserver
      );
      best = choose(best, model, "floor-fallback", withConnectors(floor.blocks(), ownedConnectors, maxBlocks), 2, maxBlocks, effectiveObserver);
      best = choose(best, model, "ceil-fallback", withConnectors(ceil.blocks(), ownedConnectors, maxBlocks), 3, maxBlocks, effectiveObserver);
      if (best == null) {
         return BresenhamFaceSweep.Attempt.failed(candidateLimitExceeded
            ? BresenhamFaceSweep.Status.LIMIT_EXCEEDED
            : BresenhamFaceSweep.Status.NO_VALID_CANDIDATE);
      }
      return BresenhamFaceSweep.Attempt.success(
         new BresenhamFaceSweep.Result(
            Collections.unmodifiableSet(new LinkedHashSet<>(best.blocks())),
            model.outline()
         )
      );
   }

   static long[] nearestQuotients(long numerator, long denominator) {
      if (denominator < 0L) {
         numerator = -numerator;
         denominator = -denominator;
      }
      long floor = Math.floorDiv(numerator, denominator);
      long remainder = Math.floorMod(numerator, denominator);
      long doubled = remainder * 2L;
      if (doubled == denominator) {
         // A single choice at an exact half is reflected to the other choice
         // by a signed-axis transform.  Keeping both is the smallest local
         // supercover and avoids hiding a world-axis preference in the tile.
         return new long[]{floor, floor + 1L};
      }
      return new long[]{doubled > denominator ? floor + 1L : floor};
   }

   private static Set<BlockPos> withConnectors(
      Set<BlockPos> blocks,
      Set<BlockPos> connectors,
      int maxBlocks
   ) {
      if (blocks.isEmpty()) {
         return Set.of();
      }
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>(blocks);
      result.addAll(connectors);
      return result.size() <= maxBlocks ? result : Set.of();
   }

   /**
    * Fills the analytic parallelogram as disjoint half-open height bands.
    *
    * A projected lattice point belongs to band h exactly when
    * h - 1/2 <= realHeight < h + 1/2.  Enumerating the projected domain and
    * snapping once is the exact integer form of clipping and filling those
    * bands.  Owned Bresenham edges are then unioned verbatim; they never grow
    * the analytic domain and no vertical span is invented between edge voxels.
    */
   private static BresenhamFaceSweep.Attempt analyticHeightBandAttempt(
      ProjectedBresenhamFace.Frame frame,
      LineTieBias tieBias,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      ModelBuild built = Model.createAnalytic(frame, tieBias, maxBlocks, observer, true);
      if (built.model() == null && built.status() == BresenhamFaceSweep.Status.NO_VALID_CANDIDATE) {
         built = Model.createAnalytic(frame, tieBias, maxBlocks, observer, false);
      }
      if (built.model() == null) {
         return BresenhamFaceSweep.Attempt.failed(built.status());
      }
      Model model = built.model();
      FallbackBlocks bands = materializeAnalyticHeightBands(model, maxBlocks, observer);
      if (bands.blocks().isEmpty()) {
         return BresenhamFaceSweep.Attempt.failed(
            bands.limitExceeded()
               ? BresenhamFaceSweep.Status.LIMIT_EXCEEDED
               : BresenhamFaceSweep.Status.NO_VALID_CANDIDATE
         );
      }
      return BresenhamFaceSweep.Attempt.success(
         new BresenhamFaceSweep.Result(
            Collections.unmodifiableSet(new LinkedHashSet<>(bands.blocks())),
            model.outline()
         )
      );
   }

   static FallbackBlocks materializeAnalyticHeightBands(
      Model model,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>(model.outline());
      if (result.size() > maxBlocks) {
         return FallbackBlocks.limited();
      }
      for (Pixel pixel : sorted(model.domain())) {
         observer.checkCancelled();
         // Every analytic column receives exactly one band voxel.  Owned edge
         // voxels may add a second boundary layer, but unlike the old fallback
         // we never fill an entire vertical span between unrelated edge hits.
         int height = safeInt((long)Math.floor(model.realHeight(pixel) + 0.5));
         BlockPos block = model.restore(pixel, height);
         if (!result.contains(block) && result.size() >= maxBlocks) {
            return FallbackBlocks.limited();
         }
         result.add(block);
      }
      return FallbackBlocks.success(result);
   }

   private static Candidate choose(
      Candidate current,
      Model model,
      String name,
      Set<BlockPos> blocks,
      int priority,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      if (blocks.isEmpty() || blocks.size() > maxBlocks) {
         return current;
      }
      Analysis analysis = analyze(model, blocks, observer);
      if (!analysis.valid()) {
         return current;
      }
      Candidate candidate = new Candidate(name, blocks, analysis, priority);
      return current == null || candidate.compareTo(current) < 0 ? candidate : current;
   }

   static int safeInt(long value) {
      return value < Integer.MIN_VALUE ? Integer.MIN_VALUE : value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)value;
   }

   static BigInteger difference(int first, int second) {
      return BigInteger.valueOf(first).subtract(BigInteger.valueOf(second));
   }

   static BigInteger floorDivide(BigInteger numerator, BigInteger positiveDenominator) {
      BigInteger[] divided = numerator.divideAndRemainder(positiveDenominator);
      return divided[1].signum() < 0 ? divided[0].subtract(BigInteger.ONE) : divided[0];
   }

   record Pixel(int u, int v) {
      Pixel add(Pixel other) {
         return new Pixel(Math.addExact(this.u, other.u), Math.addExact(this.v, other.v));
      }
   }

   private record SeamScore(double maximumResidual, double totalResidual) implements Comparable<SeamScore> {
      @Override
      public int compareTo(SeamScore other) {
         int compared = Double.compare(this.maximumResidual, other.maximumResidual);
         return compared != 0 ? compared : Double.compare(this.totalResidual, other.totalResidual);
      }
   }

   record LocalPoint(int u, int v, int w) implements Comparable<LocalPoint> {
      @Override
      public int compareTo(LocalPoint other) {
         int compared = Integer.compare(this.u, other.u);
         if (compared == 0) {
            compared = Integer.compare(this.v, other.v);
         }
         return compared != 0 ? compared : Integer.compare(this.w, other.w);
      }
   }

   record ConnectorRoute(List<LocalPoint> points, double maximumResidual, double totalResidual)
      implements Comparable<ConnectorRoute> {
      static ConnectorRoute create(Model model, List<LocalPoint> points) {
         double maximum = 0.0;
         double total = 0.0;
         for (LocalPoint point : points) {
            double residual = Math.abs(
               model.frame().normal().x * point.u()
                  + model.frame().normal().y * point.v()
                  + model.frame().normal().z * point.w()
            );
            maximum = Math.max(maximum, residual);
            total += residual;
         }
         return new ConnectorRoute(List.copyOf(points), maximum, total);
      }

      @Override
      public int compareTo(ConnectorRoute other) {
         int compared = Double.compare(this.maximumResidual, other.maximumResidual);
         if (compared == 0) {
            compared = Double.compare(this.totalResidual, other.totalResidual);
         }
         if (compared != 0) {
            return compared;
         }
         int size = Math.min(this.points.size(), other.points.size());
         for (int index = 0; index < size; index++) {
            compared = this.points.get(index).compareTo(other.points.get(index));
            if (compared != 0) {
               return compared;
            }
         }
         return Integer.compare(this.points.size(), other.points.size());
      }
   }

   record ComponentMap(Map<BlockPos, Integer> byBlock, int count) {
   }

   record ConnectionSupport(
      BlockPos position,
      Set<Integer> components,
      int interiorPenalty,
      double residual,
      LocalPoint local
   ) implements Comparable<ConnectionSupport> {
      @Override
      public int compareTo(ConnectionSupport other) {
         int compared = Integer.compare(this.interiorPenalty, other.interiorPenalty);
         if (compared == 0) {
            compared = Double.compare(this.residual, other.residual);
         }
         return compared != 0 ? compared : this.local.compareTo(other.local);
      }
   }

   record Cell(Pixel pixel, int height) {
   }

   record BigPoint(BigInteger u, BigInteger v) {
      static BigPoint of(Pixel pixel) {
         return new BigPoint(BigInteger.valueOf(pixel.u()), BigInteger.valueOf(pixel.v()));
      }

      BigPoint add(BigPoint other) {
         return new BigPoint(this.u.add(other.u), this.v.add(other.v));
      }

      BigPoint subtract(BigPoint other) {
         return new BigPoint(this.u.subtract(other.u), this.v.subtract(other.v));
      }

      BigPoint scale(BigInteger amount) {
         return new BigPoint(this.u.multiply(amount), this.v.multiply(amount));
      }

      BigInteger cross(BigPoint other) {
         return this.u.multiply(other.v).subtract(this.v.multiply(other.u));
      }

      BigInteger coordinateGcd() {
         return this.u.abs().gcd(this.v.abs());
      }

      Pixel toPixel() {
         return new Pixel(this.u.intValueExact(), this.v.intValueExact());
      }
   }

   record MissingRun(int v, long minimumU, long maximumU, boolean exterior) {
   }

   record IntRange(int minimum, int maximum) {
   }

   record FallbackBlocks(Set<BlockPos> blocks, boolean limitExceeded) {
      static FallbackBlocks success(Set<BlockPos> blocks) {
         return new FallbackBlocks(blocks, false);
      }

      static FallbackBlocks limited() {
         return new FallbackBlocks(Set.of(), true);
      }
   }

   enum HeightSnap {
      FLOOR,
      NEAREST,
      CEIL
   }

   record NodePair(int enter, int leave) {
   }

   record Analysis(
      boolean valid,
      int maximumThickness,
      int maximumInteriorThickness,
      int support,
      int largestSupportComponent,
      int longestExtraContour,
      int basinCells,
      int strictExtrema,
      int blocks
   ) {
   }

   private record Candidate(String name, Set<BlockPos> blocks, Analysis analysis, int priority)
      implements Comparable<Candidate> {
      @Override
      public int compareTo(Candidate other) {
         int compared = Integer.compare(this.analysis.basinCells(), other.analysis.basinCells());
         if (compared == 0) {
            compared = Integer.compare(this.analysis.strictExtrema(), other.analysis.strictExtrema());
         }
         if (compared == 0) {
            compared = Integer.compare(
            this.analysis.largestSupportComponent(),
            other.analysis.largestSupportComponent()
            );
         }
         if (compared == 0) {
            compared = Integer.compare(this.analysis.longestExtraContour(), other.analysis.longestExtraContour());
         }
         if (compared == 0) {
            compared = Integer.compare(this.analysis.support(), other.analysis.support());
         }
         if (compared == 0) {
            compared = Integer.compare(this.analysis.blocks(), other.analysis.blocks());
         }
         return compared != 0 ? compared : Integer.compare(this.priority, other.priority);
      }
   }

   record ModelBuild(
      BresenhamFaceSweep.Status status,
      Model model,
      boolean analyticOnly
   ) {
      static ModelBuild success(Model model, boolean analyticOnly) {
         return new ModelBuild(BresenhamFaceSweep.Status.SUCCESS, model, analyticOnly);
      }

      static ModelBuild failed(BresenhamFaceSweep.Status status) {
         return new ModelBuild(status, null, false);
      }
   }

   record Ray(Pixel direction, int radius, double phase) {
   }

   record FloodNode(Pixel pixel, long level) implements Comparable<FloodNode> {
      @Override
      public int compareTo(FloodNode other) {
         int compared = Long.compare(this.level, other.level);
         return compared != 0 ? compared : PIXEL_ORDER.compare(this.pixel, other.pixel);
      }
   }

}
