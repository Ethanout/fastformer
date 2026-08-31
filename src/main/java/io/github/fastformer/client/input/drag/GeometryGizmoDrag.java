package io.github.fastformer.client.input.drag;

import io.github.fastformer.fastplace.geometry.AxisGizmo;
import net.minecraft.world.phys.Vec3;

/** Immutable state for geometry and confirmed-operation gizmo dragging. */
public record GeometryGizmoDrag(
   AxisGizmo.Operation operation,
   AxisGizmo.Axis axis,
   Vec3 origin,
   Vec3 axisVector,
   int sentSteps,
   double baseValue,
   Vec3 center,
   Vec3 startRadial,
   Vec3 startTangent,
   AxisGizmo.Direction direction,
   int mouseButton
) {
   public GeometryGizmoDrag withSentSteps(int value) {
      return new GeometryGizmoDrag(
         operation, axis, origin, axisVector, value, baseValue, center, startRadial,
         startTangent, direction, mouseButton
      );
   }
}
