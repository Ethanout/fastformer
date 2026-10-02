package io.github.fastformer.client.render.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.workspace.model.ClientSelectionPart;
import io.github.fastformer.workspace.model.WorkspaceTransform;
import io.github.fastformer.client.operation.controller.ClientOperationController;
import io.github.fastformer.client.interaction.InteractionVisibility;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import io.github.fastformer.fastplace.selection.OperationSelectionVolume;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class OperationPreviewRendererTest {
   @Test
   void worldSelectionDoesNotDrawDuplicateBlocksBeforeMoving() {
      var part = ClientSelectionPart.empty(ClientSelectionPart.Source.WORLD);
      assertFalse(OperationPreviewRenderer.shouldRenderPartBlocks(part));
      assertFalse(OperationPreviewRenderer.shouldRenderPartBlocks(part.fixed()));
      var moved = part.withTranslation(new BlockPos(1, 0, 0));
      assertTrue(OperationPreviewRenderer.shouldRenderPartBlocks(moved));
      assertTrue(OperationPreviewRenderer.shouldRenderPartBlocks(moved.withTranslation(BlockPos.ZERO)));
      assertTrue(OperationPreviewRenderer.shouldRenderPartBlocks(
         ClientSelectionPart.empty(ClientSelectionPart.Source.CLIPBOARD)));
   }
   @Test
   void olderSelectionKeepsItsOutlineAndGizmoAfterReturningWithinOneDrag() {
      assertReturnedSelectionVisible(false);
   }

   @Test
   void olderSelectionKeepsItsOutlineAndGizmoAfterMovingBackInAnotherDrag() {
      assertReturnedSelectionVisible(true);
   }

   private static void assertReturnedSelectionVisible(boolean separateDrag) {
      ClientOperationController.clearWorkspace();
      try {
         var workspace = ClientOperationController.workspace();
         var selection = OperationSelectionVolume.cuboid(BlockPos.ZERO, new BlockPos(2, 2, 2), BlockPos.ZERO, new BlockPos(2, 2, 2));
         var first = new ClientSelectionPart(1, ClientSelectionPart.Source.WORLD, selection, Map.of(), WorkspaceTransform.IDENTITY, false);
         var secondSelection = OperationSelectionVolume.cuboid(new BlockPos(10, 0, 0), new BlockPos(12, 2, 2), new BlockPos(10, 0, 0), new BlockPos(12, 2, 2));
         var second = new ClientSelectionPart(2, ClientSelectionPart.Source.WORLD, secondSelection, Map.of(), WorkspaceTransform.IDENTITY, false);
         assertTrue(workspace.addParts(List.of(first, second)));
         workspace.selectOnly(1);
         first = workspace.part(1).orElseThrow();
         second = workspace.part(2).orElseThrow();
         assertTrue(workspace.beginEdit());
         var token = workspace.activeEditToken();
         ClientOperationController.updateTransformGesture(token, List.of(first), false,
            AxisGizmo.Operation.MOVE, AxisGizmo.Axis.X, 1, 4, Double.NaN);
         if (separateDrag) {
            assertTrue(ClientOperationController.finishTransformGesture(token));
            first = workspace.part(1).orElseThrow();
            assertTrue(workspace.beginEdit());
            token = workspace.activeEditToken();
         }
         ClientOperationController.updateTransformGesture(token, List.of(first), false,
            AxisGizmo.Operation.MOVE, AxisGizmo.Axis.X, 1, separateDrag ? -4 : 0, Double.NaN);
         var returned = ClientOperationController.interactionScene().parts().get(1);
         assertEquals(selection.bounds(), returned.bounds());
         assertEquals(WorkspaceTransform.IDENTITY, returned.source().transform());
         assertFalse(returned.source().canAdjustGeometry());
         assertTrue(OperationPreviewRenderer.shouldRenderPartOutline(returned, true));
         assertTrue(OperationPreviewRenderer.shouldRenderPartOutline(returned, false));
         assertTrue(InteractionVisibility.isVisible(returned.gizmo(), true));
         assertFalse(InteractionVisibility.isVisible(returned.gizmo(), false));
         assertTrue(ClientOperationController.finishTransformGesture(token));
         ClientOperationController.selectWorkspacePart(2, false);
         assertTrue(OperationPreviewRenderer.shouldRenderPartOutline(ClientOperationController.interactionScene().parts().get(1), false));
         assertEquals(second, workspace.latestPart().orElseThrow());
         assertEquals(2, workspace.size());
      } finally {
         ClientOperationController.clearWorkspace();
      }
   }

   @Test
   void enterFixedSelectionKeepsItsOutlineAndSelectedGizmoWithoutMoving() {
      ClientOperationController.clearWorkspace();
      try {
         assertTrue(ClientOperationController.handleCreateClick(0, BlockPos.ZERO));
         assertTrue(ClientOperationController.handleCreateClick(1, new BlockPos(2, 2, 2)));
         assertTrue(ClientOperationController.fixActiveSelection());
         var part = ClientOperationController.interactionScene().parts().get(1);
         assertTrue(OperationPreviewRenderer.shouldRenderPartOutline(part, false));
         assertTrue(InteractionVisibility.isVisible(part.gizmo(), true));
         assertFalse(part.source().canAdjustGeometry());
         assertFalse(part.source().masksSourceBlocks());
      } finally {
         ClientOperationController.clearWorkspace();
      }
   }

   @Test
   void transformedLockedPartKeepsItsBlockPreview() {
      ClientSelectionPart part = ClientSelectionPart.empty(ClientSelectionPart.Source.WORLD)
         .withTransform(WorkspaceTransform.IDENTITY.withTranslation(new Vec3(1.0, 0.0, 0.0)));

      assertTrue(part.transformed());
      assertTrue(part.editability() == ClientSelectionPart.Editability.LOCKED);
      assertTrue(OperationPreviewRenderer.shouldRenderPartBlocks(part));
   }

   @Test
   void pendingDeletePartUsesDeletePreviewInsteadOfBlockPreview() {
      ClientSelectionPart part = new ClientSelectionPart(
         1,
         ClientSelectionPart.Source.WORLD,
         null,
         Map.of(),
         WorkspaceTransform.IDENTITY,
         true
      );

      assertFalse(OperationPreviewRenderer.shouldRenderPartBlocks(part));
   }

   @Test
   void altDoesNotHideTheServerSelectionBeforeItCreatesADraft() {
      assertTrue(OperationPreviewRenderer.shouldRenderServerSelection(true, false, false));
      assertFalse(OperationPreviewRenderer.shouldRenderServerSelection(true, true, false));
      assertFalse(OperationPreviewRenderer.shouldRenderServerSelection(true, false, true));
   }

   @Test
   void overlappingPreviewCellIsRenderedOnlyByItsFinalOwner() {
      BlockPos shared = new BlockPos(4, 5, 6);
      BlockPos firstOnly = new BlockPos(3, 5, 6);
      Map<BlockPos, String> firstBlocks = new LinkedHashMap<>();
      firstBlocks.put(firstOnly, "first-only");
      firstBlocks.put(shared, "first-shared");
      Map<BlockPos, Integer> owners = Map.of(firstOnly, 1, shared, 2);

      Map<BlockPos, String> owned = OperationPreviewRenderer.blocksOwnedByPart(firstBlocks, owners, 1);

      assertTrue(owned.containsKey(firstOnly));
      assertFalse(owned.containsKey(shared));
   }

   @Test
   void prismFaceCenterRejectsABaseThatCannotFormAFace() {
      Vec3 extrusion = new Vec3(0.0, 3.0, 0.0);

      assertNull(OperationPreviewRenderer.prismFaceCenter(List.of(), extrusion));
      assertNull(OperationPreviewRenderer.prismFaceCenter(List.of(Vec3.ZERO), extrusion));
      assertNull(
         OperationPreviewRenderer.prismFaceCenter(List.of(Vec3.ZERO, new Vec3(4.0, 0.0, 0.0)), extrusion)
      );
   }

   @Test
   void prismFaceCenterSitsHalfAnExtrusionAboveTheBaseCentroid() {
      // A prism spans base .. base + extrusion, so its face centre is the base
      // vertex mean plus half the extrusion.
      List<Vec3> base = List.of(Vec3.ZERO, new Vec3(6.0, 0.0, 0.0), new Vec3(0.0, 0.0, 3.0));

      Vec3 center = OperationPreviewRenderer.prismFaceCenter(base, new Vec3(0.0, 3.0, 0.0));

      // Vertex mean = ((0+6+0)/3, 0, (0+0+3)/3) = (2, 0, 1); plus (0, 1.5, 0).
      assertEquals(2.0, center.x, 1.0E-9);
      assertEquals(1.5, center.y, 1.0E-9);
      assertEquals(1.0, center.z, 1.0E-9);
   }
}
