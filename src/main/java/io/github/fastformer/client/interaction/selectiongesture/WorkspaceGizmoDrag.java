package io.github.fastformer.client.interaction.selectiongesture;

import io.github.fastformer.core.math.Axis;
import io.github.fastformer.common.geometry.gizmo.AxisGizmo;
import net.minecraft.world.phys.Vec3;

/** Read-only single-part or group gizmo state, independent of workspace implementation. */
public record WorkspaceGizmoDrag(int partId, boolean common, AxisGizmo.Operation operation, Axis axis,
   Vec3 origin, Vec3 axisVector, int sentSteps, Vec3 center, Vec3 startRadial, Vec3 startTangent,
   AxisGizmo.Direction direction, SelectionDragCapture capture, double rotationRadians, boolean freeRotation) {
   public int mouseButton() { return capture.mouseButton(); }
   WorkspaceGizmoDrag withSentSteps(int value) {
      return new WorkspaceGizmoDrag(partId, common, operation, axis, origin, axisVector, value, center,
         startRadial, startTangent, direction, capture, rotationRadians, freeRotation);
   }
   WorkspaceGizmoDrag withRotation(int steps, double radians, boolean control) {
      return new WorkspaceGizmoDrag(partId, common, operation, axis, origin, axisVector, steps, center,
         startRadial, startTangent, direction, capture, radians, control);
   }
}
