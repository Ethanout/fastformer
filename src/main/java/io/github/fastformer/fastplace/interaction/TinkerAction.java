package io.github.fastformer.fastplace.interaction;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** An owned interaction can have no writes, for example at a numeric limit. */
record TinkerAction(boolean consumed, Map<BlockPos, BlockState> changes) {
   static final TinkerAction PASS = new TinkerAction(false, Map.of());
   static final TinkerAction NO_CHANGE = new TinkerAction(true, Map.of());

   static TinkerAction edit(Level level, BlockPos pos, BlockState state) {
      return edit(level, Map.of(pos.immutable(), state));
   }

   static TinkerAction edit(Level level, Map<BlockPos, BlockState> desired) {
      Map<BlockPos, BlockState> changes = new LinkedHashMap<>();
      desired.forEach((pos, state) -> {
         if (!level.getBlockState(pos).equals(state)) changes.put(pos.immutable(), state);
      });
      return new TinkerAction(true, Collections.unmodifiableMap(changes));
   }
}
