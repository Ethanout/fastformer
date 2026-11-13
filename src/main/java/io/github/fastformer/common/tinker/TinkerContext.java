package io.github.fastformer.common.tinker;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** A single read-only tinker evaluation, shared by client prediction and server edits. */
public record TinkerContext(Level level, BlockHitResult hit, BlockState state,
                            boolean shift, boolean skipRepeatedCropStages) {
   public TinkerContext {
      Objects.requireNonNull(level, "level");
      Objects.requireNonNull(hit, "hit");
      Objects.requireNonNull(state, "state");
   }

   public static TinkerContext at(Level level, BlockHitResult hit, boolean shift, boolean skipRepeatedCropStages) {
      return new TinkerContext(level, hit, level.getBlockState(hit.getBlockPos()), shift, skipRepeatedCropStages);
   }

   public BlockPos pos() { return hit.getBlockPos(); }
   public Block block() { return state.getBlock(); }
   public Direction face() { return hit.getDirection(); }
}
