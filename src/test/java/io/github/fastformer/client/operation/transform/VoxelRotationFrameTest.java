package io.github.fastformer.client.operation.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import io.github.fastformer.client.operation.selection.OccupiedBlockBounds;
import io.github.fastformer.fastplace.geometry.AxisGizmo;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Pins the rotation steps the voxel pipeline publishes to render geometry. */
class VoxelRotationFrameTest {
   private static Map<BlockPos, String> sparseLayer() {
      LinkedHashMap<BlockPos, String> source = new LinkedHashMap<>();
      source.put(new BlockPos(0, 0, 0), "corner");
      source.put(new BlockPos(1, 0, 0), "east");
      source.put(new BlockPos(0, 0, 1), "south");
      return source;
   }

   /**
    * A two cell line. Its box centre moves when the first axis rotates the set about that
    * centre, which is what makes the second pivot observable. A set whose box centre is
    * invariant under its own rotation cannot show the difference.
    */
   private static Map<BlockPos, String> asymmetricLine() {
      LinkedHashMap<BlockPos, String> source = new LinkedHashMap<>();
      source.put(new BlockPos(0, 0, 0), "west");
      source.put(new BlockPos(1, 0, 0), "east");
      return source;
   }

   @Test
   void secondAxisPivotsOnTheCentreTheFirstAxisProduced() {
      Map<BlockPos, String> source = asymmetricLine();
      VoxelRotation.RotationResult<String> result = VoxelRotation.rotateStage(
         source, new Vec3(0.0, Math.PI * 0.5, Math.toRadians(30.0))
      );

      assertEquals(2, result.steps().size());
      assertEquals(AxisGizmo.Axis.Y, result.steps().get(0).axis());
      assertEquals(OccupiedBlockBounds.from(source.keySet()).orElseThrow().center(), result.steps().get(0).pivot());

      VoxelRotation.RotationStep yStep = result.steps().get(0);
      Map<BlockPos, String> afterY = VoxelRotation.rotateValues(source, yStep.pivot(), yStep.axis(), yStep.radians());
      Vec3 rotatedCentre = OccupiedBlockBounds.from(afterY.keySet()).orElseThrow().center();

      // The second axis re-centres on the result of the first axis. One fixed pivot would keep
      // the original centre and separate the render envelope from the blocks it describes.
      assertEquals(AxisGizmo.Axis.Z, result.steps().get(1).axis());
      assertEquals(rotatedCentre, result.steps().get(1).pivot());
      assertNotEquals(result.steps().get(0).pivot(), result.steps().get(1).pivot());
   }

   @Test
   void rotationStageMatchesApplyingEachAxisInOrder() {
      Map<BlockPos, String> source = sparseLayer();
      Vec3 rotation = new Vec3(Math.toRadians(25.0), Math.toRadians(40.0), Math.toRadians(-15.0));

      VoxelRotation.RotationResult<String> stage = VoxelRotation.rotateStage(source, rotation);
      Map<BlockPos, String> manual = source;
      for (AxisGizmo.Axis axis : AxisGizmo.Axis.values()) {
         double radians = axis == AxisGizmo.Axis.X
            ? rotation.x
            : axis == AxisGizmo.Axis.Y ? rotation.y : rotation.z;
         OccupiedBlockBounds bounds = OccupiedBlockBounds.from(manual.keySet()).orElse(null);
         if (bounds != null && Math.abs(radians) > VoxelRotation.POSITION_EPSILON) {
            manual = VoxelRotation.rotateValues(manual, bounds.center(), axis, radians);
         }
      }

      assertEquals(manual, stage.values());
      assertEquals(3, stage.steps().size());
   }

   @Test
   void rotationStageReportsNoStepForAnUnrotatedTransform() {
      VoxelRotation.RotationResult<String> result = VoxelRotation.rotateStage(sparseLayer(), Vec3.ZERO);

      assertEquals(0, result.steps().size());
      assertEquals(sparseLayer(), result.values());
   }
}
