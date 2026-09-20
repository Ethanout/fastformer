package io.github.fastformer.client.operation.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.client.operation.model.WorkspaceTransform;
import io.github.fastformer.client.operation.selection.OccupiedBlockBounds;
import io.github.fastformer.client.operation.transform.VoxelRotation;
import io.github.fastformer.fastplace.selection.OperationStackRegion;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * Pins the rotation frame contract between the voxel pipeline and the render envelope.
 *
 * <p>One tolerance of one block is allowed: the voxel pipeline discretises every axis, so a
 * cell can reach at most one block past the continuous envelope. The separation this contract
 * removes was larger than that.
 */
class RotationFrameContractTest {
   private static final double TOLERANCE = 1.0;
   private static final AABB AIR_SELECTION = new AABB(0.0, 0.0, 0.0, 5.0, 1.0, 1.0);

   private static Map<BlockPos, String> singleBlock() {
      return Map.of(BlockPos.ZERO, "only");
   }

   private static Map<BlockPos, String> sparseLayer() {
      LinkedHashMap<BlockPos, String> source = new LinkedHashMap<>();
      source.put(new BlockPos(0, 0, 0), "corner");
      source.put(new BlockPos(1, 0, 0), "east");
      source.put(new BlockPos(0, 0, 1), "south");
      return source;
   }

   private static WorkspaceTransform rotated(Vec3 rotation) {
      return new WorkspaceTransform(Vec3.ZERO, rotation, OperationStackRegion.origin());
   }

   private static AABB occupied(Map<BlockPos, String> resolved) {
      return OccupiedBlockBounds.from(resolved.keySet()).orElseThrow().aabb();
   }

   private static void assertOnBlocks(AABB envelope, AABB blocks, String label) {
      assertTrue(envelope.minX <= blocks.minX + TOLERANCE, label + " minX");
      assertTrue(envelope.minY <= blocks.minY + TOLERANCE, label + " minY");
      assertTrue(envelope.minZ <= blocks.minZ + TOLERANCE, label + " minZ");
      assertTrue(envelope.maxX >= blocks.maxX - TOLERANCE, label + " maxX");
      assertTrue(envelope.maxY >= blocks.maxY - TOLERANCE, label + " maxY");
      assertTrue(envelope.maxZ >= blocks.maxZ - TOLERANCE, label + " maxZ");
   }

   @Test
   void quarterTurnEnvelopeRotatesAboutTheOccupiedCentreNotTheSelectionCentre() {
      Map<BlockPos, String> blocks = singleBlock();
      WorkspaceTransform transform = rotated(new Vec3(0.0, Math.PI * 0.5, 0.0));

      WorkspacePreviewComposer.GeometryFrame frame = WorkspacePreviewComposer.geometryFrame(blocks, transform);
      AABB envelope = WorkspaceSelectionBounds.transformedBox(AIR_SELECTION, transform, frame);

      assertEquals(1, frame.rotationSteps().size());
      assertEquals(AxisGizmo.Axis.Y, frame.rotationSteps().getFirst().axis());
      assertEquals(new Vec3(0.5, 0.5, 0.5), frame.rotationSteps().getFirst().pivot());

      // The only block sits at (0,0,0). Rotating the box about that block's centre puts the
      // long selection span on Z: x 0..1 and z -4..1. Rotating about the selection centre
      // would put the span at x 2..3 and leave the block outside the frame.
      assertEquals(0.0, envelope.minX, 1.0E-6);
      assertEquals(1.0, envelope.maxX, 1.0E-6);
      assertEquals(-4.0, envelope.minZ, 1.0E-6);
      assertEquals(1.0, envelope.maxZ, 1.0E-6);
      assertOnBlocks(envelope, occupied(WorkspacePreviewComposer.resolveValues(blocks, transform)), "quarter turn");
   }

   @Test
   void multiAxisEnvelopeUsesEveryPublishedStepAndStaysOnTheBlocks() {
      Map<BlockPos, String> blocks = sparseLayer();
      WorkspaceTransform transform = rotated(new Vec3(Math.toRadians(25.0), Math.toRadians(40.0), Math.toRadians(-15.0)));

      WorkspacePreviewComposer.GeometryFrame frame = WorkspacePreviewComposer.geometryFrame(blocks, transform);
      AABB envelope = WorkspaceSelectionBounds.transformedBox(AIR_SELECTION, transform, frame);

      VoxelRotation.RotationResult<String> pipeline = VoxelRotation.rotateStage(blocks, transform.rotation());
      assertEquals(pipeline.steps(), frame.rotationSteps());
      assertEquals(3, frame.rotationSteps().size());
      assertOnBlocks(envelope, occupied(WorkspacePreviewComposer.resolveValues(blocks, transform)), "multi axis");
   }

