package io.github.fastformer.client.input;

import io.github.fastformer.client.input.mouse.MouseButtonInputSemantics;
import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.client.placement.ClientPlacementRouter;
import io.github.fastformer.client.render.core.FastPlaceClientPreviewCore;
import io.github.fastformer.protocol.c2s.placement.StartPlacementPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Captures an idle start without changing the input session in the mouse callback. */
final class QuickShapeStartController {
   private QuickShapeStartController() { }

   static boolean capture(Minecraft minecraft, ClientInputSession session, BlockHitResult hit, long anchorToken,
      int button, boolean embedded, boolean throughFluids) {
      return capture(minecraft, session, hit, anchorToken, button, embedded, throughFluids,
         minecraft.player.getEyePosition(), minecraft.player.getViewVector(1.0F),
         io.github.fastformer.common.interaction.binding.SessionProfiles.NONE);
   }

   static boolean capture(Minecraft minecraft, ClientInputSession session, BlockHitResult hit, long anchorToken,
      int button, boolean embedded, boolean throughFluids, net.minecraft.world.phys.Vec3 eye, net.minecraft.world.phys.Vec3 view,
      net.minecraft.resources.ResourceLocation profile) {
      if (hit == null
         || session.routing.state() != ClientInputStateMachine.State.IDLE || ClientOperationController.active()
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), StartPlacementPayload.TYPE.id())
         || !NetworkRegistry.hasChannel(minecraft.getConnection(),
            io.github.fastformer.protocol.s2c.placement.PlacementActionAckPayload.TYPE.id())) return false;
      var idle = FastPlaceClientPreviewCore.idleBuildingSession().orElse(null);
      if (idle == null) return false;
      if (!session.captureQuickShapeButton(button)) return true;
      session.postStartPlacement(new StartPlacementPayload.Target(idle.revision(), idle.callbackScope(),
         hit,
         eye, view, anchorToken, embedded,
         button == MouseButtonInputSemantics.MIDDLE_BUTTON, throughFluids, profile));
      return true;
   }

   static void dispatch(Minecraft minecraft, ClientInputSession session, StartPlacementPayload.Target target) {
      var idle = FastPlaceClientPreviewCore.idleBuildingSession().orElse(null);
      if (session.routing.state() != ClientInputStateMachine.State.IDLE || idle == null
         || idle.revision() != target.revision() || !idle.callbackScope().equals(target.callbackScope())
         || ClientOperationController.active()) return;
      if (session.modifier.held()) session.modifier.consume();
      ClientPlacementRouter.startPlacement(minecraft, target);
   }
}
