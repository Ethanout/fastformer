package io.github.fastformer.client.input.mouse;

/** Resolves the owner of an ordinary left or right mouse press. */
public final class MousePressRoutingSemantics {
   private MousePressRoutingSemantics() {
   }

   public static LeftTarget leftTarget(LeftState state) {
      if (!state.isLeftPress() || !state.sessionAcceptsLeftPress()) return LeftTarget.NONE;
      if (state.yieldToVanilla()) return LeftTarget.YIELD_TO_VANILLA;
      if (state.workspacePointerTarget()) return LeftTarget.WORKSPACE_POINTER;
      if (state.workspaceActive()) return LeftTarget.WORKSPACE_ADJUST_OR_CAPTURE;
      if (state.operationGizmoTarget()) return LeftTarget.OPERATION_GIZMO;
      if (state.operationPointTarget()) return LeftTarget.OPERATION_POINT;
      if (state.operationUndo()) return LeftTarget.UNDO;
      if (state.operationAlt()) return LeftTarget.OPERATION_ALT;
      if (state.operationAdjust()) return LeftTarget.OPERATION_ADJUST;
      if (state.cuboidWorldTarget()) return LeftTarget.CUBOID_FIRST_POINT;
      if (state.cuboidFaceTarget()) return LeftTarget.CUBOID_FACE;
      if (state.operationCaptured() || state.operationDragCaptured()) return LeftTarget.OPERATION_CAPTURE;
      if (state.geometryCaptured()) return LeftTarget.GEOMETRY_CAPTURE;
      if (state.geometryGizmoDragging()) return LeftTarget.GEOMETRY_GIZMO_CAPTURE;
      if (state.geometryGizmoTarget() || state.geometryInteractionTarget()) return LeftTarget.GEOMETRY_INTERACTION;
      if (state.operationSession()) return LeftTarget.OPERATION_CAPTURE;
      return state.undoAvailable() ? LeftTarget.UNDO : LeftTarget.NONE;
   }

   public static RightTarget rightTarget(RightState state) {
      if (!state.isRightPress()) return RightTarget.NONE;
      if (state.geometrySession()) {
         if (state.nearVanillaBlock()) return RightTarget.YIELD_TO_VANILLA;
         return state.geometryCaptured() ? RightTarget.GEOMETRY_CAPTURE : RightTarget.GEOMETRY_INTERACTION;
      }
      if (!state.operationSession()) return RightTarget.NONE;
      if (state.workspacePointerTarget()) return RightTarget.WORKSPACE_POINTER;
      if (state.workspaceActive()) return RightTarget.WORKSPACE_ADJUST_OR_CAPTURE;
      if (state.selectionConfirmed()) {
         return state.nearVanillaBlock() ? RightTarget.YIELD_TO_VANILLA : RightTarget.CONFIRMED_GIZMO_CAPTURE;
      }
      if (state.nearVanillaBlock() && !state.operationAdjust() && !state.operationAlt() && !state.operationPointTarget()) {
         return RightTarget.YIELD_TO_VANILLA;
      }
      if (state.operationAlt()) return RightTarget.OPERATION_ALT;
      if (state.operationAdjust()) return RightTarget.OPERATION_ADJUST;
      if (state.operationPointTarget()) return RightTarget.OPERATION_POINT;
      if (state.operationEdgeTarget()) return RightTarget.OPERATION_EDGE;
      if (state.operationNextPointTarget()) return RightTarget.OPERATION_NEXT_POINT;
      if (state.cuboidWorldTarget()) return RightTarget.CUBOID_SECOND_POINT;
      if (state.cuboidFaceTarget()) return RightTarget.CUBOID_FACE;
      return state.closePathTarget() ? RightTarget.CLOSE_PATH : RightTarget.NONE;
   }

   public record LeftState(
      int action,
      int button,
      boolean sessionAcceptsLeftPress,
      boolean yieldToVanilla,
      boolean workspacePointerTarget,
      boolean workspaceActive,
      boolean operationGizmoTarget,
      boolean operationPointTarget,
      boolean operationUndo,
      boolean operationAlt,
      boolean operationAdjust,
      boolean cuboidWorldTarget,
      boolean cuboidFaceTarget,
      boolean operationCaptured,
      boolean operationDragCaptured,
      boolean geometryCaptured,
      boolean geometryGizmoDragging,
      boolean geometryGizmoTarget,
      boolean geometryInteractionTarget,
      boolean operationSession,
      boolean undoAvailable
   ) {
      boolean isLeftPress() {
         return action == MouseButtonInputSemantics.PRESS && button == MouseButtonInputSemantics.LEFT_BUTTON;
      }
   }

   public record RightState(
      int action,
      int button,
      boolean geometrySession,
      boolean operationSession,
      boolean nearVanillaBlock,
      boolean geometryCaptured,
      boolean workspacePointerTarget,
      boolean workspaceActive,
      boolean selectionConfirmed,
      boolean operationAdjust,
      boolean operationAlt,
      boolean operationPointTarget,
      boolean operationEdgeTarget,
      boolean operationNextPointTarget,
      boolean cuboidWorldTarget,
      boolean cuboidFaceTarget,
      boolean closePathTarget
   ) {
      boolean isRightPress() {
         return action == MouseButtonInputSemantics.PRESS && button == MouseButtonInputSemantics.RIGHT_BUTTON;
      }
   }

   public enum LeftTarget {
      NONE,
      YIELD_TO_VANILLA,
      WORKSPACE_POINTER,
      WORKSPACE_ADJUST_OR_CAPTURE,
      OPERATION_GIZMO,
      OPERATION_POINT,
      UNDO,
      OPERATION_ALT,
      OPERATION_ADJUST,
      CUBOID_FIRST_POINT,
      CUBOID_FACE,
      OPERATION_CAPTURE,
      GEOMETRY_CAPTURE,
      GEOMETRY_GIZMO_CAPTURE,
      GEOMETRY_INTERACTION
   }

   public enum RightTarget {
      NONE,
      YIELD_TO_VANILLA,
      GEOMETRY_CAPTURE,
      GEOMETRY_INTERACTION,
      WORKSPACE_POINTER,
      WORKSPACE_ADJUST_OR_CAPTURE,
      CONFIRMED_GIZMO_CAPTURE,
      OPERATION_ALT,
      OPERATION_ADJUST,
      OPERATION_POINT,
      OPERATION_EDGE,
      OPERATION_NEXT_POINT,
      CUBOID_SECOND_POINT,
      CUBOID_FACE,
      CLOSE_PATH
   }
}
