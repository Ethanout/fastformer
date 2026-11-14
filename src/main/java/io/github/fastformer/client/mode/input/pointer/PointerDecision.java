package io.github.fastformer.client.mode.input.pointer;

/** Typed left/right actions retain exhaustive execution switches at the input boundary. */
public sealed interface PointerDecision {
   enum Flow implements PointerDecision { PASS, BLOCK }

   enum Left implements PointerDecision {
      NONE, YIELD_TO_VANILLA, WORKSPACE_POINTER, WORKSPACE_ADJUST_OR_CAPTURE, OPERATION_GIZMO,
      OPERATION_POINT, UNDO, OPERATION_ALT, OPERATION_ADJUST, CUBOID_POINT_UNDO, CUBOID_FACE,
      OPERATION_CAPTURE, GEOMETRY_CAPTURE, GEOMETRY_GIZMO_CAPTURE, GEOMETRY_INTERACTION
   }

   enum Right implements PointerDecision {
      NONE, YIELD_TO_VANILLA, GEOMETRY_CAPTURE, GEOMETRY_INTERACTION, WORKSPACE_POINTER,
      WORKSPACE_ADJUST_OR_CAPTURE, CONFIRMED_GIZMO_CAPTURE, OPERATION_ALT, OPERATION_ADJUST,
      OPERATION_POINT, OPERATION_EDGE, OPERATION_NEXT_POINT, CUBOID_NEXT_POINT, CUBOID_FACE, CLOSE_PATH
   }
}
