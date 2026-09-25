package io.github.fastformer.fastplace;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.fastplace.session.GeometrySession;
import io.github.fastformer.fastplace.workflow.WallWorkflow;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class GeometryActionContextTest {
   @Test
   void fourthWallPointProjectsTheCapturedRayOntoTheExistingPlane() {
      var session = new GeometrySession();
      session.setMode(GeometryMode.WALL);
      session.addOrClose(new BlockPos(0, 0, 0));
      session.addOrClose(new BlockPos(3, 0, 0));
      session.addOrClose(new BlockPos(0, 0, 3));
      var hit = GeometryHit.point(new BlockPos(9, 0, 9));
      var context = new GeometryActionContext(null, false, hit, new Vec3(1.5, 10, 1.5), new Vec3(0, -1, 0));

      new WallWorkflow().onAddPoint(session, context, hit);

      assertEquals(new BlockPos(1, 0, 1), session.points().getLast());
      assertEquals(4, session.points().size());
   }
}
