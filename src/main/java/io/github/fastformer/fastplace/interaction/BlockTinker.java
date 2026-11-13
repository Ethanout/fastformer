package io.github.fastformer.fastplace.interaction;

import io.github.fastformer.server.session.SessionSystem;

import io.github.fastformer.server.history.HistorySystem;

import io.github.fastformer.common.tinker.TinkerAction;
import io.github.fastformer.common.tinker.TinkerContext;
import io.github.fastformer.common.tinker.TinkerRules;
import io.github.fastformer.common.tinker.rule.StandardTinkerRules;
import io.github.fastformer.common.tinker.state.TinkerCopperCycle;
import io.github.fastformer.fastplace.geometry.raycast.LongRangeBlockRaycast;
import io.github.fastformer.server.history.entry.BlockEditHistoryMetadata;
import io.github.fastformer.server.history.HistoryStorageConfig;
import io.github.fastformer.fastplace.placement.PlacementUpdateMode;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.fastplace.world.BlockEditTransaction;
import io.github.fastformer.server.session.QuickShapeSystem;
import io.github.fastformer.server.session.GeometrySystem;
import io.github.fastformer.server.session.OperationSystem;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Empty-hand tinker admission and transactional application of the shared rules. */
public final class BlockTinker {
   private static final TinkerRules RULES = TinkerRules.create(StandardTinkerRules::register);

   private BlockTinker() {}

   public static boolean use(ServerPlayer player, BlockHitResult hit) {
      if (!canEdit(player) || !withinInteractionRange(player, hit)
         || HistorySystem.of(player).busy(player) || QuickShapeSystem.of(player).active(player)
         || OperationSystem.of(player).active(player) || GeometrySystem.of(player).active(player)
         || SessionSystem.of(player).placementTasks().active(player) || SessionSystem.of(player).operationTasks().active(player)) return false;
      FastPlaceSettings settings = FastPlaceSettings.load(player);
      if (!settings.enabled() || !settings.emptyHandWrench()) return false;
      ServerLevel level = player.serverLevel();
      TinkerAction action = previewAction(player, hit);
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

   /** Reads the same target and conversion on both sides without writing to the world. */
   public static Map<BlockPos, BlockState> preview(Player player, BlockHitResult hit) {
      return previewAction(player, hit).changes();
   }

   public static Map<BlockPos, BlockState> preview(Player player, BlockHitResult hit, boolean skipRepeatedCropStages) {
      return previewAction(player, hit, skipRepeatedCropStages).changes();
   }

   public static boolean consumes(Player player, BlockHitResult hit) { return previewAction(player, hit).consumed(); }

   public static boolean canEdit(Player player) {
      return player != null && player.isCreative() && player.getMainHandItem().isEmpty();
   }

   public static boolean withinInteractionRange(Player player, BlockHitResult hit) {
      return player != null && hit != null && hit.getType() == HitResult.Type.BLOCK
         && !LongRangeBlockRaycast.beyondInteractionRange(player, hit);
   }

   private static TinkerAction previewAction(Player player, BlockHitResult hit) {
      boolean skipRepeatedCropStages = !(player instanceof ServerPlayer serverPlayer)
         || FastPlaceSettings.load(serverPlayer).skipRepeatedCropStages();
      return previewAction(player, hit, skipRepeatedCropStages);
   }

   private static TinkerAction previewAction(Player player, BlockHitResult hit, boolean skipRepeatedCropStages) {
      if (!canEdit(player) || !withinInteractionRange(player, hit)) return TinkerAction.PASS;
      return resolveAction(player.level(), hit, player.isShiftKeyDown(), skipRepeatedCropStages);
   }

   static Map<BlockPos, BlockState> resolve(Level level, BlockHitResult hit) {
      return resolveAction(level, hit, false).changes();
   }

   static TinkerAction resolveAction(Level level, BlockHitResult hit, boolean shift) {
      return resolveAction(level, hit, shift, true);
   }

   static TinkerAction resolveAction(Level level, BlockHitResult hit, boolean shift, boolean skipRepeatedCropStages) {
      return RULES.resolve(TinkerContext.at(level, hit, shift, skipRepeatedCropStages));
   }
}
