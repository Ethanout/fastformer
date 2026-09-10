package io.github.fastformer.fastplace.events;

import io.github.fastformer.fastplace.world.*;
import io.github.fastformer.fastplace.command.FastPlaceCommandRegistry;

import io.github.fastformer.network.FastPlaceNetwork;
import io.github.fastformer.fastplace.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingSwapItemsEvent.Hands;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock.Action;
import net.neoforged.neoforge.event.tick.PlayerTickEvent.Post;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public final class FastPlaceEvents {
   private FastPlaceEvents() {
   }

   public static void register() {
      NeoForge.EVENT_BUS.addListener(FastPlaceEvents::onRightClickBlock);
      NeoForge.EVENT_BUS.addListener(FastPlaceEvents::onRightClickItem);
      NeoForge.EVENT_BUS.addListener(FastPlaceEvents::onLeftClickBlock);
      NeoForge.EVENT_BUS.addListener(FastPlaceEvents::onItemToss);
      NeoForge.EVENT_BUS.addListener(FastPlaceEvents::onSwapHands);
      NeoForge.EVENT_BUS.addListener(FastPlaceEvents::onPlayerTick);
      NeoForge.EVENT_BUS.addListener(FastPlaceEvents::onServerTick);
      PlayerLifecycleEvents.register();
      NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, WorldWriteSideEffectGuard::onEntityJoin);
      FastPlaceCommandRegistry.register();
   }

   private static void onRightClickBlock(RightClickBlock event) {
      if (!(event.getEntity() instanceof ServerPlayer player) || event.getHand() != InteractionHand.MAIN_HAND) {
         return;
      }

      if (ServerInputDispatcher.interactionBlocked(player)) {
         event.setCanceled(true);
         return;
      }

      if (BlockTinker.use(player, event.getHitVec())) {
         event.setCancellationResult(InteractionResult.SUCCESS);
         event.setCanceled(true);
         return;
      }

      if (ServerInputDispatcher.rightClickBlock(player, event.getHitVec())) {
         event.setCancellationResult(InteractionResult.SUCCESS);
         event.setCanceled(true);
      }
   }

   private static void onRightClickItem(RightClickItem event) {
      if (!(event.getEntity() instanceof ServerPlayer player) || event.getHand() != InteractionHand.MAIN_HAND) {
         return;
      }

      if (ServerInputDispatcher.interactionBlocked(player)) {
         event.setCanceled(true);
         return;
      }

      if (ServerInputDispatcher.rightClickItem(player)) {
         event.setCancellationResult(InteractionResult.SUCCESS);
         event.setCanceled(true);
      }
   }

   private static void onLeftClickBlock(LeftClickBlock event) {
      if (!(event.getEntity() instanceof ServerPlayer player) || event.getAction() != Action.START) {
         return;
      }

      if (ServerInputDispatcher.interactionBlocked(player)) {
         event.setCanceled(true);
         return;
      }

      if (ServerInputDispatcher.leftClickBlock(player, event.getPos())) {
         event.setCanceled(true);
      }
   }

   private static void onItemToss(ItemTossEvent event) {
      if (event.getPlayer() instanceof ServerPlayer player
         && (FastPlaceManager.active(player)
            || OperationManager.active(player)
            || GeometryManager.active(player)
            || FastPlaceManager.taskActive(player)
            || OperationManager.taskActive(player)
            || FastPlaceManager.restoreActive(player)
            || OperationManager.restoreActive(player))) {
         ItemStack tossed = event.getEntity().getItem().copy();
         event.setCanceled(true);
         FastPlaceManager.restoreTossedItem(player, tossed);
         FastPlaceManager.quit(player);
         return;
      }
   }

   private static void onSwapHands(Hands event) {
      if (event.getEntity() instanceof ServerPlayer player && ServerInputDispatcher.fill(player)) {
         event.setCanceled(true);
         return;
      }
   }

   private static void onPlayerTick(Post event) {
      if (event.getEntity() instanceof ServerPlayer player) {
         // A world undo/redo or task-recovery write must finish even if the
         // player toggles FastFormer off while it is running; otherwise the
         // history lock would remain forever and leave a partial world.
         if (WorldHistoryManager.busy(player)) {
            FastPlaceNetwork.syncActivity(player);
            return;
         }
         if (!ServerInputDispatcher.canOperate(player)) {
            ServerInputDispatcher.stopBecauseUnavailable(player);
            FastPlaceNetwork.syncActivity(player);
            return;
         }

         FastPlaceNetwork.syncActivity(player);
      }
   }

   private static void onServerTick(ServerTickEvent.Post event) {
      FastPlaceNetwork.tick(event.getServer());
      WorldTaskFeature.tick(event.getServer());
   }

}
