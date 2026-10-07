package io.github.fastformer.fastplace.interaction;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.DataMapHooks;

/** Uses NeoForge's reloadable oxidation and wax maps, including their inverse maps. */
final class TinkerCopperCycle {
   private TinkerCopperCycle() {}

   static boolean supports(BlockState state) {
      Block bare = bare(state.getBlock());
      return bare != state.getBlock() || DataMapHooks.getBlockWaxed(bare) != null
         || DataMapHooks.getNextOxidizedStage(bare) != null || DataMapHooks.getPreviousOxidizedStage(bare) != null;
   }

   static BlockState apply(BlockState state, BlockHitResult hit, boolean shift) {
      if (!supports(state)) return null;
      Block original = state.getBlock();
      Block bare = bare(original);
      Block first = first(bare);
      boolean separate = first == Blocks.COPPER_BLOCK || first == Blocks.CHISELED_COPPER;
      if (separate) {
         Block target;
         if (hit.getDirection().getAxis().isVertical()) {
            target = original == bare ? DataMapHooks.getBlockWaxed(bare) : bare;
         } else {
            Block next = nextOrFirst(bare);
            target = original == bare ? next : DataMapHooks.getBlockWaxed(next);
         }
         return target == null ? state : target.withPropertiesOf(state);
      }
      if (!shift && (state.getBlock() instanceof DoorBlock || state.getBlock() instanceof TrapDoorBlock
         || state.getBlock() instanceof CopperBulbBlock || state.getBlock() instanceof SlabBlock
         || state.getBlock() instanceof StairBlock || first == Blocks.CUT_COPPER)) return null;
      return combined(state);
   }

   static BlockState combined(BlockState state) {
      Block original = state.getBlock();
      Block bare = bare(original);
      boolean waxed = bare != original;
      Block next = DataMapHooks.getNextOxidizedStage(bare);
      Block target;
      if (next != null) target = waxed ? DataMapHooks.getBlockWaxed(next) : next;
      else target = waxed ? first(bare) : DataMapHooks.getBlockWaxed(first(bare));
      return target == null ? state : target.withPropertiesOf(state);
   }

   static boolean addsWax(BlockState before, BlockState after) {
      return bare(before.getBlock()) == before.getBlock() && bare(after.getBlock()) != after.getBlock();
   }

   private static Block bare(Block block) {
      Block unwaxed = DataMapHooks.getBlockUnwaxed(block);
      return unwaxed == null ? block : unwaxed;
   }

   private static Block nextOrFirst(Block block) {
      Block next = DataMapHooks.getNextOxidizedStage(block);
      return next == null ? first(block) : next;
   }

   private static Block first(Block block) {
      Set<Block> visited = new HashSet<>();
      Block current = block;
      while (visited.add(current)) {
         Block previous = DataMapHooks.getPreviousOxidizedStage(current);
         if (previous == null) return current;
         current = previous;
      }
      return block;
   }
}
