package io.github.fastformer.client.placement;

import io.github.fastformer.common.placement.context.QuickReplaceTargets;

import io.github.fastformer.common.placement.context.PlaceableItems;
import io.github.fastformer.common.replace.QuickReplaceTarget;
import io.github.fastformer.protocol.c2s.placement.QuickReplaceSessionPayload;
import io.github.fastformer.protocol.s2c.placement.QuickReplaceStatePayload;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Client-side state for Axiom-style direct block replacement. */
public final class QuickReplaceMode {
   private static boolean active;
   private static long requestId;
   private static long nextStrokeId;
   private static long strokeId;

   private QuickReplaceMode() {
   }

   public static boolean active() {
      return active;
   }

   public static boolean toggle(Minecraft minecraft) {
      if (minecraft == null || minecraft.player == null || (!active && !minecraft.player.isCreative())
         || minecraft.getConnection() == null
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), QuickReplaceSessionPayload.TYPE.id())
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), QuickReplaceStatePayload.TYPE.id())) {
         return false;
      }
      finishStroke(minecraft);
      active = !active;
      PacketDistributor.sendToServer(new QuickReplaceSessionPayload(++requestId, active));
      return active;
   }

   public static void receive(QuickReplaceStatePayload state) {
      if (state.requestId() == requestId) {
         if (!state.active()) finishStroke(Minecraft.getInstance());
         active = state.active();
      }
   }

   public static void clear() {
      active = false;
      strokeId = 0;
      requestId++;
   }

   public static boolean canReplace(Minecraft minecraft) {
      return active && minecraft != null && minecraft.player != null && minecraft.player.isCreative()
         && PlaceableItems.isPlaceable(minecraft.player.getMainHandItem());
   }

   public static void beginStroke(Minecraft minecraft) {
      finishStroke(minecraft);
      if (!canReplace(minecraft)) return;
      strokeId = ++nextStrokeId;
      replace(minecraft);
   }

   public static void replace(Minecraft minecraft) {
      if (strokeId != 0 && canReplace(minecraft)) ClientPlacementRouter.quickReplace(minecraft, strokeId, false);
   }

   public static void finishStroke(Minecraft minecraft) {
      if (strokeId == 0) return;
      long finished = strokeId;
      strokeId = 0;
      if (minecraft != null && minecraft.getConnection() != null) {
         ClientPlacementRouter.quickReplace(minecraft, finished, true);
      }
   }

   public static Preview preview(Minecraft minecraft) {
      if (!canReplace(minecraft) || minecraft.level == null) {
         return null;
      }
      var target = QuickReplaceTargets.resolve(minecraft.player);
      return target == null ? null : new Preview(target.position(), target.state());
   }

   public record Preview(BlockPos position, BlockState state) {
   }
}
