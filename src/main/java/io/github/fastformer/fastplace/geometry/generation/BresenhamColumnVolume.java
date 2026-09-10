package io.github.fastformer.fastplace.geometry.generation;

import io.github.fastformer.fastplace.FaceRasterizationMode;
import java.math.BigInteger;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Fills convex columns around six independently rasterized faces and twelve shared true edges. */
final class BresenhamColumnVolume {
   private static final long FACE_WORK_CAP = 4_000_000L;
   private static final long CANDIDATE_COLUMN_WORK_CAP = 1_000_000L;
   private static final int[][] SIX_NEIGHBORS = {
      {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
   };

   private BresenhamColumnVolume() {
   }

   static Result generate(
      List<Vec3> base,
      Vec3 extrusion,
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      return generate(base, extrusion, maxBlocks, observer, LineTieBias.DEFAULT);
   }

   static Result generate(
      List<Vec3> base,
      Vec3 extrusion,
      int maxBlocks,
      BlockGenerationObserver observer,
      LineTieBias tieBias
   ) {
      return generate(
         base, extrusion, maxBlocks, observer, tieBias, FaceRasterizationMode.POINT_SWEEP
      );
   }

   static Result generate(
      List<Vec3> base,
      Vec3 extrusion,
      int maxBlocks,
      BlockGenerationObserver observer,
      LineTieBias tieBias,
      FaceRasterizationMode rasterizationMode
   ) {
      return generate(base, extrusion, maxBlocks, observer, tieBias, rasterizationMode, false, false, false);
   }

   static Result generateBoundary(
      List<Vec3> base,
      Vec3 extrusion,
      int maxBlocks,
      BlockGenerationObserver observer,
      LineTieBias tieBias
   ) {
      return generateBoundary(
         base, extrusion, maxBlocks, observer, tieBias, FaceRasterizationMode.POINT_SWEEP
      );
   }

   static Result generateBoundary(
      List<Vec3> base,
      Vec3 extrusion,
      int maxBlocks,
      BlockGenerationObserver observer,
      LineTieBias tieBias,
      FaceRasterizationMode rasterizationMode
   ) {
      return generate(base, extrusion, maxBlocks, observer, tieBias, rasterizationMode, false, true, false);
   }

   static Result forceAffineFallbackForTesting(
      List<Vec3> base,
      Vec3 extrusion,
      int maxBlocks,
      BlockGenerationObserver observer,
      LineTieBias tieBias
   ) {
      return generate(
         base, extrusion, maxBlocks, observer, tieBias, FaceRasterizationMode.POINT_SWEEP,
         true, false, false
      );
   }

   static Result forceAffineFallbackBoundaryForTesting(
      List<Vec3> base,
      Vec3 extrusion,
      int maxBlocks,
      BlockGenerationObserver observer,
      LineTieBias tieBias
   ) {
      return generate(
         base, extrusion, maxBlocks, observer, tieBias, FaceRasterizationMode.POINT_SWEEP,
         true, true, false
      );
   }

   static Result forceFaceCandidateFailureForTesting(
      List<Vec3> base,
      Vec3 extrusion,
      int maxBlocks,
      BlockGenerationObserver observer,
      LineTieBias tieBias
   ) {
      return generate(
         base, extrusion, maxBlocks, observer, tieBias, FaceRasterizationMode.POINT_SWEEP,
         false, false, true
      );
   }

   private static Result generate(
      List<Vec3> base,
      Vec3 extrusion,
      int maxBlocks,
      BlockGenerationObserver observer,
      LineTieBias tieBias,
      FaceRasterizationMode rasterizationMode,
      boolean forceAffineFallback,
      boolean boundaryOnly,
      boolean forceFaceCandidateFailure
   ) {
      if (base == null || base.size() != 4 || extrusion == null || maxBlocks <= 0) {
         return Result.failed();
      }

      BlockGenerationObserver staging = stagingObserver(observer);
      ProjectedBresenhamFace.Frame frame = ProjectedBresenhamFace.Frame.create(base);
      if (frame == null) {
         return Result.failed();
      }
      List<Vec3> top = base.stream().map(vertex -> vertex.add(extrusion)).toList();
      List<Vec3> hullVertices = new ArrayList<>(base.size() + top.size());
      hullVertices.addAll(base);
      hullVertices.addAll(top);
      List<List<Vec3>> faces = boxFaces(base, top);
      int faceBudget = boundaryOnly ? (int)FACE_WORK_CAP : maxBlocks;
      boolean faceWorkCapped = faceWorkExceedsCap(faces, faceBudget);
      ArrayList<Integer> boundedCandidateAxes = new ArrayList<>(3);
      for (int columnAxis : candidateAxes(BlockPos.containing(extrusion))) {
         long projectedColumns = ArbitraryConvexPolyhedronGenerator.estimateSolidSpanScanColumns(
            hullVertices,
            columnAxis,
            CANDIDATE_COLUMN_WORK_CAP,
            staging
         );
         if (projectedColumns <= CANDIDATE_COLUMN_WORK_CAP) {
            boundedCandidateAxes.add(columnAxis);
         }
      }
      if (boundedCandidateAxes.isEmpty()) {
         return limitResult(maxBlocks, staging);
      }

      int intermediateLimit = faceBudget;
      FaceShellResult faceShell = faceWorkCapped
         ? FaceShellResult.failed(BresenhamFaceSweep.Status.LIMIT_EXCEEDED)
         : independentFaceShell(
            faces,
            intermediateLimit,
            staging,
            tieBias,
            rasterizationMode,
            forceFaceCandidateFailure
         );
      if (faceShell.status() == BresenhamFaceSweep.Status.LIMIT_EXCEEDED) {
         return limitResult(maxBlocks, staging);
      }
      if (faceShell.status() != BresenhamFaceSweep.Status.SUCCESS) {
         return Result.faceConstraintsFailed();
      }
      Set<BlockPos> formalShell = faceShell.blocks();
      if (formalShell.isEmpty()) {
         return Result.failed();
      }

      ColumnAccumulator bestSafeCandidate = null;
      int bestAxisRank = Integer.MAX_VALUE;
      if (!forceAffineFallback) {
         for (int columnAxis : boundedCandidateAxes) {
            ColumnAccumulator candidate = generateCandidate(
               formalShell,
               hullVertices,
               columnAxis,
               staging
            );
            if (candidate == null) {
               continue;
            }
            if (candidate.fiveNeighborOpenings() == 0L && !candidate.hasEnclosedAir()) {
               int axisRank = axisRank(frame, columnAxis);
               if (bestSafeCandidate == null
                  || candidate.blockCount() < bestSafeCandidate.blockCount()
                  || candidate.blockCount() == bestSafeCandidate.blockCount()
                     && axisRank < bestAxisRank) {
                  bestSafeCandidate = candidate;
                  bestAxisRank = axisRank;
               }
            }
         }
         if (bestSafeCandidate != null) {
            Result staged = boundaryOnly
               ? bestSafeCandidate.boundaryResult(maxBlocks, staging)
               : bestSafeCandidate.solidResult(maxBlocks, staging);
            return staged.complete() ? publish(staged, observer) : staged;
         }
      }
      return Result.faceConstraintsFailed();
   }

   private static boolean faceWorkExceedsCap(List<List<Vec3>> faces, int maxBlocks) {
      long totalWork = 0L;
      for (List<Vec3> face : faces) {
         ProjectedBresenhamFace.Frame frame = ProjectedBresenhamFace.Frame.create(face);
         if (frame == null) {
            return true;
         }
         List<ProjectedBresenhamFace.Int3> vertices = frame.vertices();
         BigInteger domain = projectedDomain(vertices);
         if (domain == null || domain.compareTo(BigInteger.valueOf(maxBlocks)) > 0) {
            return true;
         }
         long faceWork = faceWork(frame, vertices);
         totalWork = GenerationMath.saturatedAdd(totalWork, faceWork);
         if (faceWork > FACE_WORK_CAP || totalWork > FACE_WORK_CAP) {
            return true;
         }
      }
      return false;
   }

   private static long faceWork(
      ProjectedBresenhamFace.Frame frame,
      List<ProjectedBresenhamFace.Int3> vertices
   ) {
      if (vertices.size() != 4) {
         return Long.MAX_VALUE;
      }
      ProjectedBresenhamFace.Int3 p00 = vertices.get(0);
      ProjectedBresenhamFace.Int3 p10 = vertices.get(1);
      ProjectedBresenhamFace.Int3 p11 = vertices.get(2);
      ProjectedBresenhamFace.Int3 p01 = vertices.get(3);
      if ((long)p00.x() + p11.x() != (long)p10.x() + p01.x()
         || (long)p00.y() + p11.y() != (long)p10.y() + p01.y()
         || (long)p00.z() + p11.z() != (long)p10.z() + p01.z()) {
         return Long.MAX_VALUE;
      }

      BigInteger domain = projectedDomain(vertices);
      if (domain == null) {
         return Long.MAX_VALUE;
      }
      if (domain.compareTo(BigInteger.valueOf(FACE_WORK_CAP)) > 0) {
         return Long.MAX_VALUE;
      }

      List<BlockPos> world = vertices.stream().map(frame::restore).toList();
      long lineU = LineGenerator.estimateBlocks(world.get(0), world.get(1));
      long lineV = LineGenerator.estimateBlocks(world.get(0), world.get(3));
      long work = domain.longValueExact();
      work = GenerationMath.saturatedAdd(work, GenerationMath.saturatedMultiply(lineU, lineV));
      for (int index = 0; index < 4; index++) {
         work = GenerationMath.saturatedAdd(
            work,
            LineGenerator.estimateBlocks(world.get(index), world.get((index + 1) % 4))
         );
      }
      return work;
   }

   private static BigInteger projectedDomain(List<ProjectedBresenhamFace.Int3> vertices) {
      if (vertices.size() != 4) {
         return null;
      }
      ProjectedBresenhamFace.Int3 p00 = vertices.get(0);
      ProjectedBresenhamFace.Int3 p10 = vertices.get(1);
      ProjectedBresenhamFace.Int3 p01 = vertices.get(3);
      BigInteger ux = difference(p10.x(), p00.x());
      BigInteger uy = difference(p10.y(), p00.y());
      BigInteger vx = difference(p01.x(), p00.x());
      BigInteger vy = difference(p01.y(), p00.y());
      BigInteger determinant = ux.multiply(vy).subtract(uy.multiply(vx)).abs();
      if (determinant.signum() == 0) {
         return null;
      }
      return determinant
         .add(ux.abs().gcd(uy.abs()))
         .add(vx.abs().gcd(vy.abs()))
         .add(BigInteger.ONE);
   }

   private static BigInteger difference(int first, int second) {
      return BigInteger.valueOf((long)first - second);
   }

   private static List<List<Vec3>> boxFaces(List<Vec3> base, List<Vec3> top) {
      ArrayList<List<Vec3>> result = new ArrayList<>(6);
      result.add(List.copyOf(base));
      result.add(List.copyOf(top));
      for (int index = 0; index < 4; index++) {
         int next = (index + 1) % 4;
         result.add(List.of(base.get(index), base.get(next), top.get(next), top.get(index)));
      }
      return List.copyOf(result);
   }

   private static FaceShellResult independentFaceShell(
      List<List<Vec3>> faces,
      int maxBlocks,
      BlockGenerationObserver observer,
      LineTieBias tieBias,
      FaceRasterizationMode rasterizationMode,
      boolean forceCandidateFailure
   ) {
      return independentFaceShellPass(
         faces,
         maxBlocks,
         observer,
         tieBias,
         rasterizationMode,
         forceCandidateFailure
      );
   }

   private static FaceShellResult independentFaceShellPass(
      List<List<Vec3>> faces,
      int maxBlocks,
      BlockGenerationObserver observer,
      LineTieBias tieBias,
      FaceRasterizationMode rasterizationMode,
      boolean forceCandidateFailure
   ) {
      LinkedHashSet<BlockPos> shell = new LinkedHashSet<>();
      for (List<Vec3> face : faces) {
         observer.checkCancelled();
         ProjectedBresenhamFace.Frame faceFrame = ProjectedBresenhamFace.Frame.create(face);
         if (faceFrame == null) {
            return FaceShellResult.failed(BresenhamFaceSweep.Status.NO_VALID_CANDIDATE);
         }
         BresenhamFaceSweep.Attempt attempt = forceCandidateFailure
            ? BresenhamFaceSweep.Attempt.failed(BresenhamFaceSweep.Status.NO_VALID_CANDIDATE)
            : BoundaryInterpolatedFaceRasterizer.attempt(
               faceFrame, tieBias, maxBlocks, observer, rasterizationMode
            );
         Set<BlockPos> fill;
         Set<BlockPos> outline;
         if (attempt.succeeded()) {
            fill = attempt.result().fill();
            outline = attempt.result().outline();
         } else {
            return FaceShellResult.failed(attempt.status());
         }
         for (BlockPos block : clipToOwnedBoundary(faceFrame, fill, outline)) {
            if (!shell.contains(block) && shell.size() >= maxBlocks) {
               return FaceShellResult.failed(BresenhamFaceSweep.Status.LIMIT_EXCEEDED);
            }
            shell.add(block.immutable());
         }
      }
      return new FaceShellResult(
         Collections.unmodifiableSet(shell),
         BresenhamFaceSweep.Status.SUCCESS
      );
   }

   private static Set<BlockPos> clipToOwnedBoundary(
      ProjectedBresenhamFace.Frame frame,
      Set<BlockPos> fill,
      Set<BlockPos> outline
   ) {
      HashSet<FaceColumn> boundaryColumns = new HashSet<>();
      outline.forEach(block -> boundaryColumns.add(faceColumn(frame, block)));
      LinkedHashSet<BlockPos> result = new LinkedHashSet<>();
      for (BlockPos block : fill) {
         if (outline.contains(block) || !boundaryColumns.contains(faceColumn(frame, block))) {
            result.add(block);
         }
      }
      return result;
   }

   private static FaceColumn faceColumn(ProjectedBresenhamFace.Frame frame, BlockPos block) {
      long[] delta = {
         (long)block.getX() - frame.anchor().getX(),
         (long)block.getY() - frame.anchor().getY(),
         (long)block.getZ() - frame.anchor().getZ()
      };
      int[] order = frame.order();
      int[] signs = frame.signs();
      return new FaceColumn(
         Math.toIntExact(delta[order[0]] * signs[0]),
         Math.toIntExact(delta[order[1]] * signs[1])
      );
   }

   private static int[] candidateAxes(BlockPos extrusion) {
      int nonZero = 0;
      int onlyAxis = -1;
      for (int axis = 0; axis < 3; axis++) {
         if (coordinate(extrusion, axis) != 0) {
            nonZero++;
            onlyAxis = axis;
         }
      }
      return nonZero == 1
         ? new int[]{onlyAxis}
         : LineGenerator.axesByDescendingSlope(BlockPos.ZERO, extrusion);
   }

   private static int axisRank(ProjectedBresenhamFace.Frame frame, int worldAxis) {
      int[] order = frame.order();
      for (int rank = 0; rank < order.length; rank++) {
         if (order[rank] == worldAxis) {
            return rank;
         }
      }
      throw new IllegalArgumentException("axis " + worldAxis);
   }

   private static ColumnAccumulator generateCandidate(
      Set<BlockPos> shell,
      List<Vec3> hullVertices,
      int columnAxis,
      BlockGenerationObserver observer
   ) {
      ColumnAccumulator accumulator = new ColumnAccumulator(columnAxis, observer);
      boolean targetComplete = ArbitraryConvexPolyhedronGenerator.visitSolidSpans(
         hullVertices,
         columnAxis,
         accumulator::addTargetSpan,
         observer
      );
      if (!targetComplete) {
         return null;
      }
      if (accumulator.isEmpty()) {
         return null;
      }
      for (BlockPos seed : shell) {
         accumulator.addShell(seed);
      }
      accumulator.fillUnitOverlayIntervals();
      return accumulator;
   }

   private static int coordinate(BlockPos position, int axis) {
      return switch (axis) {
         case 0 -> position.getX();
         case 1 -> position.getY();
         case 2 -> position.getZ();
         default -> throw new IllegalArgumentException("axis " + axis);
      };
   }

   private static BlockPos safeNeighbor(BlockPos position, int[] step) {
      long x = (long)position.getX() + step[0];
      long y = (long)position.getY() + step[1];
      long z = (long)position.getZ() + step[2];
      if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE
         || y < Integer.MIN_VALUE || y > Integer.MAX_VALUE
         || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) {
         return null;
      }
      return new BlockPos((int)x, (int)y, (int)z);
   }

