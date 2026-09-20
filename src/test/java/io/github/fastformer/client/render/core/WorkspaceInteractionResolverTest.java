package io.github.fastformer.client.render.core;

import io.github.fastformer.client.input.OperationInteractionIntent;
import io.github.fastformer.client.interaction.PartLabelInteraction;
import io.github.fastformer.client.operation.model.ClientBlockSnapshot;
import io.github.fastformer.client.interaction.PartInteractionBounds;
import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.client.operation.workspace.ClientOperationWorkspace;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.geometry.OperationGeometry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceInteractionResolverTest {
   private static io.github.fastformer.client.interaction.SelectionInteractionScene scene(ClientSelectionPart part) {
      var session = new io.github.fastformer.client.operation.selection.ClientSelectionSession();
      session.workspace().restoreDraftState(new ClientOperationWorkspace.DraftState(List.of(part), Set.of(), 0));
      session.publishInteractionScene();
      return session.interactionScene();
   }

   @Test
   void partResolutionUsesTheDisplayedComponentAnchor() {
      var selection = OperationSelectionVolume.cuboid(
         new BlockPos(29_999_800, 60, 100), new BlockPos(29_999_804, 64, 104), BlockPos.ZERO, BlockPos.ZERO);
      var part = new ClientSelectionPart(7, ClientSelectionPart.Source.WORLD,
         selection, Map.of(), WorkspaceTransform.IDENTITY, false);
      var scene = scene(part);
      var object = scene.parts().get(part.id()).label();
      var shown = PartLabelInteraction.present(object,
         new PartLabelInteraction.Context(false, true, false, false, null, 1));
      Vec3 eye = shown.anchor().add(0, 0, 10);

      var hit = WorkspaceInteractionResolver.resolvePartTarget(
         scene, eye, new Vec3(0, 0, -1), false, Set.of(), false).orElseThrow();
      assertEquals(7, hit.partId());
      assertEquals(io.github.fastformer.client.input.OperationInteractionIntent.PartSurface.LABEL, hit.surface());
      assertEquals(10.0, hit.distance(), 1.0E-9);
      assertTrue(WorkspaceInteractionResolver.resolvePartTarget(
         scene, eye.add(0.23, 0, 0), new Vec3(0, 0, -1), false, Set.of(), false).isEmpty());
   }

   @Test
   void aPartHandleMovesOnlyItsTargetWithinAMultipleSelection() {
      var workspace = io.github.fastformer.client.operation.controller.ClientOperationController.workspace();
      workspace.clear();
      try {
         var first = transformedPart(1);
         var second = transformedPart(2).withTranslation(new Vec3(100, 0, 0));
         assertTrue(workspace.addParts(List.of(first, second)));
         assertEquals(Set.of(1, 2), workspace.selectedIds());
         var targets = io.github.fastformer.client.input.drag.WorkspaceGizmoDrag.targets(workspace, 1, false);
         assertTrue(workspace.beginEdit());
         io.github.fastformer.client.operation.controller.ClientOperationController.updateTransformGesture(
            workspace.activeEditToken(), targets, false, AxisGizmo.Operation.MOVE, AxisGizmo.Axis.X, 1, 3, Double.NaN
         );
         assertEquals(first.transform().translation().add(3, 0, 0), workspace.part(1).orElseThrow().transform().translation());
         assertEquals(second.transform(), workspace.part(2).orElseThrow().transform());
         assertEquals(2, io.github.fastformer.client.input.drag.WorkspaceGizmoDrag.targets(workspace, 0, true).size());
      } finally {
         workspace.clear();
         WorkspaceInteractionResolver.clearCache();
      }
   }

   @Test
   void lockedPartGizmoCanOnlyBePickedWhenVisibleAndWorkspaceIsEditable() {
      var workspace = io.github.fastformer.client.operation.controller.ClientOperationController.workspace();
      workspace.clear();
      try {
         var part = transformedPart(1);
         var other = transformedPart(2).withTranslation(new Vec3(100, 0, 0));
         assertTrue(workspace.addParts(List.of(part, other)));
         workspace.selectOnly(2);
         io.github.fastformer.client.operation.controller.ClientOperationController.onClientTick();
         Vec3 center = PartInteractionBounds.resolve(part).getCenter();
         Vec3 camera = center.add(0, 0, 10);
         var scale = io.github.fastformer.client.gizmo.GizmoViewScale.fromDistance(10);
         var gizmo = partGizmos(part, center, scale).getFirst();
         var handle = gizmo.handles().stream()
            .filter(value -> value.operation() == AxisGizmo.Operation.MOVE)
            .findFirst().orElseThrow();
         Vec3 target = gizmo.handleCenter(handle);
         var context = new io.github.fastformer.client.input.InteractionContext(
            null, null, target.add(0, 0, 10), new Vec3(0, 0, -1), camera, false, false, false
         );
         var provider = WorkspaceInteractionResolver.providers().get(1);
         var hiddenObject = io.github.fastformer.client.operation.controller.ClientOperationController
            .interactionScene().parts().get(1).gizmo();
         assertEquals(false, io.github.fastformer.client.interaction.InteractionVisibility.isVisible(hiddenObject, false));
         assertTrue(provider.resolve(context).isEmpty());
         workspace.selectOnly(1);
         io.github.fastformer.client.operation.controller.ClientOperationController.onClientTick();
         assertTrue(io.github.fastformer.client.interaction.InteractionVisibility.isVisible(hiddenObject, true));
         var intent = (OperationInteractionIntent.Gizmo)provider.resolve(context).orElseThrow();
         assertEquals(1, intent.partId());
         assertEquals(Set.of(1), workspace.selectedIds());
         assertEquals(part.transform(), workspace.part(1).orElseThrow().transform());
         assertEquals(false, WorkspacePartInteractionCapabilities.canEditSource(part));
         workspace.setLocked(true);
         assertTrue(provider.resolve(context).isEmpty());
      } finally {
         workspace.clear();
         WorkspaceInteractionResolver.clearCache();
      }
   }

   @Test
   void selectionCreationPointUsesHitSurface() {
      BlockPos hitBlock = new BlockPos(3, 4, 5);
      BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(hitBlock), Direction.UP, hitBlock, false);

      assertEquals(hitBlock.above(), WorkspaceInteractionResolver.selectionCreationPoint(hit));
   }

   @Test
   void withoutFailedTargetsPreservesOrderAndLeavesInputUntouched() {
      BlockPos first = BlockPos.ZERO;
      BlockPos failed = new BlockPos(1, 0, 0);
      LinkedHashMap<BlockPos, String> blocks = new LinkedHashMap<>();
      blocks.put(first, "first");
      blocks.put(failed, "failed");

      Map<BlockPos, String> visible = WorkspaceInteractionResolver.withoutFailedTargets(blocks, Set.of(failed));

      assertEquals(Map.of(first, "first"), visible);
      assertEquals(2, blocks.size());
   }

   @Test
   void interactionBoundsFallsBackToSelectionWhenPreviewIsOverBudget() {
      var selection = OperationSelectionVolume.cuboid(
         new BlockPos(-4, 2, 7), new BlockPos(12, 9, 18), BlockPos.ZERO, BlockPos.ZERO
      );
      var part = new ClientSelectionPart(1, ClientSelectionPart.Source.WORLD,
         selection, Map.of(), WorkspaceTransform.IDENTITY, false);

      assertEquals(selection.bounds(), PartInteractionBounds.resolve(part));
      assertTrue(WorkspacePartInteractionCapabilities.canSelect(part, selection.bounds()));
   }

   @Test
   void lockedPartRemainsSelectableFromItsSelectionBounds() {
      var selection = OperationSelectionVolume.cuboid(
         new BlockPos(-4, 2, 7), new BlockPos(12, 9, 18), BlockPos.ZERO, BlockPos.ZERO
      );
      var part = new ClientSelectionPart(
         1,
         ClientSelectionPart.Source.WORLD,
         selection,
         Map.of(),
         WorkspaceTransform.IDENTITY,
         false
      ).withTranslation(new Vec3(2.0, 0.0, 0.0));

      assertEquals(ClientSelectionPart.Editability.LOCKED, part.editability());
      assertTrue(WorkspacePartInteractionCapabilities.canSelect(
         part, PartInteractionBounds.resolve(part)
      ));
      assertEquals(false, WorkspacePartInteractionCapabilities.canEditSource(part));
   }

   @Test
   void emptyUntransformedPrismRemainsSelectableWithoutPreviewBlocks() {
      var selection = OperationSelectionVolume.create(
         io.github.fastformer.fastplace.selection.OperationSelectionMode.CONVEX_HULL,
         java.util.List.of(BlockPos.ZERO, new BlockPos(3, 0, 0), new BlockPos(0, 0, 3)),
         BlockPos.ZERO,
         BlockPos.ZERO,
         0
      );
      var part = new ClientSelectionPart(
         1, ClientSelectionPart.Source.WORLD, selection, Map.of(), WorkspaceTransform.IDENTITY, false
      );

      assertEquals(selection.bounds(), PartInteractionBounds.resolve(part));
      assertTrue(WorkspacePartInteractionCapabilities.canSelect(
         part, PartInteractionBounds.resolve(part)
      ));
   }

   @Test
   void submissionLockDoesNotChangePartSelectionCapability() {
      var selection = OperationSelectionVolume.cuboid(
         BlockPos.ZERO, new BlockPos(2, 1, 1), BlockPos.ZERO, BlockPos.ZERO
      );
      var part = new ClientSelectionPart(
         1, ClientSelectionPart.Source.WORLD, selection, Map.of(), WorkspaceTransform.IDENTITY, false
      );
      var workspace = new ClientOperationWorkspace();
      assertTrue(workspace.addParts(List.of(part)));
      Set<Integer> selectedBeforeSubmission = Set.copyOf(workspace.selectedIds());

      workspace.setLocked(true);

      assertTrue(workspace.locked());
      assertEquals(selectedBeforeSubmission, workspace.selectedIds());
      assertTrue(WorkspacePartInteractionCapabilities.canSelect(
         part, PartInteractionBounds.resolve(part)
      ));
   }

   @Test
   void lockedTransformedPartResolvesAsASelectionTarget() {
      var part = transformedPart(1);

      assertEquals(1, resolveCtrlPart(part, Set.of(), false).orElseThrow().partId());
   }

   @Test
   void emptyCuboidResolvesFromItsCtrlOutlineWithoutPreviewBlocks() {
      var selection = OperationSelectionVolume.cuboid(
         BlockPos.ZERO, new BlockPos(2, 2, 2), BlockPos.ZERO, BlockPos.ZERO
      );
      var part = new ClientSelectionPart(
         1, ClientSelectionPart.Source.WORLD, selection, Map.of(), WorkspaceTransform.IDENTITY, false
      );

      assertEquals(1, resolveCtrlPart(part, Set.of(), false).orElseThrow().partId());
   }

   @Test
   void failedOutlineSurfaceDoesNotResolveAsASelectionTarget() {
      var selection = OperationSelectionVolume.cuboid(
         BlockPos.ZERO, new BlockPos(2, 2, 2), BlockPos.ZERO, BlockPos.ZERO
      );
      var part = new ClientSelectionPart(
         1, ClientSelectionPart.Source.WORLD, selection, Map.of(), WorkspaceTransform.IDENTITY, false
      );

      Vec3 eye = new Vec3(-5.0, 1.1, 1.5);
      Vec3 view = new Vec3(1.0, 0.0, 0.0);
      var hit = WorkspaceInteractionResolver.resolvePartTarget(
         scene(part), eye, view, true, Set.of(), false
      ).orElseThrow();
      assertEquals(1, hit.partId());
      assertEquals(io.github.fastformer.client.input.OperationInteractionIntent.PartSurface.FRAME, hit.surface());
      assertTrue(WorkspaceInteractionResolver.resolvePartTarget(
         scene(part), eye, view, true, Set.of(new BlockPos(0, 1, 1)), false
      ).isEmpty());
   }

   @Test
   void submissionLockPreventsPartIntentWithoutChangingCapability() {
      var part = transformedPart(1);

      assertTrue(resolveCtrlPart(part, Set.of(), true).isEmpty());
      assertTrue(WorkspacePartInteractionCapabilities.canSelect(
         part, PartInteractionBounds.resolve(part)
      ));
   }

   private static ClientSelectionPart transformedPart(int id) {
      var selection = OperationSelectionVolume.cuboid(
         BlockPos.ZERO, new BlockPos(2, 2, 2), BlockPos.ZERO, BlockPos.ZERO
      );
      return new ClientSelectionPart(
         id,
         ClientSelectionPart.Source.WORLD,
         selection,
         Map.of(),
         WorkspaceTransform.IDENTITY,
         false
      ).withTranslation(new Vec3(2.0, 0.0, 0.0));
   }

   private static Optional<OperationInteractionIntent.Part> resolveCtrlPart(
      ClientSelectionPart part, Set<BlockPos> failedTargets, boolean workspaceLocked
   ) {
      var bounds = PartInteractionBounds.resolve(part);
      Vec3 eye = new Vec3(bounds.minX - 5.0, bounds.getCenter().y, bounds.getCenter().z);
      return WorkspaceInteractionResolver.resolvePartTarget(
         scene(part), eye, new Vec3(1.0, 0.0, 0.0), true, failedTargets, workspaceLocked
      );
   }

   @Test
   void interactionBoundsKeepsTheVisibleSelectionRangeWhenItContainsAir() {
      var selection = OperationSelectionVolume.cuboid(
         new BlockPos(-4, 2, 7), new BlockPos(12, 9, 18), BlockPos.ZERO, BlockPos.ZERO
      );
      var part = new ClientSelectionPart(
         1,
         ClientSelectionPart.Source.WORLD,
         selection,
         Map.of(),
         WorkspaceTransform.IDENTITY,
         false
      );

      assertEquals(
         selection.bounds(),
         PartInteractionBounds.resolve(part)
      );
   }

   @Test
   void rejectedSurfaceCellDoesNotRemainInteractive() {
      BlockPos rejected = new BlockPos(3, 4, 5);
      OperationGeometry.RayHit hit = new OperationGeometry.RayHit(
         new Vec3(4.0, 4.5, 5.5), new Vec3(1.0, 0.0, 0.0), 2.0, 0
      );

      assertEquals(true, WorkspaceInteractionResolver.hitsFailedTarget(hit, Set.of(rejected)));
      assertEquals(false, WorkspaceInteractionResolver.hitsFailedTarget(hit, Set.of(rejected.above())));
   }

   @Test
   void rotatedPartKeepsWorldMoveAxesAndLocalScaleAxes() {
      var selection = OperationSelectionVolume.cuboid(
         BlockPos.ZERO, new BlockPos(2, 1, 1), BlockPos.ZERO, new BlockPos(2, 1, 1)
      );
      var part = new ClientSelectionPart(
         1,
         ClientSelectionPart.Source.WORLD,
         selection,
         Map.of(),
         WorkspaceTransform.IDENTITY.withRotation(new Vec3(0.0, Math.PI * 0.5, 0.0)),
         false
      );

      var gizmos = partGizmos(
         part, Vec3.ZERO, new io.github.fastformer.client.gizmo.GizmoViewScale(1.0, 0.1)
      );
      var worldGizmo = gizmos.get(0);
      var scaleGizmo = gizmos.get(1);

      // A rotation must not turn the move or rotate axes away from the world axes.
      assertEquals(new Vec3(1.0, 0.0, 0.0), worldGizmo.axisVector(AxisGizmo.Axis.X));
      assertTrue(worldGizmo.handles().stream().anyMatch(handle -> handle.operation() == AxisGizmo.Operation.MOVE));
      assertTrue(worldGizmo.handles().stream().anyMatch(handle -> handle.operation() == AxisGizmo.Operation.ROTATE));
      assertTrue(worldGizmo.handles().stream().noneMatch(handle -> handle.operation() == AxisGizmo.Operation.SCALE));

      // Only the scale handles follow the rotated part frame.
      assertEquals(0.0, scaleGizmo.axisVector(AxisGizmo.Axis.X).x, 1.0E-6);
      assertEquals(-1.0, scaleGizmo.axisVector(AxisGizmo.Axis.X).z, 1.0E-6);
      assertTrue(scaleGizmo.handles().stream().allMatch(handle -> handle.operation() == AxisGizmo.Operation.SCALE));
   }

   private static List<AxisGizmo> partGizmos(ClientSelectionPart part, Vec3 center,
      io.github.fastformer.client.gizmo.GizmoViewScale scale) {
      var object = io.github.fastformer.client.interaction.SelectionGizmoInteraction.createPart(
         java.util.UUID.randomUUID(), 1, part, center);
      return io.github.fastformer.client.interaction.SelectionGizmoInteraction.resolvePart(object, scale).gizmos();
   }

   @Test
   void unrotatedPartKeepsScaleHandlesOnWorldAxes() {
      var selection = OperationSelectionVolume.cuboid(
         BlockPos.ZERO, new BlockPos(2, 1, 1), BlockPos.ZERO, new BlockPos(2, 1, 1)
      );
      var part = new ClientSelectionPart(
         1, ClientSelectionPart.Source.WORLD, selection, Map.of(), WorkspaceTransform.IDENTITY, false
      );

      var gizmos = partGizmos(
         part, Vec3.ZERO, new io.github.fastformer.client.gizmo.GizmoViewScale(1.0, 0.1)
      );

      assertEquals(new Vec3(1.0, 0.0, 0.0), gizmos.get(0).axisVector(AxisGizmo.Axis.X));
      assertEquals(new Vec3(1.0, 0.0, 0.0), gizmos.get(1).axisVector(AxisGizmo.Axis.X));
   }
}
