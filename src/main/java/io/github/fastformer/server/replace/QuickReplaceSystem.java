package io.github.fastformer.server.replace;

import io.github.fastformer.common.placement.context.QuickReplaceTargets;

import io.github.fastformer.server.session.SessionSystem;

import io.github.fastformer.server.history.HistorySystem;
import io.github.fastformer.common.replace.QuickReplaceSweep;
import io.github.fastformer.common.replace.QuickReplaceTarget;

import io.github.fastformer.fastplace.interaction.BlockTinker;
import io.github.fastformer.fastplace.placement.PlacementUpdateMode;
import io.github.fastformer.common.placement.context.PlaceableItems;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.fastplace.world.*;
import io.github.fastformer.server.input.ServerInputDispatcher;
import io.github.fastformer.server.session.QuickShapeSystem;
import io.github.fastformer.server.session.GeometrySystem;
import io.github.fastformer.server.session.OperationSystem;
import io.github.fastformer.core.lifecycle.Scoped;
import io.github.fastformer.core.lifecycle.ScopedKey;
import io.github.fastformer.server.player.PlayerStateRegistry;
import net.minecraft.resources.ResourceLocation;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

/** Authoritative Axiom-style replacement using the held block's placement state. */
public final class QuickReplaceSystem {
   private static final ScopedKey<State> STATE = PlayerStateRegistry.key(ResourceLocation.parse("fastformer:quick_replace"), State::new);
   private QuickReplaceSystem() {
   }

   public static void forget(ServerPlayer player) {
      PlayerStateRegistry.find(player).flatMap(scope -> scope.find(STATE)).ifPresent(State::clear);
   }

   private static State state(ServerPlayer player) { return PlayerStateRegistry.of(player).get(STATE); }

   public static boolean active(ServerPlayer player) {
      return player != null && PlayerStateRegistry.find(player).flatMap(scope -> scope.find(STATE))
         .map(state -> state.active).orElse(false);
   }

   public static boolean setActive(ServerPlayer player, boolean active) {
      if (player == null) return false;
      forget(player);
      if (!active) return false;
      if (!ServerInputDispatcher.canOperate(player) || ServerInputDispatcher.interactionBlocked(player) || HistorySystem.of(player).busy(player)
         || QuickShapeSystem.of(player).active(player) || OperationSystem.of(player).active(player) || GeometrySystem.of(player).active(player)
         || SessionSystem.of(player).placementTasks().active(player) || SessionSystem.of(player).operationTasks().active(player)) return false;
      state(player).active = true;
      return true;
   }

   public static boolean replaceCrosshair(ServerPlayer player) {
      return admitted(player) && state(player).dedupe.accept(player.getServer().getTickCount())
         && replaceSample(player, new QuickReplaceStroke(0));
   }

   public static boolean sample(ServerPlayer player, long strokeId, boolean finished) {
      if (player == null || !active(player) || strokeId <= 0) return false;
      State state = state(player);
      var stroke = state.stroke;
      if (finished) {
         if (stroke != null && stroke.id <= strokeId) state.stroke = null;
         state.lastStroke = Math.max(state.lastStroke, strokeId);
         return false;
      }
      if (stroke == null || stroke.id != strokeId) {
         if (strokeId <= state.lastStroke) return false;
         stroke = new QuickReplaceStroke(strokeId);
         state.stroke = stroke;
         state.lastStroke = strokeId;
      }
      if (!admitted(player)) { stroke.previous = null; return false; }
      return replaceSample(player, stroke);
   }

   private static boolean replaceSample(ServerPlayer player, QuickReplaceStroke stroke) {
      long tick = player.getServer().getTickCount();
      if (stroke.lastSampleTick == tick) return false;
      if (tick - stroke.lastSampleTick > 10 || stroke.item == null
         || !net.minecraft.world.item.ItemStack.isSameItemSameComponents(stroke.item, player.getMainHandItem())) {
         stroke.previous = null;
      }
      stroke.lastSampleTick = tick;
      stroke.item = player.getMainHandItem().copy();
      var ray = new QuickReplaceSweep.Ray(player.getEyePosition(), player.getViewVector(1));
      var current = new QuickReplaceSweep.Sample(ray, QuickReplaceTargets.raycast(player, ray.eye(), ray.view()));
      Map<BlockPos, BlockState> changes = new java.util.LinkedHashMap<>();
      var samples = QuickReplaceSweep.trace(stroke.previous, current,
         sampleRay -> QuickReplaceTargets.raycast(player, sampleRay.eye(), sampleRay.view()));
      for (var sample : samples) {
         var target = QuickReplaceTargets.resolve(player, sample.hit());
         if (target != null && !player.level().getBlockState(target.position()).equals(target.state())) {
            changes.putIfAbsent(target.position(), target.state());
         }
      }
      stroke.previous = current.hit() == null ? null : current;
      if (changes.isEmpty()) return false;
      var outcome = BlockEditTransaction.applyGesture(player, player.serverLevel(), changes,
         FastPlaceSettings.load(player).placementUpdateMode().flags(), PlacementUpdateMode.CLIENT_ONLY.flags(), stroke.history);
      if (outcome != BlockEditTransaction.Outcome.APPLIED) stroke.previous = null;
      BlockEditTransaction.report(outcome, player);
      return outcome == BlockEditTransaction.Outcome.APPLIED;
   }

   /**
    * A world write may only start when no history, recovery, task or editing
    * session owns this player. The task checks match {@link BlockTinker} so a
    * short operation cannot start while a queued task still holds the lease.
    */
   private static boolean admitted(ServerPlayer player) {
      return player != null
         && active(player)
         && !HistorySystem.of(player).busy(player)
         && ServerInputDispatcher.canOperate(player)
         && !ServerInputDispatcher.interactionBlocked(player)
         && !QuickShapeSystem.of(player).active(player)
         && !OperationSystem.of(player).active(player)
         && !GeometrySystem.of(player).active(player)
         && !SessionSystem.of(player).placementTasks().active(player)
         && !SessionSystem.of(player).operationTasks().active(player)
         && PlaceableItems.isPlaceable(player.getMainHandItem());
   }

   private static final class State implements Scoped {
      private boolean active;
      private QuickReplaceStroke stroke;
      private long lastStroke;
      private final QuickReplaceDedupe dedupe = new QuickReplaceDedupe();
      private void clear() { active = false; stroke = null; lastStroke = 0; dedupe.clear(); }
      @Override public void onDetach() { clear(); }
      @Override public void onDimensionChanged() { clear(); }
      @Override public void onDiscard() { clear(); }
   }
}