   private static Result publish(Result staged, BlockGenerationObserver observer) {
      BlockGenerationObserver actualObserver = observer == null ? BlockGenerationObserver.NONE : observer;
      for (BlockPos position : staged.blocks()) {
         actualObserver.checkCancelled();
         actualObserver.onGenerated(position);
      }
      return new Result(
         GeneratedBlockSets.readOnly(staged.blocks()),
         true,
         staged.shell()
      );
   }

   /**
    * The public Set API uses an exact-size witness for one-past-placement-limit
    * rejection.  Sentinel construction is deterministic, bounded by the same
    * budget, cancellable, and never emits generated-block callbacks.
    */
   private static Result limitResult(
      int maxBlocks,
      BlockGenerationObserver observer
   ) {
      return new Result(GenerationLimitExceeded.witness(maxBlocks, observer), false, Set.of());
   }

   private static BlockGenerationObserver stagingObserver(BlockGenerationObserver observer) {
      BlockGenerationObserver delegate = observer == null ? BlockGenerationObserver.NONE : observer;
      return new BlockGenerationObserver() {
         @Override
         public void onScanned(long amount) {
            delegate.onScanned(amount);
         }

         @Override
         public void checkCancelled() {
            delegate.checkCancelled();
         }
      };
   }

   record Result(Set<BlockPos> blocks, boolean complete, Set<BlockPos> shell) {
      private static Result failed() {
         return new Result(Set.of(), false, Set.of());
      }

