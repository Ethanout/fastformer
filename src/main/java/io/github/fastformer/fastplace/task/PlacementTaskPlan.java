package io.github.fastformer.fastplace.task;

import io.github.fastformer.fastplace.OperationConflictMode;
import io.github.fastformer.fastplace.PlacementUpdateMode;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.BlockPos;

/** Immutable execution policy shared by every placement task. */
public record PlacementTaskPlan(
   BlockState defaultState,
   Function<Set<BlockPos>, Map<BlockPos, BlockState>> stateResolver,
   OperationConflictMode conflictMode,
   PlacementUpdateMode updateMode,
   int maxPlacement,
   ResourceKey<Level> dimension
) {
   public PlacementTaskPlan {
      if (conflictMode == null || updateMode == null || dimension == null) {
         throw new IllegalArgumentException("A placement task plan requires policies and dimension");
      }
      if (maxPlacement < 1) {
         throw new IllegalArgumentException("maxPlacement must be positive");
      }
   }
}
