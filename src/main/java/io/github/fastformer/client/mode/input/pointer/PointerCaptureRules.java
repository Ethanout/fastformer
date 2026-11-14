package io.github.fastformer.client.mode.input.pointer;

import io.github.fastformer.client.mode.input.pointer.PointerDecision.Left;

/** Existing target/capture ownership wins before a mode supplies its ordinary left action. */
public final class PointerCaptureRules {
   private PointerCaptureRules() {}

   public static Left left(PointerView.Left view) {
      if (view.yieldToVanilla()) return Left.YIELD_TO_VANILLA;
      if (view.workspacePointerTarget()) return Left.WORKSPACE_POINTER;
      if (view.workspaceActive()) return Left.WORKSPACE_ADJUST_OR_CAPTURE;
      if (view.operationGizmoTarget()) return Left.OPERATION_GIZMO;
      if (view.operationPointTarget()) return Left.OPERATION_POINT;
      if (view.operationUndo()) return Left.UNDO;
      if (view.operationAlt()) return Left.OPERATION_ALT;
      if (view.operationAdjust()) return Left.OPERATION_ADJUST;
      if (view.cuboidWorldTarget()) return Left.CUBOID_POINT_UNDO;
      if (view.cuboidFaceTarget()) return Left.CUBOID_FACE;
      if (view.operationCaptured() || view.operationDragCaptured()) return Left.OPERATION_CAPTURE;
      if (view.geometryCaptured()) return Left.GEOMETRY_CAPTURE;
      if (view.geometryGizmoDragging()) return Left.GEOMETRY_GIZMO_CAPTURE;
      if (view.geometryGizmoTarget() || view.geometryInteractionTarget()) return Left.GEOMETRY_INTERACTION;
      return Left.NONE;
   }
}