      private static Result faceConstraintsFailed() {
         return new Result(GenerationFailed.faceConstraints(), false, Set.of());
      }
   }

   private record FaceShellResult(Set<BlockPos> blocks, BresenhamFaceSweep.Status status) {
      static FaceShellResult failed(BresenhamFaceSweep.Status status) {
         return new FaceShellResult(Set.of(), status);
      }
   }

   private record FaceColumn(int u, int v) {
   }

   private record ColumnKey(int first, int second) {
   }

   private record Span(int minimum, int maximum) {
      Span include(int coordinate) {
         return new Span(Math.min(this.minimum, coordinate), Math.max(this.maximum, coordinate));
      }

      long length() {
         return (long)this.maximum - this.minimum + 1L;
      }

      boolean contains(long coordinate) {
         return coordinate >= this.minimum && coordinate <= this.maximum;
      }
   }

   static Set<BlockPos> boundaryFromSpansForTesting(Set<BlockPos> solid, int axis) {
      ColumnAccumulator accumulator = columnAccumulatorForTesting(solid, axis);
      Result result = accumulator.boundaryResult(Integer.MAX_VALUE, BlockGenerationObserver.NONE);
      if (!result.complete()) {
         throw new IllegalStateException("test boundary exceeded the integer output limit");
      }
      return result.blocks();
   }

