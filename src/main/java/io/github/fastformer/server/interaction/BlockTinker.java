package io.github.fastformer.server.interaction;

import io.github.fastformer.server.session.SessionSystem;

import io.github.fastformer.server.history.HistorySystem;

import io.github.fastformer.common.tinker.TinkerAction;
import io.github.fastformer.common.tinker.TinkerPrediction;
import io.github.fastformer.common.tinker.state.TinkerCopperCycle;
import io.github.fastformer.server.history.entry.BlockEditHistoryMetadata;
import io.github.fastformer.server.history.HistoryStorageConfig;
import io.github.fastformer.common.placement.PlacementUpdateMode;
import io.github.fastformer.server.settings.FastPlaceSettings;
import io.github.fastformer.server.worldwrite.transaction.BlockEditTransaction;
import io.github.fastformer.server.session.QuickShapeSystem;
import io.github.fastformer.server.session.GeometrySystem;
import io.github.fastformer.server.session.OperationSystem;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** Empty-hand tinker admission and transactional application of the shared rules. */
public final class BlockTinker {
   private BlockTinker() {}

   public static boolean use(ServerPlayer player, BlockHitResult hit) {
      if (!TinkerPrediction.canEdit(player) || !TinkerPrediction.withinInteractionRange(player, hit)
         || HistorySystem.of(player).busy(player) || QuickShapeSystem.of(player).active(player)
         || OperationSystem.of(player).active(player) || GeometrySystem.of(player).active(player)
         || SessionSystem.of(player).placementTasks().active(player) || SessionSystem.of(player).operationTasks().active(player)) return false;
      FastPlaceSettings settings = FastPlaceSettings.load(player);
      if (!settings.enabled() || !settings.emptyHandWrench()) return false;
      ServerLevel level = player.serverLevel();
      TinkerAction action = TinkerPrediction.previewAction(player, hit, settings.skipRepeatedCropStages());
      if (!action.consumed()) return false;
      if (action.changes().isEmpty()) return true;
      boolean wax = action.changes().entrySet().stream()
         .anyMatch(entry -> TinkerCopperCycle.addsWax(level.getBlockState(entry.getKey()), entry.getValue()));
      var metadata = BlockEditHistoryMetadata.fromCluster(TinkerHistoryClassifier.cluster(
         level, hit, action.changes(), HistoryStorageConfig.compactionPolicy().grouping()));
      var outcome = tinker(player, level, action.changes(), settings.placementUpdateMode().flags(), metadata);
      if (outcome == BlockEditTransaction.Outcome.APPLIED && wax) level.levelEvent(null, 3003, hit.getBlockPos(), 0);
      return outcome.consumedInteraction() || outcome == BlockEditTransaction.Outcome.REJECTED_BUSY;
   }

   /** A failed edit must not continue with vanilla interaction. */
   static BlockEditTransaction.Outcome tinker(ServerPlayer player, ServerLevel level,
      Map<BlockPos, BlockState> changes, int flags) {
      return tinker(player, level, changes, flags, null);
   }

   private static BlockEditTransaction.Outcome tinker(ServerPlayer player, ServerLevel level,
      Map<BlockPos, BlockState> changes, int flags, BlockEditHistoryMetadata metadata) {
      BlockEditTransaction.Outcome outcome = BlockEditTransaction.apply(
         player, level, changes, flags, PlacementUpdateMode.CLIENT_ONLY.flags(), metadata);
      BlockEditTransaction.report(outcome, player);
      return outcome;
   }

   public static Map<BlockPos, BlockState> preview(ServerPlayer player, BlockHitResult hit) {
      return TinkerPrediction.previewAction(player, hit, FastPlaceSettings.load(player).skipRepeatedCropStages()).changes();
   }
}
