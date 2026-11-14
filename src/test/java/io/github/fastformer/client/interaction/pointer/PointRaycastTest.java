package io.github.fastformer.client.interaction.pointer;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class PointRaycastTest {
   @Test
   void nearestPointUsesBoundedNormalizedRayAndKeepsOrderForTies() {
      var near = BlockPos.ZERO;
      var far = new BlockPos(0,0,4);
      var eye = new Vec3(0.5,0.5,-2);
      assertEquals(near, PointRaycast.nearest(List.of(far, near), eye, new Vec3(0,0,3), 10));
      assertNull(PointRaycast.nearest(List.of(far, near), eye, new Vec3(0,0,1), 1));
      assertNull(PointRaycast.nearest(List.of(new BlockPos(2,0,0)), eye, new Vec3(0,0,1), 10));
      assertNull(PointRaycast.nearest(List.of(), eye, new Vec3(0,0,1), 10));
   }
}
