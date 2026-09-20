package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.quickshape.PointMode;
import io.github.fastformer.fastplace.quickshape.LineMode;
import io.github.fastformer.fastplace.quickshape.FaceMode;
import io.github.fastformer.fastplace.quickshape.VolumeMode;
import io.github.fastformer.fastplace.quickshape.RaycastPlacement;

import io.github.fastformer.fastplace.world.*;

import io.github.fastformer.fastplace.session.*;
import io.github.fastformer.fastplace.workflow.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.geometry.GuideLine;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class FastPlaceGuideLinesTest {
   private static final FastPlaceGeometry.Modes MODES = new FastPlaceGeometry.Modes(
      PointMode.RAYCAST,
      RaycastPlacement.EMBEDDED,
      LineMode.AXIS,
      FaceMode.COORDINATE_PLANE,
      VolumeMode.PERPENDICULAR_TO_FACE,
      FillMode.OUTLINE,
      0.0,
      false
   );

   @Test
   void lineStageHasNoRedundantGuideLine() {
      List<GuideLine> lines = FastPlaceGeometry.guideLines(
         List.of(BlockPos.ZERO), false, Vec3.ZERO, null,
         new Vec3(8.0, 6.0, 8.0), new Vec3(-1.0, -0.25, -0.5), MODES
      );

      assertTrue(lines.isEmpty());
   }

   @Test
   void parallelogramFaceHasNoLineThroughConfirmedBlocks() {
      FastPlaceGeometry.Modes modes = new FastPlaceGeometry.Modes(
         PointMode.RAYCAST,
         RaycastPlacement.EMBEDDED,
         LineMode.AXIS,
         FaceMode.PARALLELOGRAM_BASE_PLANE,
         VolumeMode.PERPENDICULAR_TO_FACE,
         FillMode.OUTLINE,
         0.0,
         false
      );

      List<GuideLine> lines = FastPlaceGeometry.guideLines(
         List.of(BlockPos.ZERO, new BlockPos(4, 0, 0)), false, Vec3.ZERO, null,
         new Vec3(8.0, 6.0, 8.0), new Vec3(-1.0, -0.25, -0.5), modes
      );

      assertTrue(lines.isEmpty());
   }

   @Test
   void perpendicularVolumeShowsLongAxisAndRayConnector() {
      List<GuideLine> lines = FastPlaceGeometry.guideLines(
         List.of(BlockPos.ZERO, new BlockPos(4, 0, 0), new BlockPos(0, 0, 4)),
         false,
         Vec3.ZERO,
         null,
         new Vec3(8.0, 6.0, 8.0),
         new Vec3(-1.0, -0.25, -0.5),
         MODES
      );

      assertEquals(2, lines.size());
      assertEquals(512.0, lines.getFirst().from().distanceTo(lines.getFirst().to()), 1.0E-9);
      assertTrue(lines.get(1).from().distanceToSqr(lines.get(1).to()) > 1.0E-9);
   }
}
