package io.github.fastformer.client.input.drag;

import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Immutable state for a single-part or common workspace gizmo gesture. */
public record WorkspaceGizmoDrag(
   int partId,
   boolean common,
   AxisGizmo.Operation operation,
   AxisGizmo.Axis axis,
   Vec3 origin,
   Vec3 axisVector,
   int sentSteps,
   Vec3 center,
   Vec3 startRadial,
   Vec3 startTangent,
   AxisGizmo.Direction direction,
   int mouseButton,
   List<ClientSelectionPart> baseline,
   ClientOperationWorkspace.EditToken editToken
) {
   public WorkspaceGizmoDrag withSentSteps(int value) {
      return new WorkspaceGizmoDrag(
         partId, common, operation, axis, origin, axisVector, value, center, startRadial,
         startTangent, direction, mouseButton, baseline, editToken
      );
   }
}
