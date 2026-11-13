package io.github.fastformer.client.input;

import io.github.fastformer.common.raycast.InteractionShapeSnapshot;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdleEntryGestureTest {
   private static final Vec3 EYE = new Vec3(0.5, 0.75, -2);
   private static final Vec3 VIEW = new Vec3(0, 0, 1);
   private static final InteractionShapeSnapshot FULL = new InteractionShapeSnapshot(BlockPos.ZERO, List.of(new AABB(0, 0, 0, 1, 1, 1)));

   @Test void matchedEntryRetainsBothFirstPressRulesThroughShapeUpdatesAndClear() {
      for (boolean embedded : new boolean[]{false, true}) {
         for (boolean throughFluids : new boolean[]{false, true}) {
            var gesture = new IdleEntryGesture();
            var rules = new io.github.fastformer.common.raycast.InteractionRaycastRules(embedded, throughFluids);
            gesture.remember(5, 100, FULL, EYE, VIEW, this, null, rules);
            gesture.update(5, new InteractionShapeSnapshot(BlockPos.ZERO.north(), FULL.boxes()));
            var match = gesture.secondPress(200, EYE, VIEW);
            assertNotNull(match);
            gesture.clear();
            assertEquals(5, match.token());
            assertEquals(rules, match.rules());
            assertFalse(match.tinker());
            assertEquals(BlockPos.ZERO.north(), match.hit().getBlockPos());
            gesture.remember(6, 300, FULL, EYE, VIEW, this, null);
            assertEquals(io.github.fastformer.common.raycast.InteractionRaycastRules.DEFAULT,
               gesture.secondPress(400, EYE, VIEW).rules());
         }
      }
   }

   @Test void secondPressRequiresTheTimeWindowAndTheCapturedShape() {
      var gesture = new IdleEntryGesture();
      assertNull(gesture.secondPress(100, EYE, VIEW));
      gesture.remember(1, 100, FULL, EYE, VIEW, this, null);
      assertNotNull(gesture.secondPress(100 + 200_000_000L, EYE, VIEW));
      assertNull(gesture.secondPress(101 + 200_000_000L, EYE, VIEW));
      assertNull(gesture.secondPress(99, EYE, VIEW));
      assertNull(gesture.secondPress(200, EYE, new Vec3(1, 0, 0)));
   }

   @Test void aPartialBlockDoesNotBecomeAFullCell() {
      var slab = new InteractionShapeSnapshot(BlockPos.ZERO, List.of(new AABB(0, 0, 0, 1, 0.5, 1)));
      var gesture = new IdleEntryGesture();
      gesture.remember(1, 100, slab, EYE.subtract(0, 0.5, 0), VIEW, this, null);
      assertNull(gesture.secondPress(200, EYE, VIEW));
      assertNotNull(gesture.secondPress(200, EYE.subtract(0, 0.5, 0), VIEW));
   }

   @Test void distantEdgeMissUsesTheLongerWindowAndKeepsTheOriginalSurface() {
      var gesture = new IdleEntryGesture();
      var eye = new Vec3(0.99, 0.75, -20);
      var secondEye = eye.add(0.16, 0, 0);
      gesture.remember(1, 100, FULL, eye, VIEW, this, null);
      assertNull(FULL.clip(secondEye, VIEW));
      var match = gesture.secondPress(100 + 300_000_000L, secondEye, VIEW);
      assertNotNull(match);
      assertEquals(BlockPos.ZERO, match.hit().getBlockPos());
      assertEquals(new Vec3(1, 0.75, 0), match.hit().getLocation());
      assertEquals(net.minecraft.core.Direction.NORTH, match.hit().getDirection());
      assertNull(gesture.secondPress(101 + 300_000_000L, secondEye, VIEW));
      assertNull(gesture.secondPress(200, eye.add(0.4, 0, 0), VIEW));
   }

   @Test void fifteenBlockBoundaryUsesTheFirstPressDistance() {
      var gesture = new IdleEntryGesture();
      var near = new Vec3(0.99, 0.75, -14.99);
      gesture.remember(1, 100, FULL, near, VIEW, this, null);
      assertNull(gesture.secondPress(100 + 250_000_000L, near, VIEW));
      assertNull(gesture.secondPress(200, near.add(0.1, 0, -5), VIEW));
      var far = new Vec3(0.99, 0.75, -15);
      gesture.remember(2, 100, FULL, far, VIEW, this, null);
      assertNotNull(gesture.secondPress(100 + 250_000_000L, far.add(0.1, 0, 5), VIEW));
   }

   @Test void distantPartialShapeStaysPartialAndTinkerSharesTheSameTolerance() {
      var slab = new InteractionShapeSnapshot(BlockPos.ZERO, List.of(new AABB(0, 0, 0, 1, 0.5, 1)));
      var eye = new Vec3(0.5, 0.49, -20);
      for (boolean tinker : new boolean[]{false, true}) {
         var gesture = new IdleEntryGesture();
         if (tinker) gesture.rememberTinker(1, 100, slab, eye, VIEW, this, null);
         else gesture.remember(1, 100, slab, eye, VIEW, this, null);
         var match = gesture.secondPress(100 + 250_000_000L, eye.add(0, 0.1, 0), VIEW);
         assertNotNull(match);
         assertEquals(tinker, match.tinker());
         assertEquals(0.5, match.hit().getLocation().y);
         assertNull(gesture.secondPress(200, eye.add(0, 0.4, 0), VIEW));
      }
   }

   @Test void distantToleranceDoesNotBypassBothGestureLimits() {
      var gesture = new IdleEntryGesture();
      var eye = new Vec3(0.1, 0.75, -20);
      gesture.remember(1, 100, FULL, eye, VIEW, this, null);
      var changedEye = eye.add(-1.6, 0, 0);
      var changedView = horizontalView(7);
      assertNotNull(FULL.clip(changedEye, changedView));
      assertNull(gesture.secondPress(200, changedEye, changedView));
      gesture.clear();
      assertNull(gesture.secondPress(200, eye, VIEW));
   }

   @Test void closeHitsAllowViewChangeBeyondTheAngleThreshold() {
      var gesture = new IdleEntryGesture();
      gesture.remember(1, 100, FULL, EYE, VIEW, this, null);
      var nearbyView = horizontalView(1.9);
      var changedView = horizontalView(2.1);
      assertNotNull(FULL.clip(EYE, changedView));
      assertNotNull(gesture.secondPress(200, EYE, nearbyView));
      assertNotNull(gesture.secondPress(200, EYE, changedView));
      gesture.update(1, FULL);
      assertNotNull(gesture.secondPress(200, EYE, changedView));
   }

   @Test void floatingDoesNotCountAsViewMovement() {
      var gesture = new IdleEntryGesture();
      gesture.remember(1, 100, FULL, EYE, VIEW, this, null);
      assertNotNull(gesture.secondPress(200, EYE.add(0.35, 0.1, 0.4), VIEW));
      assertNull(gesture.secondPress(200, EYE.add(2, 0, 0), VIEW));
   }

   @Test void stableViewAllowsHitMovementBeyondThePositionThreshold() {
      var gesture = new IdleEntryGesture();
      var eye = new Vec3(0.1, 0.75, -2);
      gesture.remember(1, 100, FULL, eye, VIEW, this, null);
      assertNotNull(gesture.secondPress(200, eye.add(0.5, 0, 0), VIEW));
      var shifted = eye.add(0.501, 0, 0);
      assertNotNull(FULL.clip(shifted, VIEW));
      assertNotNull(gesture.secondPress(200, shifted, VIEW));
   }

   @Test void replacementShapeReprojectsTheFirstRayBeforeComparingHits() {
      var gesture = new IdleEntryGesture();
      gesture.remember(1, 100, FULL, EYE, VIEW, this, null);
      var placed = new InteractionShapeSnapshot(BlockPos.ZERO.north(), FULL.boxes());
      gesture.update(1, placed);
      assertEquals(placed.position(), gesture.secondPress(200, EYE, VIEW).hit().getBlockPos());
   }

   @Test void viewComparisonHandlesTheYawWrapAndPitchChanges() {
      var gesture = new IdleEntryGesture();
      var eye = new Vec3(0.5, 0.75, 3);
      gesture.remember(1, 100, FULL, eye, horizontalView(179.5), this, null);
      assertNotNull(gesture.secondPress(200, eye, horizontalView(-179.5)));
      gesture.remember(2, 100, FULL, EYE, VIEW, this, null);
      var pitched = new Vec3(0, -Math.sin(Math.toRadians(3)), Math.cos(Math.toRadians(3)));
      assertNotNull(FULL.clip(EYE, pitched));
      assertNotNull(gesture.secondPress(200, EYE, pitched));
   }

   private static Vec3 horizontalView(double degrees) {
      double angle = Math.toRadians(degrees);
      return new Vec3(Math.sin(angle), 0, Math.cos(angle));
   }

   @Test void wrenchDoubleClickKeepsTheOriginalShapeAndItsRestoreIntent() {
      var gesture = new IdleEntryGesture();
      gesture.rememberTinker(3, 100, FULL, EYE.subtract(0, 0.5, 0), VIEW, this, null);
      assertTrue(gesture.tinker());
      assertNotNull(gesture.secondPress(200, EYE, VIEW));
      assertNotNull(gesture.secondPress(200, EYE.subtract(0, 0.5, 0), VIEW));
      gesture.update(2, FULL);
      assertNotNull(gesture.secondPress(200, EYE, VIEW));
      gesture.clear();
      assertFalse(gesture.tinker());
      assertNull(gesture.secondPress(200, EYE, VIEW));
   }

   @Test void stalePacketsAndAnotherWorldCannotRestoreAnEntry() {
      var gesture = new IdleEntryGesture();
      gesture.remember(2, 100, FULL, EYE, VIEW, this, null);
      gesture.update(1, new InteractionShapeSnapshot(BlockPos.ZERO, List.of()));
      assertNotNull(gesture.secondPress(200, EYE, VIEW));
      gesture.validate(new Object(), null);
      gesture.update(2, FULL);
      assertNull(gesture.secondPress(200, EYE, VIEW));
      assertEquals(0, gesture.token());
   }

   @Test void rejectsWhenBothAngleAndHitDistanceExceedTheirThresholds() {
      var gesture = new IdleEntryGesture();
      var eye = new Vec3(0.1, 0.75, -2);
      gesture.remember(1, 100, FULL, eye, VIEW, this, null);
      var secondEye = eye.add(0.5, 0, 0);
      var secondView = horizontalView(5);
      assertNotNull(FULL.clip(secondEye, secondView));
      assertNull(gesture.secondPress(200, secondEye, secondView));
   }

   @Test void movedSnapshotRemainsIndependentOfWorldAndHasNoDistanceHandoff() {
      var distant = new InteractionShapeSnapshot(new BlockPos(0, 0, 200), FULL.boxes());
      assertNotNull(distant.clip(EYE, VIEW));
      var gesture = new IdleEntryGesture();
      gesture.remember(2, 100, FULL, EYE, VIEW, this, null);
      gesture.update(2, distant);
      assertEquals(distant.position(), gesture.secondPress(200, EYE, VIEW).hit().getBlockPos());
      gesture.clear();
      gesture.update(2, FULL);
      assertNull(gesture.secondPress(200, EYE, VIEW));
   }

   @Test void cancelReturnsToOneIdleStateAndQLeavesIdleToVanilla() {
      var machine = new io.github.fastformer.client.input.state.ClientInputStateMachine();
      assertEquals(io.github.fastformer.client.input.state.ClientInputStateMachine.Dispatch.VANILLA,
         machine.dispatch(io.github.fastformer.client.input.state.ClientInputStateMachine.InputKind.POINTER));
      assertFalse(machine.cancel());
      machine.observe(io.github.fastformer.client.input.state.ClientInputStateMachine.State.BUILDING);
      assertTrue(machine.cancel());
      machine.observe(io.github.fastformer.client.input.state.ClientInputStateMachine.State.IDLE);
      assertEquals(io.github.fastformer.client.input.state.ClientInputStateMachine.State.IDLE, machine.state());
   }
}