   @Test
   void scaledEnvelopeAnchorsOnTheOccupiedCentre() {
      Map<BlockPos, String> blocks = singleBlock();
      WorkspaceTransform transform = new WorkspaceTransform(
         Vec3.ZERO, Vec3.ZERO, OperationStackRegion.origin(), BlockPos.ZERO, new Vec3(2.0, 1.0, 1.0)
      );

      WorkspacePreviewComposer.GeometryFrame frame = WorkspacePreviewComposer.geometryFrame(blocks, transform);
      AABB envelope = WorkspaceSelectionBounds.transformedBox(AIR_SELECTION, transform, frame);

      // The scale stage anchors on the occupied centre, and the box must follow that anchor.
      assertEquals(new Vec3(0.5, 0.5, 0.5), frame.scaleAnchor());
      assertEquals(0.5, envelope.getCenter().x, 1.0E-6);
      assertEquals(10.0, envelope.getXsize(), 1.0E-6);
      assertOnBlocks(envelope, occupied(WorkspacePreviewComposer.resolveValues(blocks, transform)), "scaled");
   }

   @Test
   void repeatedEnvelopeUsesThePipelineCellStride() {
      Map<BlockPos, String> blocks = singleBlock();
      WorkspaceTransform transform = new WorkspaceTransform(
         Vec3.ZERO, Vec3.ZERO, new OperationStackRegion(BlockPos.ZERO, new BlockPos(2, 0, 0)),
         BlockPos.ZERO, new Vec3(1.0, 1.0, 1.0)
      );

      WorkspacePreviewComposer.GeometryFrame frame = WorkspacePreviewComposer.geometryFrame(blocks, transform);
      AABB envelope = WorkspaceSelectionBounds.transformedBox(AIR_SELECTION, transform, frame);

      // A zero repeat stride means one occupied cell, not one selection width.
      assertEquals(new BlockPos(1, 1, 1), frame.repeatStrideCells());
      assertOnBlocks(envelope, occupied(WorkspacePreviewComposer.resolveValues(blocks, transform)), "repeated");
   }

   @Test
   void translationMovesTheRotatedEnvelopeWithoutRotatingTheDisplacement() {
      Map<BlockPos, String> blocks = sparseLayer();
      Vec3 translation = new Vec3(12.0, -7.0, 5.0);
      for (Vec3 rotation : java.util.List.of(
         new Vec3(0.0, Math.PI * 0.5, 0.0),
         new Vec3(0.3, 0.7, -0.2)
      )) {
         WorkspaceTransform original = rotated(rotation);
         WorkspaceTransform moved = original.withTranslation(translation);
         var frame = WorkspacePreviewComposer.geometryFrame(blocks, original);
         var movedFrame = WorkspacePreviewComposer.geometryFrame(blocks, moved);
         AABB expected = WorkspaceSelectionBounds.transformedBox(AIR_SELECTION, original, frame).move(translation);
         AABB actual = WorkspaceSelectionBounds.transformedBox(AIR_SELECTION, moved, movedFrame);
         assertEquals(expected.minX, actual.minX, 1.0E-6);
         assertEquals(expected.minY, actual.minY, 1.0E-6);
         assertEquals(expected.minZ, actual.minZ, 1.0E-6);
         assertEquals(expected.maxX, actual.maxX, 1.0E-6);
         assertEquals(expected.maxY, actual.maxY, 1.0E-6);
         assertEquals(expected.maxZ, actual.maxZ, 1.0E-6);
         assertOnBlocks(actual, occupied(WorkspacePreviewComposer.resolveValues(blocks, moved)), "translated rotation");
      }
   }

   @Test
   void identityTransformPublishesNoFrame() {
      assertNull(WorkspacePreviewComposer.geometryFrame(singleBlock(), WorkspaceTransform.IDENTITY));
   }
}
