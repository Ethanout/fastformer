package io.github.fastformer.server.input;

import io.github.fastformer.common.placement.context.PlacementContexts;

import io.github.fastformer.server.history.HistorySystem;

import io.github.fastformer.common.raycast.InteractionShapeSnapshot;
import io.github.fastformer.fastplace.interaction.EntryDoubleClickTolerance;
import io.github.fastformer.common.raycast.InteractionRaycastRules;
import io.github.fastformer.common.placement.PlacementContextSnapshot;
import io.github.fastformer.common.placement.context.PlaceableItems;
import io.github.fastformer.protocol.c2s.placement.IdleInteractionCapturePayload;
import io.github.fastformer.protocol.s2c.placement.IdleInteractionSnapshotPayload;
import io.github.fastformer.server.session.QuickShapeSystem;
import io.github.fastformer.server.session.OperationSystem;
import io.github.fastformer.server.session.GeometrySystem;
import io.github.fastformer.core.lifecycle.Scoped;
import io.github.fastformer.core.lifecycle.ScopedKey;
import io.github.fastformer.server.player.PlayerStateRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server-thread snapshots pair ordinary interactions with an explicit session entry. */
public final class IdleInteractionSnapshots {
   private static final ScopedKey<State> STATE = PlayerStateRegistry.key(
      ResourceLocation.parse("fastformer:idle_interaction"), State::new);

   private static final class State implements Scoped {
      private Capture capture;
      private long tinkerToken;
      @Override public void onDetach() { onDiscard(); }
      @Override public void onDimensionChanged() { onDiscard(); }
      @Override public void onDiscard() { capture = null; tinkerToken = 0; }
   }

   private static State state(ServerPlayer player) { return PlayerStateRegistry.of(player).get(STATE); }
   private record Capture(long token, Level level, long tick, ItemStack item, InteractionShapeSnapshot shape,
      BlockPos clicked, BlockPos adjacent, boolean placed, IdleTinkerSnapshot tinker, PlacementContextSnapshot placement,
      InteractionRaycastRules rules, EntryDoubleClickTolerance tolerance) { }
   public record Entry(BlockHitResult hit, PlacementContextSnapshot placement, InteractionRaycastRules rules) { }
   private IdleInteractionSnapshots() { }

   public static boolean idle(ServerPlayer player) {
      return !QuickShapeSystem.of(player).active(player) && !OperationSystem.of(player).active(player) && !GeometrySystem.of(player).active(player)
         && !io.github.fastformer.server.fill.SmartFillSystem.active(player);
   }

   public static boolean validRay(ServerPlayer player, Vec3 eye, Vec3 view) {
      return Double.isFinite(eye.x) && Double.isFinite(eye.y) && Double.isFinite(eye.z)
         && Double.isFinite(view.x) && Double.isFinite(view.y) && Double.isFinite(view.z)
         && Math.abs(view.lengthSqr() - 1) < 0.001 && eye.distanceToSqr(player.getEyePosition()) <= 64;
   }

   public static void capture(ServerPlayer player, IdleInteractionCapturePayload request) {
      capture(player, request, true);
   }

   private static void capture(ServerPlayer player, IdleInteractionCapturePayload request, boolean sendShape) {
      state(player).capture = null;
      if (request.token() <= 0 || !ServerInputDispatcher.canOperate(player) || !idle(player)
         || ServerInputDispatcher.interactionBlocked(player) || !validRay(player, request.eye(), request.view())) return;
      var level = player.serverLevel();
      var position = request.hit().getBlockPos();
      if (!level.isInWorldBounds(position) || !level.hasChunkAt(position) || !level.getWorldBorder().isWithinBounds(position)) return;
      var shape = io.github.fastformer.fastplace.geometry.raycast.SessionEntryRaycast.snapshot(player, position, request.rules());
      var actual = io.github.fastformer.fastplace.geometry.raycast.SessionEntryRaycast.clip(
         player, request.eye(), request.view(), request.rules());
      if (shape.clip(request.eye(), request.view()) == null || !actual.getBlockPos().equals(position)
         || actual.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) return;
      ItemStack item = player.getMainHandItem().copy();
      PlacementContextSnapshot placement = null;
      if (PlaceableItems.isPlaceable(item)) {
         placement = PlacementContexts.capture(level, player, item, request.hit(), request.rules().embedded());
         var state = PlaceableItems.placementState(item, player, placement);
         if (state.isPresent()) placement = placement.withCapturedState(item.getItem(), state.orElseThrow());
      }
      var capture = new Capture(request.token(), level, level.getGameTime(), item, shape,
         position.immutable(), position.relative(request.hit().getDirection()), false, null, placement, request.rules(),
         EntryDoubleClickTolerance.capture(shape, request.eye(), request.view()));
      state(player).capture = capture;
      if (sendShape) publish(player, capture);
   }

