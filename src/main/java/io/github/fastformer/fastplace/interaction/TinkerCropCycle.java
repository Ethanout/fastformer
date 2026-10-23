package io.github.fastformer.fastplace.interaction;

import java.util.List;
import java.util.Map;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

final class TinkerCropCycle {
   // Representative ages from the vanilla 1.21.1 block models.
   private static final Map<Block, List<Integer>> VISIBLE_STAGES = Map.of(
      Blocks.CARROTS, List.of(0, 2, 4, 7),
      Blocks.POTATOES, List.of(0, 2, 4, 7),
      Blocks.NETHER_WART, List.of(0, 1, 3));

   private TinkerCropCycle() { }

   static BlockState advance(BlockState state, IntegerProperty age) {
      List<Integer> stages = VISIBLE_STAGES.get(state.getBlock());
      if (stages == null) return state.cycle(age);
      int current = state.getValue(age);
      for (int next : stages) {
         if (next > current) return state.setValue(age, next);
      }
      return state.setValue(age, stages.getFirst());
   }
}
