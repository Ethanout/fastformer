package io.github.fastformer.client.render.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.client.operation.model.ClientSelectionPart;
import io.github.fastformer.client.operation.model.WorkspaceTransform;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class OperationPreviewRendererTest {
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