   static long fiveNeighborOpeningsFromSpansForTesting(Set<BlockPos> solid, int axis) {
      return columnAccumulatorForTesting(solid, axis).fiveNeighborOpenings();
   }

   static Set<BlockPos> solidFromSpansForTesting(Set<BlockPos> solid, int axis) {
      Result result = columnAccumulatorForTesting(solid, axis)
         .solidResult(Integer.MAX_VALUE, BlockGenerationObserver.NONE);
      if (!result.complete()) {
         throw new IllegalStateException("test solid exceeded the integer output limit");
      }
      return result.blocks();
   }

   static boolean usesLazyColumnStorageForTesting(Set<BlockPos> blocks) {
      return blocks instanceof ColumnBlockSet || blocks instanceof BoundaryColumnBlockSet;
   }

   static boolean hasEnclosedAirFromColumnPartsForTesting(
      Set<BlockPos> core,
      Set<BlockPos> overlays,
      int axis
   ) {
      ColumnAccumulator accumulator = new ColumnAccumulator(axis, BlockGenerationObserver.NONE);
      core.forEach(accumulator::addTarget);
      overlays.forEach(accumulator::addShell);
      return accumulator.hasEnclosedAir();
   }

   private static ColumnAccumulator columnAccumulatorForTesting(Set<BlockPos> solid, int axis) {
      ColumnAccumulator accumulator = new ColumnAccumulator(axis, BlockGenerationObserver.NONE);
      for (BlockPos position : solid) {
         accumulator.addTarget(position);
      }
      if (accumulator.blockCount() != solid.size()) {
         throw new IllegalArgumentException("the supplied test solid is not column-convex on axis " + axis);
      }
      return accumulator;
   }

