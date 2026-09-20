package io.github.fastformer.fastplace.session;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class GeometryShapeStateUndoTest {
   @Test
   void wallUndoClearsExtrusion() {
      var state = new WallGeometryState();
      state.addPoint(Vec3.ZERO);
      state.addPoint(new Vec3(4, 0, 0));
      state.extrusion = new BlockPos(0, 5, 0);
      assertTrue(state.removeOrUndo(null, false));
      assertEquals(List.of(BlockPos.ZERO), state.blockPoints());
      assertEquals(BlockPos.ZERO, state.extrusion);
   }

   @Test
   void coneUndoClearsAdjustmentsWhenReturningToPointPlacement() {
      var state = new ConePrismGeometryState();
      state.addPoint(Vec3.ZERO);
      state.addPoint(new Vec3(4, 0, 0));
      state.addPoint(new Vec3(0, 5, 0));
      state.shapeVariant = 2;
      state.topOffset = new Vec3(2, 0, 1);
      state.topScaleOffset = 0.5;
      state.rotationRadians = 1.0;
      state.gizmoLocal = false;
      assertTrue(state.removeOrUndo(null, false));
      assertEquals(2, state.points().size());
      assertEquals(0, state.shapeVariant);
      assertEquals(Vec3.ZERO, state.topOffset);
      assertEquals(0.0, state.topScaleOffset);
      assertEquals(0.0, state.rotationRadians);
      assertTrue(state.gizmoLocal);
   }

   @Test
   void closedPolyhedronUndoRestoresBaselineBeforeRemovingLastPoint() {
      var state = new PolyhedronGeometryState();
      state.addPoint(Vec3.ZERO);
      state.addPoint(new Vec3(4, 0, 0));
      state.captureAdjustmentBaseline();
      state.clearPoints();
      state.addPoint(new Vec3(10, 0, 0));
      state.addPoint(new Vec3(20, 0, 0));
      state.setClosed(true);
      state.localScale = new Vec3(2, 3, 4);
      state.worldScale = new Vec3(4, 3, 2);
      state.rotation[0] = 0;
      state.gizmoLocal = true;
      assertTrue(state.removeOrUndo(null, false));
      assertEquals(List.of(BlockPos.ZERO), state.blockPoints());
      assertFalse(state.closed());
      assertEquals(new Vec3(1, 1, 1), state.localScale);
      assertEquals(new Vec3(1, 1, 1), state.worldScale);
      assertArrayEquals(PolyhedronGeometryState.IDENTITY_ROTATION, state.rotation);
      assertFalse(state.gizmoLocal);
      assertTrue(state.removeOrUndo(null, false));
      assertFalse(state.removeOrUndo(null, false));
   }
}
