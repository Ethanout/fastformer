package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;
import io.github.fastformer.client.operation.transform.PixelPerfectAngles;
import org.junit.jupiter.api.Test;

class RotationInputAnglesTest {
   @Test
   void altRotationConsumesOnlyTheModifierThatOwnsTheGesture() {
      var owner = new ModifierGestureState();
      var other = new ModifierGestureState();
      owner.press(100, true, true);
      other.press(100, true, true);
      double angle = RotationInputAngles.resolve(73, false, owner);
      assertEquals(PixelPerfectAngles.snap(73 * Math.PI * 2 / 1024), angle);
      assertTrue(owner.consumed());
      assertFalse(other.consumed());
      assertFalse(owner.release(110, 1000).shortPress());
      assertTrue(other.release(110, 1000).shortPress());
   }

   @Test
   void controlTakesPriorityWithoutConsumingAnAltTap() {
      var modifier = new ModifierGestureState();
      modifier.press(100, true, true);
      assertEquals(PixelPerfectAngles.free(73 * Math.PI * 2 / 1024),
         RotationInputAngles.resolve(73, true, modifier));
      assertFalse(modifier.consumed());
      assertTrue(modifier.release(110, 1000).shortPress());
   }

   @Test
   void ordinaryRotationLeavesTheModifierIdle() {
      var modifier = new ModifierGestureState();
      assertEquals(PixelPerfectAngles.defaultSnap(-73 * Math.PI * 2 / 1024),
         RotationInputAngles.resolve(-73, false, modifier));
      assertFalse(modifier.held());
   }
}
