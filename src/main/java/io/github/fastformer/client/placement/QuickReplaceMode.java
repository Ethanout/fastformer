package io.github.fastformer.client.placement;

import io.github.fastformer.fastplace.placement.context.PlaceableItems;
import io.github.fastformer.fastplace.placement.replace.QuickReplaceTarget;
import io.github.fastformer.network.payload.placement.QuickReplaceSessionPayload;
import io.github.fastformer.network.payload.placement.QuickReplaceStatePayload;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Client-side state for Axiom-style direct block replacement. */
public final class QuickReplaceMode {
   private static boolean active;
   private static long requestId;

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
      active = !active;
      PacketDistributor.sendToServer(new QuickReplaceSessionPayload(++requestId, active));
      return active;
   }

   public static void receive(QuickReplaceStatePayload state) {
      if (state.requestId() == requestId) active = state.active();
   }

   public static void clear() {
      active = false;
      requestId++;
   }

   public static boolean canReplace(Minecraft minecraft) {
      return active && minecraft != null && minecraft.player != null && minecraft.player.isCreative()
         && PlaceableItems.isPlaceable(minecraft.player.getMainHandItem());
   }

   public static Preview preview(Minecraft minecraft) {
      if (!canReplace(minecraft) || minecraft.level == null) {
         return null;
      }
      var target = QuickReplaceTarget.resolve(minecraft.player);
      return target == null ? null : new Preview(target.position(), target.state());
   }

   public record Preview(BlockPos position, BlockState state) {
   }
}
