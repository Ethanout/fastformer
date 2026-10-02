package io.github.fastformer.client.input;

import io.github.fastformer.client.input.mouse.MouseButtonInputSemantics;
import io.github.fastformer.client.input.state.ClientInputStateMachine;
import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.client.placement.ClientPlacementRouter;
import io.github.fastformer.client.quickshape.input.BuildingInputSemantics;
import io.github.fastformer.client.render.FastPlaceClientPreview;
import io.github.fastformer.network.payload.placement.StartPlacementPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Captures an idle start without changing the input session in the mouse callback. */
final class QuickShapeStartController {
   private QuickShapeStartController() { }

   static boolean capture(Minecraft minecraft, ClientInputSession session, int action, int button,
      boolean alt, BlockHitResult hit) {
      if (action != MouseButtonInputSemantics.PRESS || button < 0 || button > 2 || hit == null
         || session.routing.state() != ClientInputStateMachine.State.IDLE || ClientOperationController.active()
         || !NetworkRegistry.hasChannel(minecraft.getConnection(), StartPlacementPayload.TYPE.id())
         || !NetworkRegistry.hasChannel(minecraft.getConnection(),
            io.github.fastformer.network.payload.placement.PlacementActionAckPayload.TYPE.id())) return false;
      var idle = FastPlaceClientPreview.idleBuildingSession().orElse(null);
      if (idle == null) return false;
      if (!session.captureQuickShapeButton(button)) return true;
      session.postStartPlacement(new StartPlacementPayload.Target(idle.revision(), idle.callbackScope(),
         BuildingInputSemantics.initialPlacement(alt, button == MouseButtonInputSemantics.MIDDLE_BUTTON), hit,
         minecraft.player.getEyePosition(), minecraft.player.getViewVector(1.0F)));
      return true;
   }

   static void dispatch(Minecraft minecraft, ClientInputSession session, StartPlacementPayload.Target target) {
      var idle = FastPlaceClientPreview.idleBuildingSession().orElse(null);
      if (session.routing.state() != ClientInputStateMachine.State.IDLE || idle == null
         || idle.revision() != target.revision() || !idle.callbackScope().equals(target.callbackScope())
         || ClientOperationController.active()) return;
      if (session.modifier.held()) session.modifier.consume();
      ClientPlacementRouter.startPlacement(minecraft, target);
   }
}
