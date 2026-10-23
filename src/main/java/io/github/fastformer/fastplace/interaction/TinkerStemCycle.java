package io.github.fastformer.fastplace.interaction;

import java.util.List;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;

final class TinkerStemCycle {
   private record Family(Block stem, Block attached) { }
   private static final List<Family> FAMILIES = List.of(
      new Family(Blocks.PUMPKIN_STEM, Blocks.ATTACHED_PUMPKIN_STEM),
      new Family(Blocks.MELON_STEM, Blocks.ATTACHED_MELON_STEM));

   private TinkerStemCycle() { }

   static BlockState apply(BlockState state, BlockHitResult hit) {
      for (Family family : FAMILIES) {
         if (state.is(family.attached())) return mature(family.stem().defaultBlockState());
         if (!state.is(family.stem())) continue;
         int age = state.getValue(BlockStateProperties.AGE_7);
         if (age < 7) return state.setValue(BlockStateProperties.AGE_7, age + 1);
         Direction side = TinkerSurfaceHit.sideOperation(hit);
         if (side == Direction.UP) return state.setValue(BlockStateProperties.AGE_7, 0);
         if (side.getAxis().isHorizontal()) {
            return family.attached().defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, side);
         }
         return state;
      }
      return null;
   }

   private static BlockState mature(BlockState state) {
      return state.setValue(BlockStateProperties.AGE_7, 7);
   }
}