   private static final class ColumnAccumulator {
      private final int axis;
      private final int firstKeyAxis;
      private final int secondKeyAxis;
      private final BlockGenerationObserver observer;
      private final LinkedHashSet<BlockPos> shell = new LinkedHashSet<>();
      private final LinkedHashSet<BlockPos> overlays = new LinkedHashSet<>();
      private final Map<ColumnKey, Span> spans = new LinkedHashMap<>();
      private long blockCount;
      private long targetColumnCount;

      ColumnAccumulator(int axis, BlockGenerationObserver observer) {
         this.axis = axis;
         this.firstKeyAxis = (axis + 1) % 3;
         this.secondKeyAxis = (axis + 2) % 3;
         this.observer = observer == null ? BlockGenerationObserver.NONE : observer;
      }

      void addShell(BlockPos seed) {
         this.observer.checkCancelled();
         this.observer.onScanned(1L);
         BlockPos immutable = seed.immutable();
         this.shell.add(immutable);
         ColumnKey key = this.key(immutable);
         int coordinate = coordinate(immutable, this.axis);
         Span target = this.spans.get(key);
         if (target == null) {
            this.overlays.add(immutable);
            return;
         }
         long added = coordinate < target.minimum()
            ? (long)target.minimum() - coordinate
            : coordinate > target.maximum()
               ? (long)coordinate - target.maximum()
               : 0L;
         if (added > 0L) {
            this.spans.put(key, target.include(coordinate));
            this.addCount(added);
            if (added > 1L) {
               this.observer.onScanned(added - 1L);
            }
         }
      }

      void fillUnitOverlayIntervals() {
         Map<ColumnKey, java.util.TreeSet<Integer>> coordinates = new LinkedHashMap<>();
         for (BlockPos overlay : this.overlays) {
            coordinates.computeIfAbsent(this.key(overlay), ignored -> new java.util.TreeSet<>())
               .add(coordinate(overlay, this.axis));
         }
         ArrayList<BlockPos> additions = new ArrayList<>();
         for (Map.Entry<ColumnKey, java.util.TreeSet<Integer>> entry : coordinates.entrySet()) {
            Integer previous = null;
            for (int current : entry.getValue()) {
               if (previous != null && (long)current - previous == 2L) {
                  additions.add(this.position(entry.getKey(), previous + 1));
               }
               previous = current;
            }
         }
         for (BlockPos addition : additions) {
            this.observer.checkCancelled();
            this.observer.onScanned(1L);
            this.overlays.add(addition.immutable());
         }
      }

      boolean addTarget(BlockPos seed) {
         this.addSeed(seed, false);
         return true;
      }

      boolean addTargetSpan(int first, int second, int minimum, int maximum) {
         this.observer.checkCancelled();
         if (minimum > maximum) {
            return true;
         }
         if (this.targetColumnCount >= CANDIDATE_COLUMN_WORK_CAP) {
            return false;
         }
         this.targetColumnCount++;
         long scanned = (long)maximum - minimum + 1L;
         this.observer.onScanned(scanned);
         ColumnKey key = new ColumnKey(first, second);
         Span previous = this.spans.get(key);
         if (previous == null) {
            this.spans.put(key, new Span(minimum, maximum));
            this.addCount(scanned);
            return true;
         }
         Span merged = new Span(
            Math.min(previous.minimum(), minimum),
            Math.max(previous.maximum(), maximum)
         );
         long added = merged.length() - previous.length();
         if (added > 0L) {
            this.spans.put(key, merged);
            this.addCount(added);
         }
         return true;
      }

      boolean isEmpty() {
         return this.spans.isEmpty();
      }

      long blockCount() {
         return saturatedAdd(this.blockCount, this.overlays.size());
      }

