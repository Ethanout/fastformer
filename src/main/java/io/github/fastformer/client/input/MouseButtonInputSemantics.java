package io.github.fastformer.client.input;

/** Pure input policy for semantic mouse gestures. */
public final class MouseButtonInputSemantics {
   public static final int PRESS = 1;
   public static final int RELEASE = 0;
   public static final int LEFT_BUTTON = 0;
   public static final int RIGHT_BUTTON = 1;
   public static final int MIDDLE_BUTTON = 2;

   private MouseButtonInputSemantics() {
   }

   public static boolean startsAltCreateSelection(
      boolean altHeld, boolean createSelectionRoute, int action, int button
   ) {
      return altHeld
         && createSelectionRoute
         && action == PRESS
         && isSelectionButton(button);
   }

   /**
    * Alt keeps its selection-creation meaning in empty space. A direct Gizmo
    * hit is an explicit transform gesture and therefore takes priority.
    */
   public static boolean startsAltWorkspaceGizmoTransform(
      boolean altHeld, boolean workspaceGizmoHit, int action, int button
   ) {
      return altHeld
         && workspaceGizmoHit
         && action == PRESS
         && (button == LEFT_BUTTON || button == RIGHT_BUTTON);
   }

   public static boolean requestsBuildingMiddleConfirm(
      boolean buildingSession, boolean middleConfirmEnabled, int action, int button
   ) {
      return buildingSession
         && middleConfirmEnabled
         && action == PRESS
         && button == MIDDLE_BUTTON;
   }

   private static boolean isSelectionButton(int button) {
      return button == LEFT_BUTTON || button == RIGHT_BUTTON || button == MIDDLE_BUTTON;
   }
}
