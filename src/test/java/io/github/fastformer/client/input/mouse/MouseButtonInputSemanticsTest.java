package io.github.fastformer.client.input.mouse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MouseButtonInputSemanticsTest {

   @Test
   void altGizmoHitStartsTransformInsteadOfNewSelection() {
      assertTrue(MouseButtonInputSemantics.startsAltWorkspaceGizmoTransform(
         true, true, MouseButtonInputSemantics.PRESS, MouseButtonInputSemantics.LEFT_BUTTON
      ));
      assertTrue(MouseButtonInputSemantics.startsAltWorkspaceGizmoTransform(
         true, true, MouseButtonInputSemantics.PRESS, MouseButtonInputSemantics.RIGHT_BUTTON
      ));
      assertFalse(MouseButtonInputSemantics.startsAltWorkspaceGizmoTransform(
         true, false, MouseButtonInputSemantics.PRESS, MouseButtonInputSemantics.LEFT_BUTTON
      ));
   }
   @Test
   void altCreateSelectionMatrixOnlyAcceptsSupportedPressesOnTheSelectionRoute() {
      int[] actions = {0, MouseButtonInputSemantics.PRESS, 2};
      int[] buttons = {-1, MouseButtonInputSemantics.LEFT_BUTTON, MouseButtonInputSemantics.RIGHT_BUTTON,
         MouseButtonInputSemantics.MIDDLE_BUTTON, 3};

      for (boolean altHeld : new boolean[] {false, true}) {
         for (boolean createSelectionRoute : new boolean[] {false, true}) {
            for (int action : actions) {
               for (int button : buttons) {
                  boolean expected = altHeld
                     && createSelectionRoute
                     && action == MouseButtonInputSemantics.PRESS
                     && button >= MouseButtonInputSemantics.LEFT_BUTTON
                     && button <= MouseButtonInputSemantics.MIDDLE_BUTTON;

                  assertEquals(
                     expected,
                     MouseButtonInputSemantics.startsAltCreateSelection(
                        altHeld, createSelectionRoute, action, button
                     ),
                     "alt=" + altHeld + ", route=" + createSelectionRoute
                        + ", action=" + action + ", button=" + button
                  );
               }
            }
         }
      }
   }

   @Test
   void buildingMiddleConfirmMatrixOnlyAcceptsAnEnabledMiddlePress() {
      int[] actions = {0, MouseButtonInputSemantics.PRESS, 2};
      int[] buttons = {MouseButtonInputSemantics.LEFT_BUTTON, MouseButtonInputSemantics.RIGHT_BUTTON,
         MouseButtonInputSemantics.MIDDLE_BUTTON, 3};

      for (boolean buildingSession : new boolean[] {false, true}) {
         for (boolean middleConfirmEnabled : new boolean[] {false, true}) {
            for (int action : actions) {
               for (int button : buttons) {
                  boolean expected = buildingSession
                     && middleConfirmEnabled
                     && action == MouseButtonInputSemantics.PRESS
                     && button == MouseButtonInputSemantics.MIDDLE_BUTTON;

                  assertEquals(
                     expected,
                     MouseButtonInputSemantics.requestsBuildingMiddleConfirm(
                        buildingSession, middleConfirmEnabled, action, button
                     ),
                     "building=" + buildingSession + ", enabled=" + middleConfirmEnabled
                        + ", action=" + action + ", button=" + button
                  );
               }
            }
         }
      }
   }
}