   public static void placed(ServerPlayer player, BlockPos position) {
      var capture = current(player);
      if (capture == null || capture.placed() || player.level().getGameTime() - capture.tick() > 1
         || !position.equals(capture.clicked()) && !position.equals(capture.adjacent())) return;
      var placement = capture.placement() == null ? null : capture.placement().anchoredAt(position)
         .withCapturedState(capture.item().getItem(), player.level().getBlockState(position));
      var updated = new Capture(capture.token(), capture.level(), capture.tick(), capture.item(), capture.shape(),
         capture.clicked(), capture.adjacent(), true, capture.tinker(), placement, capture.rules(), capture.tolerance());
      state(player).capture = updated;
      publish(player, updated);
   }

   public static BlockHitResult consume(ServerPlayer player, long token, Vec3 eye, Vec3 view) {
      var entry = consumeEntry(player, token, eye, view);
      return entry == null ? null : entry.hit();
   }

   public static Entry consumeEntry(ServerPlayer player, long token, Vec3 eye, Vec3 view) {
      var capture = current(player);
      if (capture == null || capture.token() != token || !validRay(player, eye, view)) return null;
      var hit = capture.tolerance().clip(capture.shape(), eye, view);
      if (hit == null) return null;
      state(player).capture = null;
      if (capture.tinker() != null && !capture.tinker().restore(player)) {
         player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("fastformer.message.idle_entry_changed"), true);
         return null;
      }
      return new Entry(hit, capture.placement(), capture.rules());
   }

   public static void tinker(ServerPlayer player, io.github.fastformer.protocol.c2s.placement.TinkerInteractionPayload request) {
      if (!io.github.fastformer.fastplace.interaction.BlockTinker.withinInteractionRange(player, request.hit())) return;
      if (request.token() <= state(player).tinkerToken) return;
      state(player).tinkerToken = request.token();
      capture(player, new IdleInteractionCapturePayload(request.token(), request.hit(), request.eye(), request.view(),
         InteractionRaycastRules.VANILLA_OUTLINE), false);
      var capture = current(player);
      if (capture == null) return;
      var changes = io.github.fastformer.fastplace.interaction.BlockTinker.preview(player, request.hit());
      var originals = IdleTinkerSnapshot.capture(player.serverLevel(), changes.keySet());
      if (originals == null) { state(player).capture = null; return; }
      if (!io.github.fastformer.fastplace.interaction.BlockTinker.use(player, request.hit())) { state(player).capture = null; return; }
      var changed = IdleTinkerSnapshot.capture(player.serverLevel(), changes.keySet());
      if (changed == null) { state(player).capture = null; return; }
      var updated = new Capture(capture.token(), capture.level(), capture.tick(), capture.item(),
         capture.shape(), capture.clicked(), capture.adjacent(), true,
         new IdleTinkerSnapshot(originals, changed, HistorySystem.of(player).latestOperationId(player)),
         capture.placement(), capture.rules(), capture.tolerance());
      state(player).capture = updated;
      publish(player, updated);
   }

   private static Capture current(ServerPlayer player) {
      var capture = state(player).capture;
      if (capture == null) return null;
      long elapsed = player.level().getGameTime() - capture.tick();
      if (capture.level() != player.level() || elapsed < 0 || elapsed > 20
         || !ItemStack.isSameItemSameComponents(capture.item(), player.getMainHandItem()) || !idle(player)) {
         state(player).capture = null;
         return null;
      }
      return capture;
   }

   private static void publish(ServerPlayer player, Capture capture) {
      if (player.connection.hasChannel(IdleInteractionSnapshotPayload.TYPE)) {
         PacketDistributor.sendToPlayer(player, new IdleInteractionSnapshotPayload(capture.token(), player.level().dimension().location(), capture.shape()));
      }
   }

}