      private void addSeed(BlockPos seed, boolean ownedByShell) {
         this.observer.checkCancelled();
         this.observer.onScanned(1L);
         BlockPos immutable = seed.immutable();
         if (ownedByShell) {
            this.shell.add(immutable);
         }

         ColumnKey key = this.key(immutable);
         int coordinate = coordinate(immutable, this.axis);
         Span previous = this.spans.get(key);
         if (previous == null) {
            this.spans.put(key, new Span(coordinate, coordinate));
            this.addCount(1L);
            return;
         }
         long added = coordinate < previous.minimum()
            ? (long)previous.minimum() - coordinate
            : coordinate > previous.maximum()
               ? (long)coordinate - previous.maximum()
               : 0L;
         if (added > 0L) {
            this.spans.put(key, previous.include(coordinate));
            this.addCount(added);
            if (added > 1L) {
               this.observer.onScanned(added - 1L);
            }
         }
      }

      private ColumnKey key(BlockPos position) {
         return new ColumnKey(
            coordinate(position, this.firstKeyAxis),
            coordinate(position, this.secondKeyAxis)
         );
      }

      private BlockPos position(ColumnKey key, int coordinate) {
         int[] values = new int[3];
         values[this.axis] = coordinate;
         values[this.firstKeyAxis] = key.first();
         values[this.secondKeyAxis] = key.second();
         return new BlockPos(values[0], values[1], values[2]);
      }

      long fiveNeighborOpenings() {
         HashSet<BlockPos> overlayNeighborhood = new HashSet<>();
         for (BlockPos overlay : this.overlays) {
            for (int[] step : SIX_NEIGHBORS) {
               BlockPos neighbor = safeNeighbor(overlay, step);
               if (neighbor != null) {
                  overlayNeighborhood.add(neighbor);
               }
            }
         }
         long result = 0L;
         for (BlockPos candidate : overlayNeighborhood) {
            if (this.isFiveNeighborOpening(candidate)) {
               result = saturatedAdd(result, 1L);
            }
         }
         for (Map.Entry<ColumnKey, Span> entry : this.spans.entrySet()) {
            this.observer.checkCancelled();
            Span span = entry.getValue();
            long below = (long)span.minimum() - 1L;
            long above = (long)span.maximum() + 1L;
            if (below >= Integer.MIN_VALUE) {
               BlockPos candidate = this.position(entry.getKey(), (int)below);
               if (!overlayNeighborhood.contains(candidate) && this.isFiveNeighborOpening(candidate)) {
                  result = saturatedAdd(result, 1L);
               }
            }
            if (above <= Integer.MAX_VALUE) {
               BlockPos candidate = this.position(entry.getKey(), (int)above);
               if (!overlayNeighborhood.contains(candidate) && this.isFiveNeighborOpening(candidate)) {
                  result = saturatedAdd(result, 1L);
               }
            }
         }
         return result;
      }

      boolean hasEnclosedAir() {
         ColumnAirModel air = this.columnAirModel();
         HashSet<AirGap> visited = new HashSet<>();
         for (Map.Entry<ColumnKey, List<AxisInterval>> entry : air.gaps().entrySet()) {
            for (int index = 0; index < entry.getValue().size(); index++) {
               AirGap start = new AirGap(entry.getKey(), index);
               if (!visited.add(start)) {
                  continue;
               }
               boolean exterior = false;
               java.util.ArrayDeque<AirGap> open = new java.util.ArrayDeque<>();
               open.add(start);
               while (!open.isEmpty()) {
                  this.observer.checkCancelled();
                  AirGap current = open.removeFirst();
                  AxisInterval interval = air.gaps().get(current.key()).get(current.index());
                  for (int[] step : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                     ColumnKey neighborKey = safeColumnKey(current.key(), step[0], step[1]);
                     if (neighborKey == null) {
                        exterior = true;
                        continue;
                     }
                     List<AxisInterval> occupied = air.occupied().get(neighborKey);
                     if (occupied == null) {
                        exterior = true;
                        continue;
                     }
                     if (interval.minimum() < occupied.getFirst().minimum()
                        || interval.maximum() > occupied.getLast().maximum()) {
                        exterior = true;
                     }
                     List<AxisInterval> neighborGaps = air.gaps().getOrDefault(neighborKey, List.of());
                     int first = firstGapEndingAtOrAfter(neighborGaps, interval.minimum());
                     for (int neighborIndex = first; neighborIndex < neighborGaps.size(); neighborIndex++) {
                        AxisInterval neighbor = neighborGaps.get(neighborIndex);
                        if (neighbor.minimum() > interval.maximum()) {
                           break;
                        }
                        if (neighbor.maximum() >= interval.minimum()) {
                           AirGap next = new AirGap(neighborKey, neighborIndex);
                           if (visited.add(next)) {
                              open.addLast(next);
                           }
                        }
                     }
                  }
               }
               if (!exterior) {
                  return true;
               }
            }
         }
         return false;
      }

