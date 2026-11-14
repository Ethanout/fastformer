package io.github.fastformer.server.fill;

import io.github.fastformer.common.placement.context.PlacementContexts;

import io.github.fastformer.fastplace.fill.SmartFillSearch;
import io.github.fastformer.common.raycast.LongRangeBlockRaycast;
import io.github.fastformer.common.placement.context.PlaceableItems;
import io.github.fastformer.common.placement.PlacementContextSnapshot;
import io.github.fastformer.server.replace.QuickReplaceSystem;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.server.feedback.PlayerMessages;
import io.github.fastformer.protocol.c2s.placement.SmartFillCommandPayload;
import io.github.fastformer.protocol.s2c.preview.SmartFillPreviewPayload;
import io.github.fastformer.server.sync.PreviewSync;
import io.github.fastformer.server.input.IdleInteractionSnapshots;
import io.github.fastformer.server.input.ServerInputDispatcher;
import io.github.fastformer.server.session.QuickShapeSystem;
import io.github.fastformer.server.player.PlayerStateRegistry;
import io.github.fastformer.core.lifecycle.Scoped;
import io.github.fastformer.core.lifecycle.ScopedKey;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
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
public final class SmartFillSystem {
   private static final ScopedKey<State> STATE = PlayerStateRegistry.key(ResourceLocation.parse("fastformer:smart_fill"), State::new);
   private record Pending(UUID id, ResourceKey<Level> dimension, SmartFillSearch.Plan plan,
                          BlockState material, Map<BlockPos, BlockState> evidence) { }
   private SmartFillSystem() { }

   public static boolean active(ServerPlayer player) {
      return PlayerStateRegistry.find(player).flatMap(scope -> scope.find(STATE)).map(state -> state.pending != null).orElse(false);
   }

   private static State state(ServerPlayer player) { return PlayerStateRegistry.of(player).get(STATE); }

   public static void handle(ServerPlayer player, SmartFillCommandPayload command) {
      if (!player.level().dimension().location().equals(command.dimension())) return;
      switch (command.action()) {
         case DISCOVER -> discover(player, command);
         case CONFIRM -> confirm(player, command.id());
         case CANCEL -> {
            remember(player, command.id());
            Pending pending = state(player).pending;
            if (pending != null && pending.id().equals(command.id())) cancel(player);
         }
      }
   }

   private static boolean remember(ServerPlayer player, UUID id) {
      var recent = state(player).recent;
      if (!recent.add(id)) return false;
      if (recent.size() > 64) recent.remove(recent.getFirst());
      return true;
   }

   private static void discover(ServerPlayer player, SmartFillCommandPayload command) {
      if (!remember(player, command.id())) return;
      if (!ServerInputDispatcher.canOperate(player) || ServerInputDispatcher.interactionBlocked(player)
         || !IdleInteractionSnapshots.idle(player) || QuickReplaceSystem.active(player)
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
      var snapshot = PlacementContexts.capture(level, player, player.getMainHandItem(), hit, false)
         .anchoredAt(plan.targets().iterator().next());
      var state = PlaceableItems.placementState(player.getMainHandItem(), player, snapshot).orElse(null);
      if (state == null || state.isAir()) { reject(player, command.id(), "fastformer.fill.not_found"); return; }
      Pending pending = new Pending(command.id(), level.dimension(), plan, state, Map.copyOf(evidence));
      boolean touchesPlayer = plan.targets().stream().anyMatch(position -> player.getBoundingBox().intersects(new AABB(position)));
      if (touchesPlayer) {
         state(player).pending = pending;
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
      Pending pending = state(player).pending;
      if (pending == null || !pending.id().equals(id)) return;
      state(player).pending = null;
      if (!ServerInputDispatcher.canOperate(player) || !player.level().dimension().equals(pending.dimension())) {
         finish(player, id); return;
      }
      boolean unchanged = pending.evidence().entrySet().stream().allMatch(entry ->
         loaded(player.serverLevel(), entry.getKey()) && player.level().getBlockState(entry.getKey()).equals(entry.getValue()));
      if (!unchanged) reject(player, id, "fastformer.fill.changed");
      else { submit(player, pending); finish(player, id); }
   }

   private static void submit(ServerPlayer player, Pending pending) {
      if (!QuickShapeSystem.of(player).queueAirFill(player, pending.plan().targets(), pending.material())) {
         PlayerMessages.actionBar(player, PlayerMessages.text("fastformer.message.placement_confirm_failed"));
      } else {
         // Publish task ownership before ending the preview on the same connection.
         io.github.fastformer.network.FastPlaceNetwork.syncActivity(player);
      }
   }

   public static void cancel(ServerPlayer player) {
      Pending pending = state(player).pending;
      state(player).pending = null;
      if (pending != null) finish(player, pending.id());
   }

   private static void reject(ServerPlayer player, UUID id, String message) {
      PlayerMessages.actionBar(player, PlayerMessages.text(message));
      finish(player, id);
   }
   private static void publish(ServerPlayer player, Pending pending) {
      if (!player.connection.hasChannel(SmartFillPreviewPayload.TYPE)) return;
      PacketDistributor.sendToPlayer(player, new SmartFillPreviewPayload(pending.id(), pending.material(),
         List.copyOf(pending.plan().targets()), PreviewSync.callbackScope(player)));
   }
   private static void finish(ServerPlayer player, UUID id) {
      if (!player.connection.hasChannel(SmartFillPreviewPayload.TYPE)) return;
      PacketDistributor.sendToPlayer(player, new SmartFillPreviewPayload(id, Blocks.AIR.defaultBlockState(),
         List.of(), PreviewSync.callbackScope(player)));
   }
   public static void forget(ServerPlayer player) {
      PlayerStateRegistry.find(player).flatMap(scope -> scope.find(STATE)).ifPresent(State::clear);
   }

   private static final class State implements Scoped {
      private Pending pending;
      private final LinkedHashSet<UUID> recent = new LinkedHashSet<>();
      private void clear() { pending = null; recent.clear(); }
      @Override public void onDetach() { clear(); }
      @Override public void onDimensionChanged() { clear(); }
      @Override public void onDiscard() { clear(); }
   }
}
