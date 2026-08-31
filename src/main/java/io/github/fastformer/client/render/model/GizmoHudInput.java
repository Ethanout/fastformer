package io.github.fastformer.client.render.model;

import io.github.fastformer.fastplace.geometry.AxisGizmo;

public record GizmoHudInput(
   AxisGizmo gizmo,
   AxisGizmo.Axis dragAxis,
   AxisGizmo.Operation dragOperation,
   double dragBaseValue,
   int dragSteps,
   boolean allowNearBlock,
   boolean operationSelection
) {
}
