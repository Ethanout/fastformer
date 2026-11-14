package io.github.fastformer.common.geometry.generation.volume;

import io.github.fastformer.common.geometry.LineTieBias;
import io.github.fastformer.common.geometry.generation.face.ProjectedBresenhamFace;
import io.github.fastformer.common.geometry.generation.face.FaceRasterAdapters;
import io.github.fastformer.common.geometry.generation.source.GeneratedBlockSets;
import io.github.fastformer.common.geometry.generation.source.GenerationFailed;
import io.github.fastformer.common.geometry.generation.source.GenerationLimitExceeded;
import io.github.fastformer.common.geometry.generation.source.LineGenerator;
import io.github.fastformer.common.geometry.generation.volume.column.ColumnVolume;
import io.github.fastformer.common.geometry.model.FillMode;
import io.github.fastformer.common.geometry.raster.BlockGenerationObserver;
import io.github.fastformer.common.geometry.raster.face.FaceRasterDispatcher;
import io.github.fastformer.common.geometry.raster.face.FaceRasterResult;
import io.github.fastformer.common.geometry.raster.face.FaceRequest;
import io.github.fastformer.common.geometry.raster.face.policy.FacePolicyObserver;
import io.github.fastformer.core.math.SaturatedArithmetic;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Fills convex columns around six independently rasterized faces and twelve shared true edges. */
final class BresenhamColumnVolume {
   private static final long FACE_WORK_CAP = 4_000_000L;
   private static final long CANDIDATE_COLUMN_WORK_CAP = 1_000_000L;

   private BresenhamColumnVolume() {}

   static Result generate(List<Vec3> base, Vec3 extrusion, int maxBlocks, BlockGenerationObserver observer) {
      return generate(base, extrusion, maxBlocks, observer, LineTieBias.DEFAULT);
   }

   static Result generate(List<Vec3> base, Vec3 extrusion, int maxBlocks,
      BlockGenerationObserver observer, LineTieBias tieBias) {
      return generate(base, extrusion, maxBlocks, observer, tieBias, strictFaces(observer));
   }

   static Result generate(List<Vec3> base, Vec3 extrusion, int maxBlocks,
      BlockGenerationObserver observer, LineTieBias tieBias, FaceRasterDispatcher faceRasterizer) {
      return generate(base, extrusion, maxBlocks, observer, tieBias, false, faceRasterizer);
   }

   static Result generateBoundary(List<Vec3> base, Vec3 extrusion, int maxBlocks,
      BlockGenerationObserver observer, LineTieBias tieBias) {
      return generateBoundary(base, extrusion, maxBlocks, observer, tieBias, strictFaces(observer));
   }

   static Result generateBoundary(List<Vec3> base, Vec3 extrusion, int maxBlocks,
      BlockGenerationObserver observer, LineTieBias tieBias, FaceRasterDispatcher faceRasterizer) {
      return generate(base, extrusion, maxBlocks, observer, tieBias, true, faceRasterizer);
   }

   private static FaceRasterDispatcher strictFaces(BlockGenerationObserver observer) {
      return FaceRasterAdapters.strictQuad(FacePolicyObserver.policies(observer).volume());
   }

   private static Result generate(List<Vec3> base, Vec3 extrusion, int maxBlocks,
      BlockGenerationObserver observer, LineTieBias tieBias, boolean boundaryOnly,
      FaceRasterDispatcher faceRasterizer) {
      if (base == null || base.size() != 4 || extrusion == null || maxBlocks <= 0) return Result.failed();
      BlockGenerationObserver staging = stagingObserver(observer);
      ProjectedBresenhamFace.Frame frame = ProjectedBresenhamFace.Frame.create(base);
      if (frame == null) return Result.failed();
      List<Vec3> top = base.stream().map(vertex -> vertex.add(extrusion)).toList();
      List<Vec3> hullVertices = new ArrayList<>(base.size() + top.size());
      hullVertices.addAll(base);
      hullVertices.addAll(top);
      List<List<Vec3>> faces = boxFaces(base, top);
      int faceBudget = boundaryOnly ? (int)FACE_WORK_CAP : maxBlocks;
      boolean faceWorkCapped = faceWorkExceedsCap(faces, faceBudget);
      ArrayList<Integer> boundedAxes = new ArrayList<>(3);
      for (int axis : candidateAxes(BlockPos.containing(extrusion))) {
         long projectedColumns = ArbitraryConvexPolyhedronGenerator.estimateSolidSpanScanColumns(
            hullVertices, axis, CANDIDATE_COLUMN_WORK_CAP, staging);
         if (projectedColumns <= CANDIDATE_COLUMN_WORK_CAP) boundedAxes.add(axis);
      }
      if (boundedAxes.isEmpty()) return limitResult(maxBlocks, staging);
      FaceShellResult faceShell = faceWorkCapped
         ? FaceShellResult.failed(FaceShellStatus.LIMIT_EXCEEDED)
         : independentFaceShell(faces, faceBudget, staging, tieBias, faceRasterizer);
      if (faceShell.status() == FaceShellStatus.LIMIT_EXCEEDED) return limitResult(maxBlocks, staging);
      if (faceShell.status() != FaceShellStatus.SUCCESS) return Result.faceConstraintsFailed();
      if (faceShell.blocks().isEmpty()) return Result.failed();
      ColumnVolume best = bestCandidate(faceShell.blocks(), boundedAxes, frame, staging);
      if (best == null) return Result.faceConstraintsFailed();
      ColumnVolume.Result staged = boundaryOnly
         ? best.boundaryResult(maxBlocks, staging, true) : best.solidResult(maxBlocks, staging);
      Result result = new Result(staged.blocks(), staged.complete(), staged.shell());
      return result.complete() ? publish(result, observer) : result;
   }

