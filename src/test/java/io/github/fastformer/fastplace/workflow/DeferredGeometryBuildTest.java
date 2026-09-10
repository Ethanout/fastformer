package io.github.fastformer.fastplace.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.fastformer.fastplace.FillMode;
import io.github.fastformer.fastplace.GeometryMode;
import io.github.fastformer.fastplace.session.GeometrySession;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class DeferredGeometryBuildTest {
   @Test
   void wallGenerationUsesConfirmedPointsAndExtrusionAfterSessionChanges() {
      GeometrySession session = session(GeometryMode.WALL);
      session.close();
      session.adjustExtrusion(new BlockPos(0, 3, 0));
      var build = new WallWorkflow().build(session, null, FillMode.SOLID, 10000);
      var expected = build.generation().get();
      assertFalse(expected.blocks().isEmpty());

      session.adjustExtrusion(new BlockPos(0, 10, 0));
      session.setMode(GeometryMode.WALL);

      assertEquals(expected.blocks(), build.generation().get().blocks());
   }

   @Test
   void compoundGenerationDoesNotReadReplacementSessionPoints() {
      GeometrySession session = session(GeometryMode.COMPOUND);
      var build = new CompoundWorkflow().build(session, null, FillMode.SOLID, 10000);
      var expected = build.generation().get();
      assertFalse(expected.blocks().isEmpty());

      session.setMode(GeometryMode.COMPOUND);
      session.addPoint(new BlockPos(100, 100, 100));

      assertEquals(expected.blocks(), build.generation().get().blocks());
   }

   private static GeometrySession session(GeometryMode mode) {
      GeometrySession session = new GeometrySession();
      session.setMode(mode);
      session.addPoint(BlockPos.ZERO);
      session.addPoint(new BlockPos(4, 0, 0));
      session.addPoint(new BlockPos(4, 0, 4));
      return session;
   }
}
