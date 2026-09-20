package io.github.fastformer.client.interaction;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.client.operation.selection.ClientSelectionSession;
import io.github.fastformer.client.gizmo.GizmoViewScale;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

class SelectionGizmoInteractionTest {
   @Test
   void commonGeometryUsesPublishedAirInclusiveBounds() {
      var session = new ClientSelectionSession();
      var left = part(OperationSelectionVolume.cuboid(BlockPos.ZERO, new BlockPos(3, 3, 3), BlockPos.ZERO, BlockPos.ZERO));
      var right = part(OperationSelectionVolume.cuboid(new BlockPos(10, 0, 0), new BlockPos(12, 2, 2), BlockPos.ZERO, BlockPos.ZERO));
      session.workspace().addParts(List.of(left, right));
      session.publishInteractionScene();
      var group = session.interactionScene().groupGizmo();
      var bounds = group.require(InteractionComponents.WORLD_BOUNDS);
      assertEquals(new AABB(0, 0, 0, 13, 4, 4), bounds);
      var gizmo = SelectionGizmoInteraction.resolveGroup(group, new GizmoViewScale(1, 0.1));
      assertEquals(bounds.getCenter(), gizmo.center());
      assertTrue(gizmo.handles().stream().anyMatch(handle -> handle.operation() == AxisGizmo.Operation.SCALE));
      session.workspace().selectOnly(1);
      session.publishInteractionScene();
      assertNull(session.interactionScene().groupGizmo());
   }

   @Test
   void commonPrismRetainsMoveAndRotateWithoutScale() {
      var prism = OperationSelectionVolume.create(OperationSelectionMode.PRISM,
         List.of(BlockPos.ZERO, new BlockPos(1, 0, 0), new BlockPos(0, 1, 0), new BlockPos(0, 0, 2)),
         BlockPos.ZERO, BlockPos.ZERO, 0);
      assertNotNull(prism.prism());
      var session = new ClientSelectionSession();
      session.workspace().addParts(List.of(part(prism), part(prism)));
      session.publishInteractionScene();
      var gizmo = SelectionGizmoInteraction.resolveGroup(session.interactionScene().groupGizmo(), new GizmoViewScale(1, 0.1));
      assertTrue(gizmo.handles().stream().anyMatch(handle -> handle.operation() == AxisGizmo.Operation.MOVE));
      assertTrue(gizmo.handles().stream().anyMatch(handle -> handle.operation() == AxisGizmo.Operation.ROTATE));
      assertFalse(gizmo.handles().stream().anyMatch(handle -> handle.operation() == AxisGizmo.Operation.SCALE));
   }

   private static ClientSelectionPart part(OperationSelectionVolume selection) {
      return new ClientSelectionPart(1, ClientSelectionPart.Source.WORLD, selection, Map.of(), WorkspaceTransform.IDENTITY, false);
   }
}
