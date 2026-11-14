package io.github.fastformer.client.render.guide;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class SelectionPointGuidesTest {
   @Test void planeKeepsTheCapturedTargetInItsBounds() {
      var target = new BlockPos(8, 2, 4);
      var result = SelectionPointGuides.capture(List.of(BlockPos.ZERO), target, Vec3.ZERO,
         Vec3.ZERO, new Vec3(0, 1, 0), null, null);
      assertEquals(List.of(Vec3.atCenterOf(BlockPos.ZERO), Vec3.atCenterOf(target)), result.planes().getFirst().bounds());
      assertTrue(result.planes().getFirst().showWhenAxisAligned());
   }

   @Test void lineExtentIncludesTargetAndCameraDistance() {
      var result = SelectionPointGuides.capture(List.of(), new BlockPos(20, 0, 0), Vec3.ZERO,
         null, null, Vec3.ZERO, new Vec3(1, 0, 0));
      assertEquals(-24.5, result.lines().getFirst().from().x);
      assertEquals(24.5, result.lines().getFirst().to().x);
      var far = SelectionPointGuides.capture(List.of(), null, new Vec3(100, 0, 0),
         null, null, Vec3.ZERO, new Vec3(1, 0, 0));
      assertEquals(30, far.lines().getFirst().to().x);
   }
}
