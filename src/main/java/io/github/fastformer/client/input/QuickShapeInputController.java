package io.github.fastformer.client.input;

import io.github.fastformer.client.quickshape.QuickShapeSubmissionController;
import io.github.fastformer.client.placement.ClientPlacementRouter;
import io.github.fastformer.client.render.FastPlaceClientPreview;
import net.minecraft.client.Minecraft;

/** Owns quick-shape keyboard submission decisions at the input boundary. */
final class QuickShapeInputController {
   private QuickShapeInputController() {
   }

   static boolean submit(Minecraft minecraft, ClientInputSession session, KeyboardInputSnapshot event) {
      if (event == null || event.action() != 1 || !isEnter(event.key())) return false;
      var snapshot = event.quickShapeSubmission();
      if (snapshot == null || !ClientPlacementRouter.canConfirm(minecraft)) return false;
      return QuickShapeSubmissionController.begin(minecraft, session.quickShapeSubmission, snapshot);
   }

   static boolean isBuilding(Minecraft minecraft) {
      return minecraft != null && FastPlaceClientPreview.buildingSubmission().isPresent();
   }

   private static boolean isEnter(int key) {
      return key == 257 || key == 335;
   }
}