      private ColumnAirModel columnAirModel() {
         LinkedHashMap<ColumnKey, ArrayList<AxisInterval>> raw = new LinkedHashMap<>();
         this.spans.forEach((key, span) -> raw.computeIfAbsent(key, ignored -> new ArrayList<>())
            .add(new AxisInterval(span.minimum(), span.maximum())));
         for (BlockPos overlay : this.overlays) {
            this.observer.checkCancelled();
            int value = coordinate(overlay, this.axis);
            raw.computeIfAbsent(this.key(overlay), ignored -> new ArrayList<>())
               .add(new AxisInterval(value, value));
         }
         LinkedHashMap<ColumnKey, List<AxisInterval>> occupied = new LinkedHashMap<>();
         LinkedHashMap<ColumnKey, List<AxisInterval>> gaps = new LinkedHashMap<>();
         raw.forEach((key, intervals) -> {
            this.observer.checkCancelled();
            intervals.sort(java.util.Comparator.comparingInt(AxisInterval::minimum)
               .thenComparingInt(AxisInterval::maximum));
            ArrayList<AxisInterval> merged = new ArrayList<>();
            for (AxisInterval interval : intervals) {
               if (merged.isEmpty()
                  || (long)interval.minimum() > (long)merged.getLast().maximum() + 1L) {
                  merged.add(interval);
               } else {
                  AxisInterval previous = merged.removeLast();
                  merged.add(new AxisInterval(
                     previous.minimum(),
                     Math.max(previous.maximum(), interval.maximum())
                  ));
               }
            }
            occupied.put(key, List.copyOf(merged));
            ArrayList<AxisInterval> finiteGaps = new ArrayList<>();
            for (int index = 0; index + 1 < merged.size(); index++) {
               finiteGaps.add(new AxisInterval(
                  merged.get(index).maximum() + 1,
                  merged.get(index + 1).minimum() - 1
               ));
            }
            if (!finiteGaps.isEmpty()) {
               gaps.put(key, List.copyOf(finiteGaps));
            }
         });
         return new ColumnAirModel(
            Collections.unmodifiableMap(occupied),
            Collections.unmodifiableMap(gaps)
         );
      }

      private static int firstGapEndingAtOrAfter(List<AxisInterval> gaps, int coordinate) {
         int low = 0;
         int high = gaps.size();
         while (low < high) {
            int middle = (low + high) >>> 1;
            if (gaps.get(middle).maximum() < coordinate) {
               low = middle + 1;
            } else {
               high = middle;
            }
         }
         return low;
      }

      private static ColumnKey safeColumnKey(ColumnKey key, int firstDelta, int secondDelta) {
         long first = (long)key.first() + firstDelta;
         long second = (long)key.second() + secondDelta;
         if (first < Integer.MIN_VALUE || first > Integer.MAX_VALUE
            || second < Integer.MIN_VALUE || second > Integer.MAX_VALUE) {
            return null;
         }
         return new ColumnKey((int)first, (int)second);
      }

      private record AxisInterval(int minimum, int maximum) {
      }

      private record AirGap(ColumnKey key, int index) {
      }

      private record ColumnAirModel(
         Map<ColumnKey, List<AxisInterval>> occupied,
         Map<ColumnKey, List<AxisInterval>> gaps
      ) {
      }

      private boolean isFiveNeighborOpening(BlockPos candidate) {
         if (this.contains(candidate)) {
            return false;
         }
         int occupied = 0;
         for (int[] step : SIX_NEIGHBORS) {
            BlockPos neighbor = safeNeighbor(candidate, step);
            if (neighbor != null && this.contains(neighbor)) {
               occupied++;
            }
         }
         return occupied == 5;
      }


      private boolean contains(BlockPos position) {
         if (this.overlays.contains(position)) {
            return true;
         }
         Span span = this.spans.get(this.key(position));
         return span != null && span.contains(coordinate(position, this.axis));
      }

      Result solidResult(int maxBlocks, BlockGenerationObserver observer) {
         this.overlays.removeIf(this::coveredBySpan);
         if (this.blockCount() > maxBlocks) {
            return limitResult(maxBlocks, observer);
         }
         Set<BlockPos> blocks = new ColumnBlockSet(
            this.axis, this.firstKeyAxis, this.secondKeyAxis, this.spans, this.overlays, this.blockCount()
         );
         return new Result(
            blocks,
            true,
            Collections.unmodifiableSet(this.shell)
         );
      }

      private boolean coveredBySpan(BlockPos position) {
         Span span = this.spans.get(this.key(position));
         return span != null && span.contains(coordinate(position, this.axis));
      }

      Result boundaryResult(int maxBlocks, BlockGenerationObserver observer) {
         this.overlays.removeIf(this::coveredBySpan);
         ColumnBlockSet solid = new ColumnBlockSet(
            this.axis, this.firstKeyAxis, this.secondKeyAxis, this.spans, this.overlays, this.blockCount()
         );
         BoundaryColumnBlockSet blocks = BoundaryColumnBlockSet.create(solid, maxBlocks, this.observer);
         if (blocks == null) {
            return limitResult(maxBlocks, observer);
         }
         return new Result(
            blocks,
            true,
            Set.of()
         );
      }

      private void addCount(long amount) {
         this.blockCount = saturatedAdd(this.blockCount, amount);
      }

      private static int coordinate(BlockPos position, int axis) {
         return switch (axis) {
            case 0 -> position.getX();
            case 1 -> position.getY();
            case 2 -> position.getZ();
            default -> throw new IllegalArgumentException("axis " + axis);
         };
      }

   }

