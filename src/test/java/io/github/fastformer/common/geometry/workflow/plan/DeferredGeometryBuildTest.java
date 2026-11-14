package io.github.fastformer.common.geometry.workflow.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.fastformer.common.geometry.model.FillMode;
import io.github.fastformer.common.geometry.GeometryMode;
import io.github.fastformer.common.geometry.session.GeometrySession;
import io.github.fastformer.common.geometry.workflow.GeometryWorkflowView;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class DeferredGeometryBuildTest {
   @Test
   void wallGenerationUsesConfirmedPointsAndExtrusionAfterSessionChanges() {
      GeometrySession session = session(GeometryMode.WALL);
      session.close();
      session.wall().adjustExtrusion(new BlockPos(0, 3, 0));
      var build = new WallWorkflow().build(GeometryWorkflowView.from(session), FillMode.SOLID, 10000);
      var expected = build.generation().get();
      assertFalse(expected.blocks().isEmpty());

      session.wall().adjustExtrusion(new BlockPos(0, 10, 0));
      session.setMode(GeometryMode.WALL);

      assertEquals(expected.blocks(), build.generation().get().blocks());
   }

   @Test
   void compoundGenerationDoesNotReadReplacementSessionPoints() {
      GeometrySession session = session(GeometryMode.COMPOUND);
      var build = new CompoundWorkflow().build(GeometryWorkflowView.from(session), FillMode.SOLID, 10000);
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
