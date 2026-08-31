package io.github.fastformer.client.operation.transform;

/** Pure routing rules for editor hits and modifier gestures. */
public final class WorkspaceInputSemantics {
   private WorkspaceInputSemantics() {
   }

   public static ClickAction click(boolean control, Target target) {
      if (target == Target.WORLD) {
         return control ? ClickAction.CREATE_PART : ClickAction.VANILLA;
      }
      if (target == Target.GIZMO || target == Target.LABEL || target == Target.FRAME) {
         return control ? ClickAction.TOGGLE_PART : ClickAction.SELECT_PART;
      }
      return ClickAction.VANILLA;
   }

   public static DragAction drag(boolean alreadySelected, boolean control) {
      if (alreadySelected) {
         return DragAction.DRAG_SELECTION;
      }
      return control ? DragAction.ADD_AND_DRAG : DragAction.SELECT_ONLY_AND_DRAG;
   }

   public static boolean cycleOnAltRelease(boolean eligible, boolean consumed) {
      return eligible && !consumed;
   }

   public enum Target {
      NONE,
      WORLD,
      GIZMO,
      LABEL,
      FRAME
   }

   public enum ClickAction {
      VANILLA,
      CREATE_PART,
      SELECT_PART,
      TOGGLE_PART
   }

   public enum DragAction {
      DRAG_SELECTION,
      SELECT_ONLY_AND_DRAG,
      ADD_AND_DRAG
   }
}
