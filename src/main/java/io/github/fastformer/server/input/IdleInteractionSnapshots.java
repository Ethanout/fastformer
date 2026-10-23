package io.github.fastformer.server.input;

import io.github.fastformer.fastplace.interaction.InteractionShapeSnapshot;
import io.github.fastformer.fastplace.placement.context.PlacementContextSnapshot;
import io.github.fastformer.fastplace.placement.context.PlaceableItems;
import io.github.fastformer.network.payload.placement.IdleInteractionCapturePayload;
import io.github.fastformer.network.payload.placement.IdleInteractionSnapshotPayload;
import io.github.fastformer.server.session.FastPlaceManager;
import io.github.fastformer.server.session.OperationManager;
import io.github.fastformer.server.session.GeometryManager;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server-thread snapshots pair ordinary interactions with an explicit session entry. */
public final class IdleInteractionSnapshots {
   private static final Map<ServerPlayer, Capture> CAPTURES = new WeakHashMap<>();
   private static final Map<ServerPlayer, Long> TINKER_TOKENS = new WeakHashMap<>();
   private record Capture(long token, Level level, long tick, ItemStack item, InteractionShapeSnapshot shape,
      BlockPos clicked, BlockPos adjacent, boolean placed, IdleTinkerSnapshot tinker, PlacementContextSnapshot placement) { }
   public record Entry(BlockHitResult hit, PlacementContextSnapshot placement) { }
   private IdleInteractionSnapshots() { }

   public static boolean idle(ServerPlayer player) {
      return !FastPlaceManager.active(player) && !OperationManager.active(player) && !GeometryManager.active(player)
         && !io.github.fastformer.server.session.SmartFillManager.active(player);
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
      CAPTURES.remove(player);
      if (request.token() <= 0 || !ServerInputDispatcher.canOperate(player) || !idle(player)
         || ServerInputDispatcher.interactionBlocked(player) || !validRay(player, request.eye(), request.view())) return;
      var level = player.serverLevel();
      var position = request.hit().getBlockPos();
      if (!level.isInWorldBounds(position) || !level.hasChunkAt(position) || !level.getWorldBorder().isWithinBounds(position)) return;
      var shape = io.github.fastformer.fastplace.geometry.raycast.SessionEntryRaycast.snapshot(level, position);
      var actual = io.github.fastformer.fastplace.geometry.raycast.SessionEntryRaycast.clip(
         player, request.eye(), request.view(), false);
      if (shape.clip(request.eye(), request.view()) == null || !actual.getBlockPos().equals(position)
         || actual.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) return;
      ItemStack item = player.getMainHandItem().copy();
      PlacementContextSnapshot placement = null;
      if (PlaceableItems.isPlaceable(item)) {
         placement = PlacementContextSnapshot.capture(level, player, item, actual, false);
         var state = PlaceableItems.placementState(item, player, placement);
         if (state.isPresent()) placement = placement.withCapturedState(item.getItem(), state.orElseThrow());
         placement = placement.anchoredAt(position);
      }
      var capture = new Capture(request.token(), level, level.getGameTime(), item, shape,
         position.immutable(), position.relative(actual.getDirection()), false, null, placement);
      CAPTURES.put(player, capture);
      if (sendShape) publish(player, capture);
   }

   public static void placed(ServerPlayer player, BlockPos position) {
      var capture = current(player);
      if (capture == null || capture.placed() || player.level().getGameTime() - capture.tick() > 1
         || !position.equals(capture.clicked()) && !position.equals(capture.adjacent())) return;
      var placement = capture.placement() == null ? null : capture.placement().anchoredAt(position)
         .withCapturedState(capture.item().getItem(), player.level().getBlockState(position));
      var updated = new Capture(capture.token(), capture.level(), capture.tick(), capture.item(), capture.shape(),
         capture.clicked(), capture.adjacent(), true, capture.tinker(), placement);
      CAPTURES.put(player, updated);
      publish(player, updated);
   }

   public static BlockHitResult consume(ServerPlayer player, long token, Vec3 eye, Vec3 view) {
      var entry = consumeEntry(player, token, eye, view);
      return entry == null ? null : entry.hit();
   }

   public static Entry consumeEntry(ServerPlayer player, long token, Vec3 eye, Vec3 view) {
      var capture = current(player);
      if (capture == null || capture.token() != token || !validRay(player, eye, view)) return null;
      var hit = capture.shape().clip(eye, view);
      if (hit == null) return null;
      CAPTURES.remove(player);
      if (capture.tinker() != null && !capture.tinker().restore(player)) {
         player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("fastformer.message.idle_entry_changed"), true);
         return null;
      }
      return new Entry(hit, capture.placement());
   }

   public static void tinker(ServerPlayer player, io.github.fastformer.network.payload.placement.TinkerInteractionPayload request) {
      if (!io.github.fastformer.fastplace.interaction.BlockTinker.withinInteractionRange(player, request.hit())) return;
      if (request.token() <= TINKER_TOKENS.getOrDefault(player, 0L)) return;
      TINKER_TOKENS.put(player, request.token());
      capture(player, new IdleInteractionCapturePayload(request.token(), request.hit(), request.eye(), request.view()), false);
      var capture = current(player);
      if (capture == null) return;
      var changes = io.github.fastformer.fastplace.interaction.BlockTinker.preview(player, request.hit());
      var originals = IdleTinkerSnapshot.capture(player.serverLevel(), changes.keySet());
      if (originals == null) { CAPTURES.remove(player); return; }
      if (!io.github.fastformer.fastplace.interaction.BlockTinker.use(player, request.hit())) { CAPTURES.remove(player); return; }
      var changed = IdleTinkerSnapshot.capture(player.serverLevel(), changes.keySet());
      if (changed == null) { CAPTURES.remove(player); return; }
      var updated = new Capture(capture.token(), capture.level(), capture.tick(), capture.item(),
         capture.shape(), capture.clicked(), capture.adjacent(), true,
         new IdleTinkerSnapshot(originals, changed, io.github.fastformer.fastplace.history.WorldHistoryManager.latestOperationId(player)), capture.placement());
      CAPTURES.put(player, updated);
      publish(player, updated);
   }

   private static Capture current(ServerPlayer player) {
      var capture = CAPTURES.get(player);
      if (capture == null) return null;
      long elapsed = player.level().getGameTime() - capture.tick();
      if (capture.level() != player.level() || elapsed < 0 || elapsed > 20
         || !ItemStack.isSameItemSameComponents(capture.item(), player.getMainHandItem()) || !idle(player)) {
         CAPTURES.remove(player);
         return null;
      }
      return capture;
   }

   private static void publish(ServerPlayer player, Capture capture) {
      if (player.connection.hasChannel(IdleInteractionSnapshotPayload.TYPE)) {
         PacketDistributor.sendToPlayer(player, new IdleInteractionSnapshotPayload(capture.token(), player.level().dimension().location(), capture.shape()));
      }
   }

   public static void remove(ServerPlayer player) { CAPTURES.remove(player); TINKER_TOKENS.remove(player); }
   public static void clear() { CAPTURES.clear(); TINKER_TOKENS.clear(); }
}
