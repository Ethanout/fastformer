package io.github.fastformer.common.geometry.generation.face;

import io.github.fastformer.common.geometry.LineTieBias;
import io.github.fastformer.common.geometry.generation.source.LineGenerator;
import io.github.fastformer.common.geometry.raster.BlockGenerationObserver;
import io.github.fastformer.core.graph.ExteriorUnionFind;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;

import static io.github.fastformer.common.math.BlockQuads.isParallelogram;
import static io.github.fastformer.common.geometry.generation.face.TranslatedScanFaceRasterizer.*;
import static io.github.fastformer.common.geometry.generation.face.TranslatedScanTopology.*;

/** Owns projection coordinates, lattice-domain construction, and height preferences. */
final class TranslatedScanModel {
   private static final long MIN_RAW_PAIR_BUDGET = 16_384L;
   private static final long RAW_PAIR_BUDGET_MULTIPLIER = 8L;

   private TranslatedScanModel() {
   }

   record Model(
      ProjectedBresenhamFace.Frame frame,
      Set<BlockPos> raw,
      Set<BlockPos> outline,
      Set<Pixel> domain,
      Map<Pixel, NavigableSet<Integer>> preferred,
      Map<Pixel, NavigableSet<Integer>> ownedColumns,
      Set<Pixel> boundary
   ) {
      static ModelBuild create(
         ProjectedBresenhamFace.Frame frame,
         LineTieBias tieBias,
         int maxBlocks,
         BlockGenerationObserver observer
      ) {
         return create(frame, tieBias, maxBlocks, observer, true, false);
      }

      static ModelBuild createAnalytic(
         ProjectedBresenhamFace.Frame frame,
         LineTieBias tieBias,
         int maxBlocks,
         BlockGenerationObserver observer,
         boolean requireConnectedDomain
      ) {
         return create(frame, tieBias, maxBlocks, observer, requireConnectedDomain, true);
      }

      static ModelBuild create(
         ProjectedBresenhamFace.Frame frame,
         LineTieBias tieBias,
         int maxBlocks,
         BlockGenerationObserver observer,
         boolean requireConnectedDomain
      ) {
         return create(frame, tieBias, maxBlocks, observer, requireConnectedDomain, false);
      }

      static ModelBuild create(
         ProjectedBresenhamFace.Frame frame,
         LineTieBias tieBias,
         int maxBlocks,
         BlockGenerationObserver observer,
         boolean requireConnectedDomain,
         boolean forceAnalyticOnly
      ) {
         if (frame == null || frame.vertices().size() != 4) {
            return ModelBuild.failed(BresenhamFaceSweep.Status.NOT_APPLICABLE);
         }
         List<BlockPos> vertices = frame.vertices().stream().map(frame::restore).toList();
         BlockPos origin = vertices.getFirst();
         BlockPos p10 = vertices.get(1);
         BlockPos p11 = vertices.get(2);
         BlockPos p01 = vertices.get(3);
         if (!isParallelogram(origin, p10, p11, p01)) {
            return ModelBuild.failed(BresenhamFaceSweep.Status.NOT_APPLICABLE);
         }

         Model coordinateSystem = new Model(frame, Set.of(), Set.of(), Set.of(), Map.of(), Map.of(), Set.of());
         List<Pixel> polygon = vertices.stream().map(coordinateSystem::project).toList();
         int minimumU = polygon.stream().mapToInt(Pixel::u).min().orElseThrow();
         int maximumU = polygon.stream().mapToInt(Pixel::u).max().orElseThrow();
         int minimumV = polygon.stream().mapToInt(Pixel::v).min().orElseThrow();
         int maximumV = polygon.stream().mapToInt(Pixel::v).max().orElseThrow();
         long width = (long)maximumU - minimumU + 1L;
         long height = (long)maximumV - minimumV + 1L;
         boolean expansiveDomain = width <= 0L
            || height <= 0L
            || width > MAX_DOMAIN_SCAN / height;

         long lineUCount = LineGenerator.estimateBlocks(origin, p10);
         long lineVCount = LineGenerator.estimateBlocks(origin, p01);
         long budgetScaledPairs = Math.min(
            MAX_RAW_PAIRS,
            Math.max(MIN_RAW_PAIR_BUDGET, (long)maxBlocks * RAW_PAIR_BUDGET_MULTIPLIER)
         );
         boolean analyticOnly = forceAnalyticOnly
            || expansiveDomain
            || lineUCount <= 0L
            || lineVCount <= 0L
            || lineUCount > budgetScaledPairs
            || lineVCount > budgetScaledPairs
            || lineUCount > budgetScaledPairs / lineVCount;
         for (int index = 0; index < 4; index++) {
            long edgeBlocks = LineGenerator.estimateBlocks(
               vertices.get(index),
               vertices.get((index + 1) % 4)
            );
            if (edgeBlocks > maxBlocks) {
               return ModelBuild.failed(BresenhamFaceSweep.Status.LIMIT_EXCEEDED);
            }
         }

         LinkedHashSet<BlockPos> outline = new LinkedHashSet<>();
         for (int index = 0; index < 4; index++) {
            observer.checkCancelled();
            outline.addAll(LineGenerator.path(vertices.get(index), vertices.get((index + 1) % 4), tieBias));
         }
         if (outline.size() > maxBlocks) {
            return ModelBuild.failed(BresenhamFaceSweep.Status.LIMIT_EXCEEDED);
         }

         LinkedHashSet<Pixel> domain = scanConvexDomain(polygon, maxBlocks, observer);
         if (domain == null) {
            return ModelBuild.failed(BresenhamFaceSweep.Status.LIMIT_EXCEEDED);
         }

         LinkedHashSet<BlockPos> raw = new LinkedHashSet<>();
         if (!analyticOnly) {
            List<BlockPos> lineU = LineGenerator.path(origin, p10, tieBias);
            List<BlockPos> lineV = LineGenerator.path(origin, p01, tieBias);
            for (BlockPos first : lineU) {
               observer.checkCancelled();
               long dx = (long)first.getX() - origin.getX();
               long dy = (long)first.getY() - origin.getY();
               long dz = (long)first.getZ() - origin.getZ();
               for (BlockPos second : lineV) {
                  raw.add(new BlockPos(
                     safeInt(dx + second.getX()),
                     safeInt(dy + second.getY()),
                     safeInt(dz + second.getZ())
                  ));
               }
            }
         }

         Model shell = new Model(frame, raw, outline, Set.of(), Map.of(), Map.of(), Set.of());
         Map<Pixel, NavigableSet<Integer>> rawColumns = columns(shell, raw);
         Map<Pixel, NavigableSet<Integer>> ownedColumns = columns(shell, outline);
         if (!forceAnalyticOnly) {
            domain.addAll(rawColumns.keySet());
            domain.addAll(ownedColumns.keySet());
         }
         domain = bridgeDiagonalContacts(domain, polygon);
         if (!forceAnalyticOnly) {
            domain = completeProjectedDomain(domain, maxBlocks, observer);
            if (domain == null) {
               return ModelBuild.failed(BresenhamFaceSweep.Status.LIMIT_EXCEEDED);
            }
         }
         if (requireConnectedDomain && !isFourConnected(domain)) {
            return ModelBuild.failed(BresenhamFaceSweep.Status.NO_VALID_CANDIDATE);
         }
         if (domain.size() > maxBlocks) {
            return ModelBuild.failed(BresenhamFaceSweep.Status.LIMIT_EXCEEDED);
         }
         long mandatoryBlocks = domain.size();
         for (NavigableSet<Integer> heights : ownedColumns.values()) {
            if (!heights.isEmpty()) {
               long ownedSpan = (long)heights.last() - heights.first() + 1L;
               mandatoryBlocks += Math.max(0L, ownedSpan - 1L);
            }
            if (mandatoryBlocks > maxBlocks) {
               return ModelBuild.failed(BresenhamFaceSweep.Status.LIMIT_EXCEEDED);
            }
         }

         Model geometry = new Model(frame, raw, outline, domain, Map.of(), ownedColumns, Set.of());
         int radiusLimit = safeInt(Math.max(width, height) + 2L);
         LinkedHashMap<Pixel, NavigableSet<Integer>> preferred = new LinkedHashMap<>();
         for (Pixel pixel : sorted(domain)) {
            observer.checkCancelled();
            NavigableSet<Integer> known = rawColumns.get(pixel);
            if (known != null && !known.isEmpty()) {
               preferred.put(pixel, new TreeSet<>(known));
            } else if (analyticOnly) {
               preferred.put(pixel, analyticPreference(geometry, pixel));
            } else {
               preferred.put(pixel, interpolate(geometry, pixel, rawColumns, radiusLimit));
            }
         }
         LinkedHashSet<Pixel> boundary = new LinkedHashSet<>();
         for (Pixel pixel : domain) {
            for (Pixel neighbor : neighbors(pixel)) {
               if (!domain.contains(neighbor)) {
                  boundary.add(pixel);
                  break;
               }
            }
         }
         return ModelBuild.success(
            new Model(
               frame,
               Collections.unmodifiableSet(raw),
               Collections.unmodifiableSet(outline),
               Collections.unmodifiableSet(domain),
               immutableColumns(preferred),
               immutableColumns(ownedColumns),
               Collections.unmodifiableSet(boundary)
            ),
            analyticOnly
         );
      }

      Pixel project(BlockPos position) {
         long[] delta = {
            (long)position.getX() - this.frame.anchor().getX(),
            (long)position.getY() - this.frame.anchor().getY(),
            (long)position.getZ() - this.frame.anchor().getZ()
         };
         return new Pixel(
            safeInt(delta[this.frame.order()[0]] * this.frame.signs()[0]),
            safeInt(delta[this.frame.order()[1]] * this.frame.signs()[1])
         );
      }

      int height(BlockPos position) {
         long[] delta = {
            (long)position.getX() - this.frame.anchor().getX(),
            (long)position.getY() - this.frame.anchor().getY(),
            (long)position.getZ() - this.frame.anchor().getZ()
         };
         return safeInt(delta[this.frame.order()[2]] * this.frame.signs()[2]);
      }

      BlockPos restore(Pixel pixel, int height) {
         return this.frame.restore(new ProjectedBresenhamFace.Int3(pixel.u(), pixel.v(), height));
      }

      double realHeight(Pixel pixel) {
         return -(this.frame.normal().x * pixel.u() + this.frame.normal().y * pixel.v())
            / this.frame.normal().z;
      }

      static boolean insideConvex(Pixel point, List<Pixel> polygon) {
         int sign = 0;
         for (int index = 0; index < polygon.size(); index++) {
            Pixel first = polygon.get(index);
            Pixel second = polygon.get((index + 1) % polygon.size());
            BigInteger value = difference(second.u(), first.u()).multiply(difference(point.v(), first.v()))
               .subtract(difference(second.v(), first.v()).multiply(difference(point.u(), first.u())));
            if (value.signum() == 0) {
               continue;
            }
            int current = value.signum();
            if (sign != 0 && current != sign) {
               return false;
            }
            sign = current;
         }
         return true;
      }

      /** Enumerates a lattice parallelogram in time proportional to its lattice-point count. */
      static LinkedHashSet<Pixel> scanConvexDomain(
         List<Pixel> polygon,
         int maxBlocks,
         BlockGenerationObserver observer
      ) {
         if (polygon.size() != 4 || maxBlocks <= 0) {
            return null;
         }
         BigPoint origin = BigPoint.of(polygon.get(0));
         BigPoint p10 = BigPoint.of(polygon.get(1));
         BigPoint p11 = BigPoint.of(polygon.get(2));
         BigPoint p01 = BigPoint.of(polygon.get(3));
         if (!origin.add(p11).equals(p10.add(p01))) {
            return null;
         }
         BigPoint edgeU = p10.subtract(origin);
         BigPoint edgeV = p01.subtract(origin);
         BigInteger determinant = edgeU.cross(edgeV);
         if (determinant.signum() == 0) {
            return null;
         }
         if (determinant.signum() < 0) {
            BigPoint swap = edgeU;
            edgeU = edgeV;
            edgeV = swap;
            determinant = determinant.negate();
         }

         BigInteger edgeUGcd = edgeU.coordinateGcd();
         BigInteger edgeVGcd = edgeV.coordinateGcd();
         BigInteger exactCount = determinant.add(edgeUGcd).add(edgeVGcd).add(BigInteger.ONE);
         if (exactCount.compareTo(BigInteger.valueOf(maxBlocks)) > 0) {
            return null;
         }

         BigInteger rowModulus = edgeU.v().abs().gcd(edgeV.v().abs());
         if (rowModulus.signum() == 0) {
            return null;
         }
         BigInteger columnModulus = determinant.divide(rowModulus);
         int rowResidues = rowModulus.intValueExact();
         int columnResidues = columnModulus.intValueExact();
         LinkedHashSet<Pixel> result = new LinkedHashSet<>();
         int visited = 0;
         for (int v = 0; v < rowResidues; v++) {
            for (int u = 0; u < columnResidues; u++) {
               if ((visited++ & 1023) == 0) {
                  observer.checkCancelled();
               }
               BigPoint residue = new BigPoint(BigInteger.valueOf(u), BigInteger.valueOf(v));
               BigInteger uFloor = floorDivide(residue.cross(edgeV), determinant);
               BigInteger vFloor = floorDivide(edgeU.cross(residue), determinant);
               BigPoint local = residue.subtract(edgeU.scale(uFloor)).subtract(edgeV.scale(vFloor));
               result.add(origin.add(local).toPixel());
            }
         }

         addLatticeSegment(result, origin, p10, observer);
         addLatticeSegment(result, p10, p11, observer);
         addLatticeSegment(result, p11, p01, observer);
         addLatticeSegment(result, p01, origin, observer);
         if (result.size() != exactCount.intValueExact()) {
            throw new IllegalStateException("lattice parallelogram enumeration count mismatch");
         }
         return result;
      }

      private static LinkedHashSet<Pixel> bridgeDiagonalContacts(
         Set<Pixel> source,
         List<Pixel> polygon
      ) {
         LinkedHashSet<Pixel> snapshot = new LinkedHashSet<>(source);
         LinkedHashSet<Pixel> result = new LinkedHashSet<>(snapshot);
         BigInteger centerU = polygon.stream()
            .map(pixel -> BigInteger.valueOf(pixel.u()))
            .reduce(BigInteger.ZERO, BigInteger::add);
         BigInteger centerV = polygon.stream()
            .map(pixel -> BigInteger.valueOf(pixel.v()))
            .reduce(BigInteger.ZERO, BigInteger::add);
         BigInteger scale = BigInteger.valueOf(polygon.size());
         for (Pixel pixel : sorted(snapshot)) {
            for (Pixel step : List.of(new Pixel(1, 1), new Pixel(1, -1))) {
               Pixel diagonal = new Pixel(pixel.u() + step.u(), pixel.v() + step.v());
               if (!snapshot.contains(diagonal)) {
                  continue;
               }
               Pixel first = new Pixel(pixel.u() + step.u(), pixel.v());
               Pixel second = new Pixel(pixel.u(), pixel.v() + step.v());
               if (snapshot.contains(first) || snapshot.contains(second)) {
                  continue;
               }
               BigInteger firstDistance = centerDistance(first, scale, centerU, centerV);
               BigInteger secondDistance = centerDistance(second, scale, centerU, centerV);
               if (firstDistance.compareTo(secondDistance) <= 0) {
                  result.add(first);
               }
               if (secondDistance.compareTo(firstDistance) <= 0) {
                  result.add(second);
               }
            }
         }
         return result;
      }

      private static BigInteger centerDistance(
         Pixel pixel,
         BigInteger scale,
         BigInteger centerU,
         BigInteger centerV
      ) {
         BigInteger du = scale.multiply(BigInteger.valueOf(pixel.u())).subtract(centerU);
         BigInteger dv = scale.multiply(BigInteger.valueOf(pixel.v())).subtract(centerV);
         return du.multiply(du).add(dv.multiply(dv));
      }

      static LinkedHashSet<Pixel> completeProjectedDomain(
         Set<Pixel> source,
         int maxBlocks,
         BlockGenerationObserver observer
      ) {
         if (source.isEmpty()) {
            return new LinkedHashSet<>();
         }
         if (source.size() > maxBlocks) {
            return null;
         }
         int minimumU = source.stream().mapToInt(Pixel::u).min().orElseThrow();
         int maximumU = source.stream().mapToInt(Pixel::u).max().orElseThrow();
         long lowU = (long)minimumU - 1L;
         long highU = (long)maximumU + 1L;
         TreeMap<Integer, ArrayList<Integer>> occupiedRows = new TreeMap<>();
         int visited = 0;
         for (Pixel pixel : source) {
            if ((visited++ & 1023) == 0) {
               observer.checkCancelled();
            }
            occupiedRows.computeIfAbsent(pixel.v(), ignored -> new ArrayList<>()).add(pixel.u());
         }

         ArrayList<MissingRun> runs = new ArrayList<>();
         TreeMap<Integer, List<Integer>> runsByRow = new TreeMap<>();
         visited = 0;
         for (Map.Entry<Integer, ArrayList<Integer>> entry : occupiedRows.entrySet()) {
            if ((visited++ & 1023) == 0) {
               observer.checkCancelled();
            }
            int row = entry.getKey();
            ArrayList<Integer> occupied = entry.getValue();
            occupied.sort(Integer::compare);
            Integer previousRow = occupiedRows.lowerKey(row);
            Integer nextRow = occupiedRows.higherKey(row);
            boolean openVertically = previousRow == null
               || (long)row - previousRow != 1L
               || nextRow == null
               || (long)nextRow - row != 1L;
            ArrayList<Integer> rowRuns = new ArrayList<>();
            long cursor = lowU;
            for (int coordinate : occupied) {
               if (cursor < coordinate) {
                  addMissingRun(runs, rowRuns, row, cursor, (long)coordinate - 1L, lowU, highU, openVertically);
               }
               cursor = (long)coordinate + 1L;
            }
            if (cursor <= highU) {
               addMissingRun(runs, rowRuns, row, cursor, highU, lowU, highU, openVertically);
            }
            runsByRow.put(row, List.copyOf(rowRuns));
         }

         ExteriorUnionFind components = new ExteriorUnionFind(runs.size());
         Map.Entry<Integer, List<Integer>> previous = null;
         for (Map.Entry<Integer, List<Integer>> current : runsByRow.entrySet()) {
            if (previous != null && (long)current.getKey() - previous.getKey() == 1L) {
               unionOverlappingRuns(runs, components, previous.getValue(), current.getValue(), observer);
            }
            previous = current;
         }
         for (int index = 0; index < runs.size(); index++) {
            if ((index & 1023) == 0) {
               observer.checkCancelled();
            }
            if (runs.get(index).exterior()) {
               components.markExterior(index);
            }
         }

         long required = source.size();
         for (int index = 0; index < runs.size(); index++) {
            if ((index & 1023) == 0) {
               observer.checkCancelled();
            }
            if (!components.isExterior(index)) {
               MissingRun run = runs.get(index);
               long length = run.maximumU() - run.minimumU() + 1L;
               if (length > (long)maxBlocks - required) {
                  return null;
               }
               required += length;
            }
         }

         LinkedHashSet<Pixel> result = new LinkedHashSet<>();
         visited = 0;
         for (Pixel pixel : source) {
            if ((visited++ & 1023) == 0) {
               observer.checkCancelled();
            }
            result.add(pixel);
         }
         visited = 0;
         for (int index = 0; index < runs.size(); index++) {
            if (components.isExterior(index)) {
               continue;
            }
            MissingRun run = runs.get(index);
            for (long u = run.minimumU(); u <= run.maximumU(); u++) {
               if ((visited++ & 1023) == 0) {
                  observer.checkCancelled();
               }
               result.add(new Pixel(Math.toIntExact(u), run.v()));
            }
         }
         return result;
      }

      private static boolean isFourConnected(Set<Pixel> source) {
         if (source.isEmpty()) {
            return true;
         }
         HashSet<Pixel> unseen = new HashSet<>(source);
         ArrayDeque<Pixel> open = new ArrayDeque<>();
         Pixel first = unseen.iterator().next();
         unseen.remove(first);
         open.add(first);
         while (!open.isEmpty()) {
            Pixel pixel = open.removeFirst();
            for (Pixel neighbor : neighbors(pixel)) {
               if (unseen.remove(neighbor)) {
                  open.addLast(neighbor);
               }
            }
         }
         return unseen.isEmpty();
      }

      private static NavigableSet<Integer> analyticPreference(Model model, Pixel pixel) {
         double target = model.realHeight(pixel);
         int lower = safeInt((long)Math.floor(target));
         TreeSet<Integer> result = new TreeSet<>();
         result.add(lower);
         if (Math.abs(target - lower) > 1.0E-9) {
            result.add(safeInt((long)lower + 1L));
         }
         return result;
      }

      private static NavigableSet<Integer> interpolate(
         Model model,
         Pixel pixel,
         Map<Pixel, NavigableSet<Integer>> known,
         int radiusLimit
      ) {
         ArrayList<Ray> rays = new ArrayList<>();
         for (Pixel direction : neighbors(new Pixel(0, 0))) {
            for (int radius = 1; radius <= radiusLimit; radius++) {
               Pixel sample = new Pixel(
                  safeInt((long)pixel.u() + (long)direction.u() * radius),
                  safeInt((long)pixel.v() + (long)direction.v() * radius)
               );
               NavigableSet<Integer> heights = known.get(sample);
               if (heights != null && !heights.isEmpty()) {
                  double phase = heights.stream().mapToDouble(value -> value - model.realHeight(sample)).average().orElse(0.0);
                  rays.add(new Ray(direction, radius, phase));
                  break;
               }
            }
         }
         double phase = 0.0;
         ArrayList<Double> axes = new ArrayList<>(2);
         addAxisEstimate(axes, rays, new Pixel(-1, 0), new Pixel(1, 0));
         addAxisEstimate(axes, rays, new Pixel(0, -1), new Pixel(0, 1));
         if (!axes.isEmpty()) {
            phase = axes.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
         } else if (!rays.isEmpty()) {
            double weighted = 0.0;
            double weights = 0.0;
            for (Ray ray : rays) {
               double weight = 1.0 / ray.radius();
               weighted += weight * ray.phase();
               weights += weight;
            }
            phase = weighted / weights;
         }
         double target = model.realHeight(pixel) + phase;
         int lower = safeInt((long)Math.floor(target));
         TreeSet<Integer> result = new TreeSet<>();
         result.add(lower);
         if (Math.abs(target - lower) > 1.0E-9) {
            result.add(safeInt((long)lower + 1L));
         }
         return result;
      }

      private static void addAxisEstimate(
         List<Double> output,
         List<Ray> rays,
         Pixel negative,
         Pixel positive
      ) {
         Ray first = rays.stream().filter(ray -> ray.direction().equals(negative)).findFirst().orElse(null);
         Ray second = rays.stream().filter(ray -> ray.direction().equals(positive)).findFirst().orElse(null);
         if (first != null && second != null) {
            output.add(
               (second.radius() * first.phase() + first.radius() * second.phase())
                  / (first.radius() + second.radius())
            );
         }
      }

      private static Map<Pixel, NavigableSet<Integer>> immutableColumns(
         Map<Pixel, NavigableSet<Integer>> source
      ) {
         LinkedHashMap<Pixel, NavigableSet<Integer>> result = new LinkedHashMap<>();
         source.forEach((pixel, heights) -> result.put(
            pixel,
            Collections.unmodifiableNavigableSet(new TreeSet<>(heights))
         ));
         return Collections.unmodifiableMap(result);
      }
   }

}