   private static ColumnVolume bestCandidate(Set<BlockPos> shell, List<Integer> axes,
      ProjectedBresenhamFace.Frame frame, BlockGenerationObserver observer) {
      ColumnVolume best = null;
      int bestRank = Integer.MAX_VALUE;
      for (int axis : axes) {
         ColumnVolume candidate = ColumnVolume.fromShell(shell, axis, observer);
         if (candidate.fiveNeighborOpenings() != 0L || candidate.hasEnclosedAir()) continue;
         int rank = axisRank(frame, axis);
         if (best == null || candidate.blockCount() < best.blockCount()
            || candidate.blockCount() == best.blockCount() && rank < bestRank) {
            best = candidate;
            bestRank = rank;
         }
      }
      return best;
   }

   private static boolean faceWorkExceedsCap(List<List<Vec3>> faces, int maxBlocks) {
      long totalWork = 0L;
      for (List<Vec3> face : faces) {
         ProjectedBresenhamFace.Frame frame = ProjectedBresenhamFace.Frame.create(face);
         if (frame == null) return true;
         List<ProjectedBresenhamFace.Int3> vertices = frame.vertices();
         BigInteger domain = projectedDomain(vertices);
         if (domain == null || domain.compareTo(BigInteger.valueOf(maxBlocks)) > 0) return true;
         long work = faceWork(frame, vertices);
         totalWork = SaturatedArithmetic.saturatedAdd(totalWork, work);
         if (work > FACE_WORK_CAP || totalWork > FACE_WORK_CAP) return true;
      }
      return false;
   }

   private static long faceWork(ProjectedBresenhamFace.Frame frame, List<ProjectedBresenhamFace.Int3> vertices) {
      if (vertices.size() != 4) return Long.MAX_VALUE;
      ProjectedBresenhamFace.Int3 p00 = vertices.get(0);
      ProjectedBresenhamFace.Int3 p10 = vertices.get(1);
      ProjectedBresenhamFace.Int3 p11 = vertices.get(2);
      ProjectedBresenhamFace.Int3 p01 = vertices.get(3);
      if ((long)p00.x() + p11.x() != (long)p10.x() + p01.x()
         || (long)p00.y() + p11.y() != (long)p10.y() + p01.y()
         || (long)p00.z() + p11.z() != (long)p10.z() + p01.z()) return Long.MAX_VALUE;
      BigInteger domain = projectedDomain(vertices);
      if (domain == null || domain.compareTo(BigInteger.valueOf(FACE_WORK_CAP)) > 0) return Long.MAX_VALUE;
      List<BlockPos> world = vertices.stream().map(frame::restore).toList();
      long lineU = LineGenerator.estimateBlocks(world.get(0), world.get(1));
      long lineV = LineGenerator.estimateBlocks(world.get(0), world.get(3));
      long work = SaturatedArithmetic.saturatedAdd(domain.longValueExact(),
         SaturatedArithmetic.saturatedMultiply(lineU, lineV));
      for (int index = 0; index < 4; index++) {
         work = SaturatedArithmetic.saturatedAdd(work,
            LineGenerator.estimateBlocks(world.get(index), world.get((index + 1) % 4)));
      }
      return work;
   }

