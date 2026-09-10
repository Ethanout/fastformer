package io.github.fastformer.fastplace.geometry;

import io.github.fastformer.fastplace.geometry.generation.BlockGenerationResult;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

public record GeometryBuildResult(
   long scanCells,
   long targetCapacity,
   Supplier<BlockGenerationResult> generation,
   Component blockedReason
) {
   public GeometryBuildResult {
      if (scanCells < 0L || targetCapacity < 0L) {
         throw new IllegalArgumentException("Geometry work estimates must not be negative");
      }
      Objects.requireNonNull(generation, "generation");
   }

   public static GeometryBuildResult readyLegacy(
      long scanCells,
      long targetCapacity,
      Supplier<Set<BlockPos>> blocks
   ) {
      return new GeometryBuildResult(
         scanCells,
         targetCapacity,
         () -> BlockGenerationResult.fromLegacy(blocks.get()),
         null
      );
   }

   public static GeometryBuildResult ready(
      long scanCells,
      long targetCapacity,
      Supplier<BlockGenerationResult> generation
   ) {
      return new GeometryBuildResult(scanCells, targetCapacity, generation, null);
   }

   public static GeometryBuildResult blocked(Component reason) {
      return new GeometryBuildResult(
         0L,
         0L,
         BlockGenerationResult::constraintsFailed,
         reason
      );
   }

   public boolean ready() {
      return this.blockedReason == null;
   }
}
