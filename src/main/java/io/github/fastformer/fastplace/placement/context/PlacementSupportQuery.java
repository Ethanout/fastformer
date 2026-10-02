package io.github.fastformer.fastplace.placement.context;

import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** A single support position visible only to the current placement query and thread. */
public final class PlacementSupportQuery {
   private static final ThreadLocal<Support> CURRENT = new ThreadLocal<>();

   private PlacementSupportQuery() {}

   public static <T> T withSupport(Level level, BlockPos position, Supplier<T> query) {
      Support previous = CURRENT.get();
      CURRENT.set(new Support(level, position.immutable()));
      try {
         return query.get();
      } finally {
         if (previous == null) CURRENT.remove();
         else CURRENT.set(previous);
      }
   }

   public static BlockState blockState(Level level, BlockPos position) {
      Support support = CURRENT.get();
      return support != null && support.level == level && support.position.equals(position)
         ? Blocks.STONE.defaultBlockState() : null;
   }

   private record Support(Level level, BlockPos position) {}
}
