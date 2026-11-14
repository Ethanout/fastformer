package io.github.fastformer.client.render.model;

import io.github.fastformer.core.math.Axis;

import io.github.fastformer.common.geometry.gizmo.AxisGizmo;

public record GizmoHudInput(
   AxisGizmo gizmo,
   Axis dragAxis,
   AxisGizmo.Operation dragOperation,
   double dragBaseValue,
   int dragSteps,
   boolean allowNearBlock,
   boolean operationSelection
) {
}
