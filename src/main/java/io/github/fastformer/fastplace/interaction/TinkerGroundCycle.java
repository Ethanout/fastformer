package io.github.fastformer.fastplace.interaction;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

final class TinkerGroundCycle {
   private TinkerGroundCycle() {}

   static BlockState apply(BlockState state, Direction face) {
      if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.PODZOL) || state.is(Blocks.MYCELIUM)) {
         return face == Direction.UP ? Blocks.DIRT_PATH.defaultBlockState() : cycleSurface(state);
      }
      if (state.is(Blocks.DIRT_PATH) || state.is(Blocks.FARMLAND)) {
         return face == Direction.UP ? cycleCultivation(state) : state;
      }
      if (state.is(Blocks.DIRT)) return Blocks.COARSE_DIRT.defaultBlockState();
      if (state.is(Blocks.COARSE_DIRT)) return Blocks.ROOTED_DIRT.defaultBlockState();
      if (state.is(Blocks.ROOTED_DIRT)) return Blocks.DIRT.defaultBlockState();
      if (state.is(Blocks.SHORT_GRASS)) return Blocks.TALL_GRASS.defaultBlockState().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
      if (state.is(Blocks.TALL_GRASS)) {
         return state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER
            ? state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER)
            : Blocks.SHORT_GRASS.defaultBlockState();
      }
      return null;
   }

   private static BlockState cycleSurface(BlockState state) {
      if (state.is(Blocks.PODZOL)) return Blocks.MYCELIUM.defaultBlockState();
      if (state.is(Blocks.MYCELIUM)) return Blocks.GRASS_BLOCK.defaultBlockState().setValue(BlockStateProperties.SNOWY, true);
      return state.getValue(BlockStateProperties.SNOWY)
         ? Blocks.GRASS_BLOCK.defaultBlockState() : Blocks.PODZOL.defaultBlockState();
   }

   private static BlockState cycleCultivation(BlockState state) {
      if (state.is(Blocks.DIRT_PATH)) return Blocks.FARMLAND.defaultBlockState().setValue(BlockStateProperties.MOISTURE, 7);
      return state.getValue(BlockStateProperties.MOISTURE) > 0
         ? state.setValue(BlockStateProperties.MOISTURE, 0) : Blocks.GRASS_BLOCK.defaultBlockState();
   }
}
