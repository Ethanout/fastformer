package io.github.fastformer.client.interaction;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.workspace.model.ClientSelectionPart;
import io.github.fastformer.workspace.model.WorkspaceTransform;
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
   void denseMultipleSelectionShowsOnlyTheGroupGizmo() {
      var session = new ClientSelectionSession();
      var selection = OperationSelectionVolume.cuboid(BlockPos.ZERO, new BlockPos(3, 3, 3), BlockPos.ZERO, BlockPos.ZERO);
      session.workspace().addParts(java.util.stream.IntStream.range(0, 24).mapToObj(index -> part(selection)).toList());
      session.publishInteractionScene();
      var scene = session.interactionScene();
      assertEquals(24, scene.groupGizmo().require(InteractionComponents.GROUP_GIZMO).members().size());
      for (var member : scene.parts().values()) {
         assertFalse(SelectionGizmoInteraction.partGizmoVisible(scene, member.gizmo(), true));
      }
      var gizmo = SelectionGizmoInteraction.resolveGroup(scene.groupGizmo(), new GizmoViewScale(1, 0.1)).world();
      assertEquals(1.35, gizmo.axisLength(), 1.0E-6);
      var handle = gizmo.handles().stream().filter(candidate -> candidate.operation() == AxisGizmo.Operation.MOVE
         && candidate.axis() == AxisGizmo.Axis.X && candidate.direction() == AxisGizmo.Direction.POSITIVE).findFirst().orElseThrow();
      var endpoint = gizmo.handleCenter(handle);
      var hit = gizmo.hitTest(endpoint.add(0, 0, 6), new net.minecraft.world.phys.Vec3(0, 0, -1), 32);
      assertNotNull(hit);
      assertEquals(handle.key(), hit.handle().key());
      session.workspace().selectOnly(1);
      session.publishInteractionScene();
      var singleScene = session.interactionScene();
      assertNull(singleScene.groupGizmo());
      assertTrue(SelectionGizmoInteraction.partGizmoVisible(singleScene, singleScene.parts().get(1).gizmo(), true));
   }

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
      var geometry = SelectionGizmoInteraction.resolveGroup(group, new GizmoViewScale(1, 0.1));
      var gizmo = geometry.world();
      assertEquals(bounds.getCenter(), gizmo.center());
      assertTrue(geometry.scale().handles().stream().anyMatch(handle -> handle.operation() == AxisGizmo.Operation.SCALE));
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
      var geometry = SelectionGizmoInteraction.resolveGroup(session.interactionScene().groupGizmo(), new GizmoViewScale(1, 0.1));
      assertTrue(geometry.world().handles().stream().anyMatch(handle -> handle.operation() == AxisGizmo.Operation.MOVE));
      assertTrue(geometry.rotation().handles().stream().anyMatch(handle -> handle.operation() == AxisGizmo.Operation.ROTATE));
      assertTrue(geometry.scale().handles().isEmpty());
   }

   @Test
   void rotationAndScaleUseTheTransformedSelectionFrame() {
      var transform = WorkspaceTransform.IDENTITY.withRotation(new net.minecraft.world.phys.Vec3(0.2, 0.8, -0.3))
         .withSymmetry(io.github.fastformer.workspace.model.WorkspaceSymmetry.reflection(AxisGizmo.Axis.X));
      var source = part(OperationSelectionVolume.cuboid(BlockPos.ZERO, new BlockPos(3, 3, 3), BlockPos.ZERO, BlockPos.ZERO))
         .withTransform(transform);
      var object = SelectionGizmoInteraction.createPart(java.util.UUID.randomUUID(), 1, source, net.minecraft.world.phys.Vec3.ZERO);
      var geometry = SelectionGizmoInteraction.resolvePart(object, new GizmoViewScale(1, 0.1));
      for (AxisGizmo.Axis axis : AxisGizmo.Axis.values()) {
         var expected = io.github.fastformer.workspace.transform.WorkspaceRotation.localAxis(transform, axis);
         assertEquals(0.0, expected.distanceToSqr(geometry.rotation().axisVector(axis)), 1.0E-12);
         assertEquals(0.0, expected.distanceToSqr(geometry.scale().axisVector(axis)), 1.0E-12);
      }
      assertTrue(geometry.world().handles().stream().allMatch(handle -> handle.operation() == AxisGizmo.Operation.MOVE));
   }

   private static ClientSelectionPart part(OperationSelectionVolume selection) {
      return new ClientSelectionPart(1, ClientSelectionPart.Source.WORLD, selection, Map.of(), WorkspaceTransform.IDENTITY, false);
   }
}
