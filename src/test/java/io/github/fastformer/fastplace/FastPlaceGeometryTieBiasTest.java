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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.geometry.generation.LineTieBias;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class FastPlaceGeometryTieBiasTest {
   @Test
   void exactFaceTieUsesTheSelectedBiasWhileAxisLineGeometryStaysUnchanged() {
      FastPlaceGeometry.Modes defaults = modes(LineTieBias.DEFAULT);
      FastPlaceGeometry.Modes opposite = modes(LineTieBias.OPPOSITE);
      List<BlockPos> line = List.of(BlockPos.ZERO, new BlockPos(2, 1, 0));
      List<BlockPos> face = List.of(BlockPos.ZERO, new BlockPos(2, 1, 0), new BlockPos(0, 0, 2));

      assertEquals(FastPlaceGeometry.blocks(line, defaults), FastPlaceGeometry.blocks(line, opposite));
      Set<BlockPos> defaultFace = FastPlaceGeometry.blocks(face, defaults);
      Set<BlockPos> oppositeFace = FastPlaceGeometry.blocks(face, opposite);
      assertNotEquals(defaultFace, oppositeFace);
      assertEquals(defaultFace.size(), oppositeFace.size());
      assertTrue(defaultFace.containsAll(face));
      assertTrue(oppositeFace.containsAll(face));
   }

   private static FastPlaceGeometry.Modes modes(LineTieBias bias) {
      return new FastPlaceGeometry.Modes(
         PointMode.RAYCAST,
         RaycastPlacement.EMBEDDED,
         LineMode.AXIS,
         FaceMode.COORDINATE_PLANE,
         VolumeMode.PERPENDICULAR_TO_FACE,
         FillMode.OUTLINE,
         0.0,
         false
      ).withFaceTieBias(bias);
   }
}