   /** Immutable set view that expands compact column spans only while iterating. */
   private static final class ColumnBlockSet extends AbstractSet<BlockPos> {
      private final int axis;
      private final int firstKeyAxis;
      private final int secondKeyAxis;
      private final Map<ColumnKey, Span> spans;
      private final Set<BlockPos> overlays;
      private final int size;

      private ColumnBlockSet(
         int axis,
         int firstKeyAxis,
         int secondKeyAxis,
         Map<ColumnKey, Span> spans,
         Set<BlockPos> overlays,
         long size
      ) {
         if (size < 0L || size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Column block count is outside Set limits: " + size);
         }
         this.axis = axis;
         this.firstKeyAxis = firstKeyAxis;
         this.secondKeyAxis = secondKeyAxis;
         this.spans = Collections.unmodifiableMap(spans);
         this.overlays = Collections.unmodifiableSet(overlays);
         this.size = (int)size;
      }

      @Override
      public int size() {
         return this.size;
      }

      @Override
      public boolean contains(Object candidate) {
         if (!(candidate instanceof BlockPos position)) {
            return false;
         }
         if (this.overlays.contains(position)) {
            return true;
         }
         Span span = this.spans.get(key(position));
         return span != null && span.contains(coordinate(position, this.axis));
      }

      @Override
      public Iterator<BlockPos> iterator() {
         return new ColumnIterator();
      }

      private ColumnKey key(BlockPos position) {
         return new ColumnKey(
            coordinate(position, this.firstKeyAxis), coordinate(position, this.secondKeyAxis)
         );
      }

      private BlockPos position(ColumnKey key, long coordinate) {
         int[] values = new int[3];
         values[this.axis] = (int)coordinate;
         values[this.firstKeyAxis] = key.first();
         values[this.secondKeyAxis] = key.second();
         return new BlockPos(values[0], values[1], values[2]);
      }

      private final class ColumnIterator implements Iterator<BlockPos> {
         private final Iterator<Map.Entry<ColumnKey, Span>> columns = ColumnBlockSet.this.spans.entrySet().iterator();
         private final Iterator<BlockPos> overlayIterator = ColumnBlockSet.this.overlays.iterator();
         private Map.Entry<ColumnKey, Span> column;
         private long coordinate;

         @Override
         public boolean hasNext() {
            return hasColumnValue() || this.overlayIterator.hasNext();
         }

         @Override
         public BlockPos next() {
            if (hasColumnValue()) {
               return ColumnBlockSet.this.position(this.column.getKey(), this.coordinate++);
            }
            if (this.overlayIterator.hasNext()) {
               return this.overlayIterator.next();
            }
            throw new NoSuchElementException();
         }

         private boolean hasColumnValue() {
            while (this.column == null || this.coordinate > this.column.getValue().maximum()) {
               if (!this.columns.hasNext()) {
                  return false;
               }
               this.column = this.columns.next();
               this.coordinate = this.column.getValue().minimum();
            }
            return true;
         }
      }
   }

   /** Boundary view that filters a compact solid without retaining its expanded positions. */
   private static final class BoundaryColumnBlockSet extends AbstractSet<BlockPos> {
      private final ColumnBlockSet solid;
      private final int size;

      private BoundaryColumnBlockSet(ColumnBlockSet solid, int size) {
         this.solid = solid;
         this.size = size;
      }

      static BoundaryColumnBlockSet create(
         ColumnBlockSet solid,
         int maxBlocks,
         BlockGenerationObserver observer
      ) {
         int count = 0;
         for (BlockPos position : solid) {
            observer.checkCancelled();
            if (isBoundary(solid, position) && ++count > maxBlocks) {
               return null;
            }
         }
         return new BoundaryColumnBlockSet(solid, count);
      }

      @Override
      public int size() {
         return this.size;
      }

      @Override
      public boolean contains(Object candidate) {
         return candidate instanceof BlockPos position
            && this.solid.contains(position)
            && isBoundary(this.solid, position);
      }

      @Override
      public Iterator<BlockPos> iterator() {
         return new BoundaryIterator();
      }

      private static boolean isBoundary(Set<BlockPos> solid, BlockPos position) {
         for (int[] step : SIX_NEIGHBORS) {
            BlockPos neighbor = safeNeighbor(position, step);
            if (neighbor == null || !solid.contains(neighbor)) {
               return true;
            }
         }
         return false;
      }

      private final class BoundaryIterator implements Iterator<BlockPos> {
         private final Iterator<BlockPos> candidates = BoundaryColumnBlockSet.this.solid.iterator();
         private BlockPos next;

         @Override
         public boolean hasNext() {
            while (this.next == null && this.candidates.hasNext()) {
               BlockPos candidate = this.candidates.next();
               if (BoundaryColumnBlockSet.isBoundary(BoundaryColumnBlockSet.this.solid, candidate)) {
                  this.next = candidate;
               }
            }
            return this.next != null;
         }

         @Override
         public BlockPos next() {
            if (!this.hasNext()) {
               throw new NoSuchElementException();
            }
            BlockPos result = this.next;
            this.next = null;
            return result;
         }
      }
   }

   private static long saturatedAdd(long first, long second) {
      return second > Long.MAX_VALUE - first ? Long.MAX_VALUE : first + second;
   }
}