   private static BigInteger projectedDomain(List<ProjectedBresenhamFace.Int3> vertices) {
      if (vertices.size() != 4) return null;
      ProjectedBresenhamFace.Int3 p00 = vertices.get(0);
      ProjectedBresenhamFace.Int3 p10 = vertices.get(1);
      ProjectedBresenhamFace.Int3 p01 = vertices.get(3);
      BigInteger ux = difference(p10.x(), p00.x());
      BigInteger uy = difference(p10.y(), p00.y());
      BigInteger vx = difference(p01.x(), p00.x());
      BigInteger vy = difference(p01.y(), p00.y());
      BigInteger determinant = ux.multiply(vy).subtract(uy.multiply(vx)).abs();
      return determinant.signum() == 0 ? null
         : determinant.add(ux.abs().gcd(uy.abs())).add(vx.abs().gcd(vy.abs())).add(BigInteger.ONE);
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

   private static FaceShellResult independentFaceShell(List<List<Vec3>> faces, int maxBlocks,
      BlockGenerationObserver observer, LineTieBias tieBias, FaceRasterDispatcher faceRasterizer) {
      LinkedHashSet<BlockPos> shell = new LinkedHashSet<>();
      for (List<Vec3> face : faces) {
         observer.checkCancelled();
         if (ProjectedBresenhamFace.Frame.create(face) == null) {
            return FaceShellResult.failed(FaceShellStatus.NO_VALID_CANDIDATE);
         }
         FaceRasterResult result = faceRasterizer.rasterize(
            new FaceRequest(face, FillMode.SOLID, tieBias, maxBlocks, observer));
         if (!(result instanceof FaceRasterResult.Filled filled)) return FaceShellResult.failed(faceStatus(result));
         for (BlockPos block : filled.blocks()) {
            if (!shell.contains(block) && shell.size() >= maxBlocks) {
               return FaceShellResult.failed(FaceShellStatus.LIMIT_EXCEEDED);
            }
            shell.add(block.immutable());
         }
      }
      return new FaceShellResult(Collections.unmodifiableSet(shell), FaceShellStatus.SUCCESS);
   }

   private static FaceShellStatus faceStatus(FaceRasterResult result) {
      return switch (result) {
         case FaceRasterResult.Filled ignored -> FaceShellStatus.SUCCESS;
         case FaceRasterResult.LimitExceeded ignored -> FaceShellStatus.LIMIT_EXCEEDED;
         case FaceRasterResult.NotApplicable ignored -> FaceShellStatus.NOT_APPLICABLE;
         case FaceRasterResult.NoValidCandidate ignored -> FaceShellStatus.NO_VALID_CANDIDATE;
      };
   }

   private static int[] candidateAxes(BlockPos extrusion) {
      int[] coordinates = {extrusion.getX(), extrusion.getY(), extrusion.getZ()};
      int nonZero = 0;
      int onlyAxis = -1;
      for (int axis = 0; axis < 3; axis++) {
         if (coordinates[axis] != 0) {
            nonZero++;
            onlyAxis = axis;
         }
      }
      return nonZero == 1 ? new int[]{onlyAxis} : LineGenerator.axesByDescendingSlope(BlockPos.ZERO, extrusion);
   }

   private static int axisRank(ProjectedBresenhamFace.Frame frame, int worldAxis) {
      int[] order = frame.order();
      for (int rank = 0; rank < order.length; rank++) if (order[rank] == worldAxis) return rank;
      throw new IllegalArgumentException("axis " + worldAxis);
   }

   private static Result publish(Result staged, BlockGenerationObserver observer) {
      BlockGenerationObserver actualObserver = observer == null ? BlockGenerationObserver.NONE : observer;
      for (BlockPos position : staged.blocks()) {
         actualObserver.checkCancelled();
         actualObserver.onGenerated(position);
      }
      return new Result(GeneratedBlockSets.readOnly(staged.blocks()), true, staged.shell());
   }

   /** One-past-limit witness remains bounded, cancellable, and never publishes callbacks. */
   private static Result limitResult(int maxBlocks, BlockGenerationObserver observer) {
      return new Result(GenerationLimitExceeded.witness(maxBlocks, observer), false, Set.of());
   }

   private static BlockGenerationObserver stagingObserver(BlockGenerationObserver observer) {
      BlockGenerationObserver delegate = observer == null ? BlockGenerationObserver.NONE : observer;
      return FacePolicyObserver.forward(delegate, new BlockGenerationObserver() {
         @Override
         public void onScanned(long amount) { delegate.onScanned(amount); }

         @Override
         public void checkCancelled() { delegate.checkCancelled(); }
      });
   }

   record Result(Set<BlockPos> blocks, boolean complete, Set<BlockPos> shell) {
      private static Result failed() { return new Result(Set.of(), false, Set.of()); }
      private static Result faceConstraintsFailed() { return new Result(GenerationFailed.faceConstraints(), false, Set.of()); }
   }

   private enum FaceShellStatus { SUCCESS, LIMIT_EXCEEDED, NOT_APPLICABLE, NO_VALID_CANDIDATE }

   private record FaceShellResult(Set<BlockPos> blocks, FaceShellStatus status) {
      static FaceShellResult failed(FaceShellStatus status) { return new FaceShellResult(Set.of(), status); }
   }
}
