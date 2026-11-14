package io.github.fastformer.common.geometry.generation;

import io.github.fastformer.common.geometry.generation.FastPlaceGeometry;
import io.github.fastformer.common.geometry.generation.source.*;
import io.github.fastformer.common.geometry.generation.face.*;
import io.github.fastformer.common.geometry.generation.face.entry.*;
import io.github.fastformer.common.geometry.generation.volume.*;
import io.github.fastformer.common.geometry.generation.shape.*;

import io.github.fastformer.common.geometry.LineTieBias;

import io.github.fastformer.common.geometry.model.FillMode;

import io.github.fastformer.common.quickshape.mode.PointMode;
import io.github.fastformer.common.quickshape.mode.LineMode;
import io.github.fastformer.common.quickshape.mode.FaceMode;
import io.github.fastformer.common.quickshape.mode.VolumeMode;
import io.github.fastformer.common.quickshape.mode.RaycastPlacement;


import io.github.fastformer.common.selection.session.*;
import io.github.fastformer.common.geometry.workflow.*;
import io.github.fastformer.common.geometry.workflow.plan.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
