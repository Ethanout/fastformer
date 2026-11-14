package io.github.fastformer.client.interaction.binding.action;

/** Geometry pointer operations selected by resource rules, independent of the physical button. */
public enum GeometryBindingCommand {
   GIZMO, TARGET_LEFT, TARGET_RIGHT, CLEAR_LEFT, CLEAR_RIGHT, UNDO_POINT, PATH,
   CAPTURE, RELEASE_POINTER, RELEASE_GIZMO, BEGIN_UNDO, END_UNDO
}
