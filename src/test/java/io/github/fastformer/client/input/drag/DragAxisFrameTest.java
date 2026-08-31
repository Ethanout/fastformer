package io.github.fastformer.client.input.drag;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class DragAxisFrameTest {
   @Test
   void directionCanChangeWithoutChangingTheAccumulatedPosition() {
      Vec3 axis = new Vec3(1.0, 0.0, 0.0);
      Vec3 boundary = new Vec3(3.0, 0.0, 0.0);
      DragAxisFrame inside = DragAxisFrame.start(Vec3.ZERO, true);

      assertEquals(-3, inside.project(boundary, axis));
      DragAxisFrame outside = inside.rebase(boundary, -3, false);
      assertEquals(-3, outside.project(boundary, axis));
      assertEquals(-1, outside.project(new Vec3(5.0, 0.0, 0.0), axis));
   }
}
