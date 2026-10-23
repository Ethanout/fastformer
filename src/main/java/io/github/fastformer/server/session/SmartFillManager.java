package io.github.fastformer.server.session;

import io.github.fastformer.fastplace.fill.SmartFillSearch;
import io.github.fastformer.fastplace.geometry.raycast.LongRangeBlockRaycast;
import io.github.fastformer.fastplace.placement.context.PlaceableItems;
import io.github.fastformer.fastplace.placement.context.PlacementContextSnapshot;
import io.github.fastformer.fastplace.placement.replace.QuickReplaceManager;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.fastplace.text.FastPlaceMessages;
import io.github.fastformer.network.payload.placement.SmartFillCommandPayload;
import io.github.fastformer.network.payload.preview.SmartFillPreviewPayload;
import io.github.fastformer.network.sync.PlayerPreviewSync;
import io.github.fastformer.server.input.IdleInteractionSnapshots;
import io.github.fastformer.server.input.ServerInputDispatcher;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server-thread fill sessions own the plan until confirmation or cancellation. */
public final class SmartFillManager {
   private static final Map<UUID, Pending> SESSIONS = new java.util.HashMap<>();
   private static final Map<UUID, LinkedHashSet<UUID>> RECENT_REQUESTS = new java.util.HashMap<>();
   private record Pending(UUID id, ResourceKey<Level> dimension, SmartFillSearch.Plan plan,
                          BlockState material, Map<BlockPos, BlockState> evidence) { }
   private SmartFillManager() { }

   public static boolean active(ServerPlayer player) { return active(player.getUUID()); }
   public static boolean active(UUID owner) { return SESSIONS.containsKey(owner); }

   public static void handle(ServerPlayer player, SmartFillCommandPayload command) {
      if (!player.level().dimension().location().equals(command.dimension())) return;
      switch (command.action()) {
         case DISCOVER -> discover(player, command);
         case CONFIRM -> confirm(player, command.id());
         case CANCEL -> {
            remember(player.getUUID(), command.id());
            Pending pending = SESSIONS.get(player.getUUID());
            if (pending != null && pending.id().equals(command.id())) cancel(player);
         }
      }
   }

   private static boolean remember(UUID owner, UUID id) {
      var recent = RECENT_REQUESTS.computeIfAbsent(owner, ignored -> new LinkedHashSet<>());
      if (!recent.add(id)) return false;
      if (recent.size() > 64) recent.remove(recent.getFirst());
      return true;
   }

   private static void discover(ServerPlayer player, SmartFillCommandPayload command) {
      if (!remember(player.getUUID(), command.id())) return;
      if (!ServerInputDispatcher.canOperate(player) || ServerInputDispatcher.interactionBlocked(player)
         || !IdleInteractionSnapshots.idle(player) || QuickReplaceManager.active(player)
         || !PlaceableItems.isPlaceable(player.getMainHandItem())
         || !IdleInteractionSnapshots.validRay(player, command.eye(), command.view())) {
         finish(player, command.id());
         return;
      }
      var hit = LongRangeBlockRaycast.clip(player.level(), player, command.eye(), command.view()).hit();
      if (hit.getType() != HitResult.Type.BLOCK) { reject(player, command.id(), "fastformer.fill.aim_hint"); return; }
      var level = player.serverLevel();
      Map<BlockPos, BlockState> evidence = new LinkedHashMap<>();
      SmartFillSearch search = new SmartFillSearch(position -> read(level, position, evidence));
      BlockPos seed = BlockPos.containing(hit.getLocation().add(Vec3.atLowerCornerOf(hit.getDirection().getNormal()).scale(0.1)));
      var plan = search.find(seed, hit.getDirection());
      if (plan == null || plan.targets().isEmpty()) { reject(player, command.id(), "fastformer.fill.not_found"); return; }
      if (plan.targets().size() > FastPlaceSettings.load(player).maxPlacement()) {
         reject(player, command.id(), "fastformer.fill.too_large"); return;
      }
      var snapshot = PlacementContextSnapshot.capture(level, player, player.getMainHandItem(), hit, false)
         .anchoredAt(plan.targets().iterator().next());
      var state = PlaceableItems.placementState(player.getMainHandItem(), player, snapshot).orElse(null);
      if (state == null || state.isAir()) { reject(player, command.id(), "fastformer.fill.not_found"); return; }
      Pending pending = new Pending(command.id(), level.dimension(), plan, state, Map.copyOf(evidence));
      boolean touchesPlayer = plan.targets().stream().anyMatch(position -> player.getBoundingBox().intersects(new AABB(position)));
      if (touchesPlayer) {
         SESSIONS.put(player.getUUID(), pending);
         publish(player, pending);
      } else {
         submit(player, pending);
         finish(player, command.id());
      }
   }

