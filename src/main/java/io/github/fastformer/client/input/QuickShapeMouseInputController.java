package io.github.fastformer.client.input;

import io.github.fastformer.client.input.mouse.MouseButtonInputSemantics;
import io.github.fastformer.client.placement.ClientPlacementRouter;
import io.github.fastformer.client.render.FastPlaceClientPreview;
import net.minecraft.client.Minecraft;

/** Owns quick-shape mouse confirmation and path-close decisions. */
final class QuickShapeMouseInputController {
   private QuickShapeMouseInputController() {
   }

   static boolean handleRight(Minecraft minecraft, ClientInputSession session, int action, long occurredAtNanos) {
      if (action != MouseButtonInputSemantics.PRESS
         || InteractionContext.nearVanillaBlock(minecraft)) {
         return false;
      }
      if (!PathCloseInputDispatcher.press(minecraft, session, false, occurredAtNanos)) {
         return false;
      }
      session.buildingRightPress.consume();
      return true;
   }

   static boolean handleMiddle(
      Minecraft minecraft, ClientInputSession session, boolean buildingSession, int action, int button
   ) {
      if (!buildingSession || button != MouseButtonInputSemantics.MIDDLE_BUTTON) {
         return false;
      }
      if (FastPlaceClientPreview.buildingMiddleClickIgnored()) {
         return true;
      }
      return MouseButtonInputSemantics.requestsBuildingMiddleConfirm(
         true, FastPlaceClientPreview.middleConfirmEnabled(), action, button
      ) && ClientPlacementRouter.quickShape(minecraft);
   }
}
