package io.github.fastformer.fastplace.interaction;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

final class TinkerTorchCycle {
   private TinkerTorchCycle() {}

   static BlockState apply(BlockState state) {
      if (state.is(Blocks.TORCH) || state.is(Blocks.REDSTONE_TORCH)) {
         return cycle(state, Blocks.TORCH, Blocks.REDSTONE_TORCH);
      }
      if (state.is(Blocks.WALL_TORCH) || state.is(Blocks.REDSTONE_WALL_TORCH)) {
         return cycle(state, Blocks.WALL_TORCH, Blocks.REDSTONE_WALL_TORCH);
      }
      return null;
   }

   private static BlockState cycle(BlockState state, Block torch, Block redstoneTorch) {
      if (state.is(redstoneTorch) && !state.getValue(BlockStateProperties.LIT)) return state;
      return state.is(torch)
         ? redstoneTorch.withPropertiesOf(state).setValue(BlockStateProperties.LIT, true)
         : torch.withPropertiesOf(state);
   }
}
