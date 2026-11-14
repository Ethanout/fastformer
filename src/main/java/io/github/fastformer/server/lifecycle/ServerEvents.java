package io.github.fastformer.server.lifecycle;

import io.github.fastformer.server.worldwrite.effect.WorldWriteSideEffectGuard;

import io.github.fastformer.server.session.SessionSystem;

import io.github.fastformer.server.history.HistorySystem;

import io.github.fastformer.server.gate.ServerOperationGates;

import io.github.fastformer.server.command.CommandSystem;
import io.github.fastformer.server.history.HistoryService;
import io.github.fastformer.server.interaction.BlockTinker;
import io.github.fastformer.server.mode.ModeInput;
import io.github.fastformer.server.mode.ModeInputResult;
import io.github.fastformer.server.sync.PreviewSync;
import io.github.fastformer.server.input.ServerInputActions;
import io.github.fastformer.server.player.TossedItems;
import io.github.fastformer.server.session.QuickShapeSystem;
import io.github.fastformer.server.session.GeometrySystem;
import io.github.fastformer.server.session.OperationSystem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.living.LivingSwapItemsEvent.Hands;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock.Action;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickItem;
import net.neoforged.neoforge.event.tick.PlayerTickEvent.Post;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public final class ServerEvents {
   private ServerEvents() {
   }

   public static void register() {
      NeoForge.EVENT_BUS.addListener(ServerEvents::onRightClickBlock);
      NeoForge.EVENT_BUS.addListener(ServerEvents::onRightClickItem);
      NeoForge.EVENT_BUS.addListener(ServerEvents::onLeftClickBlock);
      NeoForge.EVENT_BUS.addListener(ServerEvents::onItemToss);
      NeoForge.EVENT_BUS.addListener(ServerEvents::onSwapHands);
      NeoForge.EVENT_BUS.addListener(ServerEvents::onPlayerTick);
      NeoForge.EVENT_BUS.addListener(ServerEvents::onServerTick);
      PlayerLifecycleEvents.register();
      NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, WorldWriteSideEffectGuard::onEntityJoin);
      CommandSystem.bootstrap();
   }

   private static void onRightClickBlock(RightClickBlock event) {
      if (!(event.getEntity() instanceof ServerPlayer player) || event.getHand() != InteractionHand.MAIN_HAND) {
         return;
      }

      if (ServerInputActions.interactionBlocked(player)) {
         event.setCanceled(true);
         return;
      }

      ModeInputResult input = SessionSystem.of(player).modes().input(player, ModeInput.RIGHT_BLOCK);
      if (input != ModeInputResult.NEXT) {
         if (input == ModeInputResult.CONSUMED) {
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
         }
         return;
      }
      if (io.github.fastformer.server.interaction.ForcedPlacement.use(player, event.getHitVec())
         || BlockTinker.use(player, event.getHitVec())) {
         event.setCancellationResult(InteractionResult.SUCCESS);
         event.setCanceled(true);
         return;
      }

      if (ServerInputActions.rightClickBlock(player, event.getHitVec())) {
         event.setCancellationResult(InteractionResult.SUCCESS);
         event.setCanceled(true);
      }
   }

   private static void onRightClickItem(RightClickItem event) {
      if (!(event.getEntity() instanceof ServerPlayer player) || event.getHand() != InteractionHand.MAIN_HAND) {
         return;
      }


      if (ServerInputActions.interactionBlocked(player)) {
         event.setCanceled(true);
         return;
      }

      ModeInputResult input = SessionSystem.of(player).modes().input(player, ModeInput.RIGHT_ITEM);
      if (input != ModeInputResult.NEXT) {
         if (input == ModeInputResult.CONSUMED) {
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
         }
         return;
      }
      if (ServerInputActions.rightClickItem(player)) {
         event.setCancellationResult(InteractionResult.SUCCESS);
         event.setCanceled(true);
      }
   }

   private static void onLeftClickBlock(LeftClickBlock event) {
      if (!(event.getEntity() instanceof ServerPlayer player) || event.getAction() != Action.START) {
         return;
      }


      if (ServerInputActions.interactionBlocked(player)) {
         event.setCanceled(true);
         return;
      }

      ModeInputResult input = SessionSystem.of(player).modes().input(player, ModeInput.LEFT_BLOCK);
      if (input != ModeInputResult.NEXT) {
         if (input == ModeInputResult.CONSUMED) event.setCanceled(true);
         return;
      }
      if (ServerInputActions.leftClickBlock(player, event.getPos())) {
         event.setCanceled(true);
      }
   }

   private static void onItemToss(ItemTossEvent event) {
      if (event.getPlayer() instanceof ServerPlayer player
         && (!ServerOperationGates.NO_SESSION.test(player).allowed()
            || !ServerOperationGates.WRITE_AVAILABLE.test(player).allowed())) {
         ItemStack tossed = event.getEntity().getItem().copy();
         event.setCanceled(true);
         TossedItems.restore(player, tossed);
         SessionSystem.of(player).quit(player);
         return;
      }
   }

   private static void onSwapHands(Hands event) {
      if (event.getEntity() instanceof ServerPlayer player) {
         ModeInputResult input = SessionSystem.of(player).modes().input(player, ModeInput.SWAP_HANDS);
         if (input != ModeInputResult.NEXT) {
            if (input == ModeInputResult.CONSUMED) event.setCanceled(true);
            return;
         }
      }
      if (event.getEntity() instanceof ServerPlayer player && ServerInputActions.fill(player)) {
         event.setCanceled(true);
         return;
      }
   }

   private static void onPlayerTick(Post event) {
      if (event.getEntity() instanceof ServerPlayer player) {
         // A world undo/redo or task-recovery write must finish even if the
         // player toggles FastFormer off while it is running; otherwise the
         // history lock would remain forever and leave a partial world.
         if (HistorySystem.of(player).busy(player)) {
            PreviewSync.syncActivity(player);
            return;
         }
         if (!ServerInputActions.canOperate(player)) {
            io.github.fastformer.server.session.SessionSystem.of(player).stopUnavailable(player);
            PreviewSync.syncActivity(player);
            return;
         }

         SessionSystem.of(player).modes().tick(player);
         PreviewSync.syncActivity(player);
      }
   }

   private static void onServerTick(ServerTickEvent.Post event) {
      io.github.fastformer.server.network.upload.UploadSystem.of(event.getServer()).tick();
      io.github.fastformer.server.task.WorldTaskScheduler.tick(event.getServer());
   }

}