   private static SmartFillSearch.Cell read(ServerLevel level, BlockPos position, Map<BlockPos, BlockState> evidence) {
      if (!loaded(level, position)) return SmartFillSearch.Cell.UNKNOWN;
      BlockState state = level.getBlockState(position);
      evidence.put(position.immutable(), state);
      if (state.isAir()) return SmartFillSearch.Cell.AIR;
      // Plants, fluids and incomplete models are neither fill targets nor sealed walls.
      return state.getFluidState().isEmpty() && state.isCollisionShapeFullBlock(level, position)
         ? SmartFillSearch.Cell.SOLID : SmartFillSearch.Cell.UNKNOWN;
   }

   private static boolean loaded(ServerLevel level, BlockPos position) {
      return level.isInWorldBounds(position) && level.getWorldBorder().isWithinBounds(position) && level.hasChunkAt(position);
   }

   public static void confirm(ServerPlayer player, UUID id) {
      Pending pending = SESSIONS.get(player.getUUID());
      if (pending == null || !pending.id().equals(id)) return;
      SESSIONS.remove(player.getUUID());
      if (!ServerInputDispatcher.canOperate(player) || !player.level().dimension().equals(pending.dimension())) {
         finish(player, id); return;
      }
      boolean unchanged = pending.evidence().entrySet().stream().allMatch(entry ->
         loaded(player.serverLevel(), entry.getKey()) && player.level().getBlockState(entry.getKey()).equals(entry.getValue()));
      if (!unchanged) reject(player, id, "fastformer.fill.changed");
      else { submit(player, pending); finish(player, id); }
   }

   private static void submit(ServerPlayer player, Pending pending) {
      if (!FastPlaceManager.queueAirFill(player, pending.plan().targets(), pending.material())) {
         FastPlaceMessages.actionBar(player, FastPlaceMessages.text("fastformer.message.placement_confirm_failed"));
      } else {
         // Publish task ownership before ending the preview on the same connection.
         io.github.fastformer.network.FastPlaceNetwork.syncActivity(player);
      }
   }

   public static void cancel(ServerPlayer player) {
      Pending pending = SESSIONS.remove(player.getUUID());
      if (pending != null) finish(player, pending.id());
   }

   private static void reject(ServerPlayer player, UUID id, String message) {
      FastPlaceMessages.actionBar(player, FastPlaceMessages.text(message));
      finish(player, id);
   }
   private static void publish(ServerPlayer player, Pending pending) {
      if (!player.connection.hasChannel(SmartFillPreviewPayload.TYPE)) return;
      PacketDistributor.sendToPlayer(player, new SmartFillPreviewPayload(pending.id(), pending.material(),
         List.copyOf(pending.plan().targets()), PlayerPreviewSync.callbackScope(player)));
   }
   private static void finish(ServerPlayer player, UUID id) {
      if (!player.connection.hasChannel(SmartFillPreviewPayload.TYPE)) return;
      PacketDistributor.sendToPlayer(player, new SmartFillPreviewPayload(id, Blocks.AIR.defaultBlockState(),
         List.of(), PlayerPreviewSync.callbackScope(player)));
   }
   public static void forget(UUID owner) { SESSIONS.remove(owner); RECENT_REQUESTS.remove(owner); }
   public static void clear() { SESSIONS.clear(); RECENT_REQUESTS.clear(); }
}
