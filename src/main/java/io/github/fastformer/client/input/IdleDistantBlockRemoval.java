package io.github.fastformer.client.input;

import io.github.fastformer.client.input.mouse.MouseButtonInputSemantics;
import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.render.FastPlaceClientPreview;
import io.github.fastformer.fastplace.geometry.raycast.LongRangeBlockRaycast;
import io.github.fastformer.network.payload.placement.DistantBlockRemovePayload;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

final class IdleDistantBlockRemoval {
   private IdleDistantBlockRemoval() { }

   static boolean capture(Minecraft minecraft, ClientInputSession session, int action, int button, boolean alt) {
      if (button != MouseButtonInputSemantics.LEFT_BUTTON) return false;
      if (action == MouseButtonInputSemantics.RELEASE && session.distantRemovalPressed) {
         session.distantRemovalPressed = false;
         minecraft.options.keyAttack.setDown(false);
         return true;
      }
      var player = minecraft.player;
      if (action != MouseButtonInputSemantics.PRESS || session.routing.state() != ClientInputStateMachine.State.IDLE
         || player == null || !player.isCreative() || !player.getMainHandItem().isEmpty()
         || !io.github.fastformer.client.interaction.ClientInteractionUpdates.distantBlockRemoval(player)
         || !FastPlaceClientPreview.enabled() || minecraft.getCameraEntity() != player
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), DistantBlockRemovePayload.TYPE.id())) return false;
      if (!alt && minecraft.hitResult != null && minecraft.hitResult.getType() == net.minecraft.world.phys.HitResult.Type.ENTITY) return false;
      var hit = LongRangeBlockRaycast.clipForIdleEntry(player, alt).hit();
      if (!LongRangeBlockRaycast.beyondInteractionRange(player, hit)
         || minecraft.level.getBlockState(hit.getBlockPos()).isAir()) return false;
      session.idleEntry.clear();
      session.distantRemovalPressed = true;
      minecraft.options.keyAttack.setDown(false);
      if (alt) session.modifier.consume();
      PacketDistributor.sendToServer(new DistantBlockRemovePayload(hit, player.getEyePosition(), player.getViewVector(1), alt));
      return true;
   }
}
