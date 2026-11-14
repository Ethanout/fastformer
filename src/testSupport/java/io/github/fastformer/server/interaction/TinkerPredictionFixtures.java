package io.github.fastformer.server.interaction;

import io.github.fastformer.common.tinker.TinkerAction;
import io.github.fastformer.common.tinker.TinkerPrediction;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** Keeps rule-only GameTest conveniences out of the server interaction API. */
final class TinkerPredictionFixtures {
   private TinkerPredictionFixtures() {
   }

   static Map<BlockPos, BlockState> resolve(Level level, BlockHitResult hit) {
      return resolveAction(level, hit, false).changes();
   }

   static TinkerAction resolveAction(Level level, BlockHitResult hit, boolean shift) {
      return resolveAction(level, hit, shift, true);
   }

   static TinkerAction resolveAction(Level level, BlockHitResult hit, boolean shift, boolean skipRepeatedCropStages) {
      return TinkerPrediction.resolveAction(level, hit, shift, skipRepeatedCropStages);
   }
}
