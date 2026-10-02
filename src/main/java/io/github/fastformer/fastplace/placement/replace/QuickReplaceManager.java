package io.github.fastformer.fastplace.placement.replace;

import io.github.fastformer.fastplace.history.WorldHistoryManager;
import io.github.fastformer.fastplace.interaction.BlockTinker;
import io.github.fastformer.fastplace.placement.PlacementUpdateMode;
import io.github.fastformer.fastplace.placement.context.PlaceableItems;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.fastplace.world.*;
import io.github.fastformer.server.input.ServerInputDispatcher;
import io.github.fastformer.server.session.FastPlaceManager;
import io.github.fastformer.server.session.GeometryManager;
import io.github.fastformer.server.session.OperationManager;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

/** Authoritative Axiom-style replacement using the held block's placement state. */
public final class QuickReplaceManager {
   private static final java.util.Set<java.util.UUID> ACTIVE = new java.util.HashSet<>();
   private QuickReplaceManager() {
   }

   public static void forget(java.util.UUID owner) {
      ACTIVE.remove(owner);
      QuickReplaceDedupe.forget(owner);
   }

   public static void clearAll() {
      ACTIVE.clear();
      QuickReplaceDedupe.clearAll();
   }

   public static boolean active(ServerPlayer player) {
      return player != null && ACTIVE.contains(player.getUUID());
   }

   public static boolean setActive(ServerPlayer player, boolean active) {
      if (player == null) return false;
      forget(player.getUUID());
      if (!active) return false;
      if (!ServerInputDispatcher.canOperate(player) || WorldHistoryManager.busy(player)
         || FastPlaceManager.active(player) || OperationManager.active(player) || GeometryManager.active(player)
         || FastPlaceManager.taskActive(player) || OperationManager.taskActive(player)) return false;
      ACTIVE.add(player.getUUID());
      return true;
   }

   public static boolean replaceCrosshair(ServerPlayer player) {
      if (!admitted(player)) {
         return false;
      }
      ServerLevel level = player.serverLevel();
      if (!QuickReplaceDedupe.accept(player.getUUID(), player.getServer().getTickCount())) {
         return false;
      }
      QuickReplaceTarget target = QuickReplaceTarget.resolve(player);
      if (target == null) return false;
      BlockPos pos = target.position();
      BlockState beforeState = level.getBlockState(pos);
      BlockState afterState = target.state();
      if (beforeState.equals(afterState)) {
         return false;
      }
      // A short transaction checks its own recovery result and keeps the lease
      // when a restore does not complete. Only "no change applies here" leaves
      // the world untouched and unreported.
      ShortWriteTransaction.Outcome outcome = ShortWriteTransaction.apply(
         player,
         level,
         Map.of(pos, afterState),
         FastPlaceSettings.load(player).placementUpdateMode().flags(),
         PlacementUpdateMode.CLIENT_ONLY.flags()
      );
      ShortWriteTransaction.report(outcome, player);
      return outcome == ShortWriteTransaction.Outcome.APPLIED;
   }

   /**
    * A world write may only start when no history, recovery, task or editing
    * session owns this player. The task checks match {@link BlockTinker} so a
    * short operation cannot start while a queued task still holds the lease.
    */
   private static boolean admitted(ServerPlayer player) {
      return player != null
         && active(player)
         && !WorldHistoryManager.busy(player)
         && ServerInputDispatcher.canOperate(player)
         && !FastPlaceManager.active(player)
         && !OperationManager.active(player)
         && !GeometryManager.active(player)
         && !FastPlaceManager.taskActive(player)
         && !OperationManager.taskActive(player)
         && PlaceableItems.isPlaceable(player.getMainHandItem());
   }

}
