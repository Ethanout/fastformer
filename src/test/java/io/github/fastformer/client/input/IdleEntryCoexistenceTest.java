package io.github.fastformer.client.input;

import io.github.fastformer.common.raycast.InteractionShapeSnapshot;
import io.github.fastformer.client.interaction.binding.input.*;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdleEntryCoexistenceTest {
   private static final long PRESS = 1_000_000_000L;
   private static final Vec3 VIEW = new Vec3(0, 0, 1);
   private static final InteractionShapeSnapshot SHAPE = new InteractionShapeSnapshot(BlockPos.ZERO, List.of(new AABB(0, 0, 0, 1, 1, 1)));
   private static final InputPattern HOLD = new InputPattern("right", "hold", 200, "deferred");

   @Test void nearSecondPressPrecedesHoldAndTinker() {
      var gesture = new IdleEntryGesture();
      Vec3 eye = new Vec3(0.5, 0.5, -3);
      gesture.remember(1, PRESS, SHAPE, eye, VIEW, this, null);
      assertNotNull(gesture.secondPress(PRESS + 190_000_000L, eye, VIEW));
      assertEquals(IdleEntryRules.Owner.DOUBLE_ENTRY, IdleEntryRules.owner(
         new IdleEntryRules.Context(false, false, false, false, false, true, true, IdleEntryRules.Aim.ORDINARY_BLOCK)));
   }

   @Test void fifteenToTwentyBlockSecondPressKeepsThreeHundredMillisecondsAndEdgeMargin() {
      for (double distance : new double[]{15, 17, 20}) {
         var gesture = new IdleEntryGesture();
         Vec3 eye = new Vec3(0.99, 0.5, -distance);
         gesture.remember(1, PRESS, SHAPE, eye, VIEW, this, null);
         Vec3 secondEye = eye.add(0.1, 0, 0);
         assertNull(SHAPE.clip(secondEye, VIEW));
         assertNotNull(gesture.secondPress(PRESS + 300_000_000L, secondEye, VIEW));
         assertNull(gesture.secondPress(PRESS + 300_000_001L, secondEye, VIEW));
      }
   }

   @Test void distantSingleAndAltPreserveTheirEntryGuard() {
      assertEquals(IdleEntryRules.Owner.DISTANT_ENTRY, IdleEntryRules.owner(
         new IdleEntryRules.Context(false, false, false, false, true, true, true, IdleEntryRules.Aim.ORDINARY_BLOCK)));
      assertEquals(IdleEntryRules.Owner.ALT_ENTRY, IdleEntryRules.owner(
         new IdleEntryRules.Context(false, false, false, true, true, true, true, IdleEntryRules.Aim.ORDINARY_BLOCK)));
      var guard = new RightClickInertiaGuard();
      guard.arm(PRESS);
      assertTrue(guard.capture(1, 1, PRESS + 100_000_000L));
      assertTrue(guard.capture(0, 1, PRESS + 400_000_000L));
   }

   @Test void holdReleaseHasNoEntryGuardToSwallowIt() {
      var arbiter = new ButtonArbiter<String>();
      var guard = new RightClickInertiaGuard();
      arbiter.press(1, PRESS, HOLD, "frozen press");
      assertEquals(ButtonArbiter.Kind.START, arbiter.tick(PRESS + 200_000_000L).getFirst().kind());
      assertFalse(guard.capture(0, 1, PRESS + 250_000_000L));
      assertEquals(ButtonArbiter.Kind.RELEASE, arbiter.release(1, PRESS + 250_000_000L).getFirst().kind());
   }

   @Test void endingHoldClearsThePairingAndNextClickStartsFresh() {
      var gesture = new IdleEntryGesture();
      Vec3 eye = new Vec3(0.5, 0.5, -3);
      gesture.remember(1, PRESS, SHAPE, eye, VIEW, this, null);
      gesture.clear();
      assertNull(gesture.secondPress(PRESS + 50_000_000L, eye, VIEW));
      assertEquals(IdleEntryRules.Owner.HOLD_CANDIDATE, IdleEntryRules.owner(
         new IdleEntryRules.Context(false, false, false, false, false, false, false, IdleEntryRules.Aim.ORDINARY_BLOCK)));
   }

   @Test void externalActiveGuardAndVanillaTargetsKeepTheirPriority() {
      assertEquals(IdleEntryRules.Owner.EXTERNAL, IdleEntryRules.owner(new IdleEntryRules.Context(true, true, true, true, true, true, true, IdleEntryRules.Aim.ENTITY)));
      assertEquals(IdleEntryRules.Owner.ACTIVE_SESSION, IdleEntryRules.owner(new IdleEntryRules.Context(false, true, true, true, true, true, true, IdleEntryRules.Aim.ENTITY)));
      assertEquals(IdleEntryRules.Owner.ENTRY_GUARD, IdleEntryRules.owner(new IdleEntryRules.Context(false, false, true, true, true, true, true, IdleEntryRules.Aim.ENTITY)));
      for (var aim : List.of(IdleEntryRules.Aim.AIR, IdleEntryRules.Aim.ENTITY, IdleEntryRules.Aim.PASSTHROUGH_BLOCK))
         assertEquals(IdleEntryRules.Owner.VANILLA, IdleEntryRules.owner(new IdleEntryRules.Context(false, false, false, false, false, false, false, aim)));
   }
}
