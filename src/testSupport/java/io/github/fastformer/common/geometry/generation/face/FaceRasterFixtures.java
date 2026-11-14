package io.github.fastformer.common.geometry.generation.face;

import io.github.fastformer.common.geometry.LineTieBias;
import io.github.fastformer.common.geometry.raster.BlockGenerationObserver;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Lets volume tests compare independent face rasters without widening production internals. */
public final class FaceRasterFixtures {
   private FaceRasterFixtures() {}

   public record Result(Set<BlockPos> fill, Set<BlockPos> outline) {}
   public enum Status { SUCCESS, NOT_APPLICABLE, LIMIT_EXCEEDED, NO_VALID_CANDIDATE }
   public record Attempt(boolean succeeded, Status status, Result result) {}

   public static Attempt attempt(ProjectedBresenhamFace.Frame frame, LineTieBias tieBias,
      int maxBlocks, BlockGenerationObserver observer) {
      var attempt = QuadFaceRasterizer.attempt(frame, tieBias, maxBlocks, observer);
      return new Attempt(attempt.succeeded(), Status.valueOf(attempt.status().name()),
         new Result(attempt.result().fill(), attempt.result().outline()));
   }

   public static Set<BlockPos> generate(List<Vec3> vertices, int maxBlocks) {
      return PlanarFaceRasterizer.generate(vertices, maxBlocks);
   }

   public static Set<BlockPos> interpolatedQuadOutline(List<Vec3> vertices, int maxBlocks,
      BlockGenerationObserver observer, LineTieBias tieBias) {
      return PlanarFaceRasterizer.interpolatedQuadOutline(vertices, maxBlocks, observer, tieBias);
   }
}
