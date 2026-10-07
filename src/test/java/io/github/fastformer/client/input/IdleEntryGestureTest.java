package io.github.fastformer.client.input;

import io.github.fastformer.fastplace.interaction.InteractionShapeSnapshot;
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

   @Test void secondPressRequiresTheTimeWindowAndTheCapturedShape() {
      var gesture = new IdleEntryGesture();
      assertNull(gesture.secondPress(100, EYE, VIEW));
      gesture.remember(1, 100, FULL, EYE, VIEW, this, null);
      assertNotNull(gesture.secondPress(100 + IdleEntryGesture.DOUBLE_PRESS_NANOS, EYE, VIEW));
      assertNull(gesture.secondPress(101 + IdleEntryGesture.DOUBLE_PRESS_NANOS, EYE, VIEW));
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

   @Test void viewChangeRejectsAnotherWrenchClickEvenOnTheSameShape() {
      var gesture = new IdleEntryGesture();
      gesture.remember(1, 100, FULL, EYE, VIEW, this, null);
      var nearbyView = horizontalView(1.9);
      var changedView = horizontalView(2.1);
      assertNotNull(FULL.clip(EYE, changedView));
      assertNotNull(gesture.secondPress(200, EYE, nearbyView));
      assertNull(gesture.secondPress(200, EYE, changedView));
      gesture.update(1, FULL);
      assertNull(gesture.secondPress(200, EYE, changedView));
   }

   @Test void floatingDoesNotCountAsViewMovement() {
      var gesture = new IdleEntryGesture();
      gesture.remember(1, 100, FULL, EYE, VIEW, this, null);
      assertNotNull(gesture.secondPress(200, EYE.add(0.35, 0.1, 0.4), VIEW));
      assertNull(gesture.secondPress(200, EYE.add(2, 0, 0), VIEW));
   }

   @Test void hitDistanceIsRequiredEvenWithoutViewRotation() {
      var gesture = new IdleEntryGesture();
      var eye = new Vec3(0.1, 0.75, -2);
      gesture.remember(1, 100, FULL, eye, VIEW, this, null);
      assertNotNull(gesture.secondPress(200, eye.add(0.5, 0, 0), VIEW));
      var shifted = eye.add(0.501, 0, 0);
      assertNotNull(FULL.clip(shifted, VIEW));
      assertNull(gesture.secondPress(200, shifted, VIEW));
   }

   @Test void replacementShapeReprojectsTheFirstRayBeforeComparingHits() {
      var gesture = new IdleEntryGesture();
      gesture.remember(1, 100, FULL, EYE, VIEW, this, null);
      var placed = new InteractionShapeSnapshot(BlockPos.ZERO.north(), FULL.boxes());
      gesture.update(1, placed);
      assertEquals(placed.position(), gesture.secondPress(200, EYE, VIEW).getBlockPos());
   }

   @Test void viewComparisonHandlesTheYawWrapAndPitchChanges() {
      var gesture = new IdleEntryGesture();
      var eye = new Vec3(0.5, 0.75, 3);
      gesture.remember(1, 100, FULL, eye, horizontalView(179.5), this, null);
      assertNotNull(gesture.secondPress(200, eye, horizontalView(-179.5)));
      gesture.remember(2, 100, FULL, EYE, VIEW, this, null);
      var pitched = new Vec3(0, -Math.sin(Math.toRadians(3)), Math.cos(Math.toRadians(3)));
      assertNotNull(FULL.clip(EYE, pitched));
      assertNull(gesture.secondPress(200, EYE, pitched));
   }

   private static Vec3 horizontalView(double degrees) {
      double angle = Math.toRadians(degrees);
      return new Vec3(Math.sin(angle), 0, Math.cos(angle));
   }

   @Test void wrenchDoubleClickUsesTheChangedShapeAndKeepsItsRestoreIntent() {
      var slab = new InteractionShapeSnapshot(BlockPos.ZERO, List.of(new AABB(0, 0, 0, 1, 0.5, 1)));
      var gesture = new IdleEntryGesture();
      gesture.rememberTinker(3, 100, FULL, slab, EYE.subtract(0, 0.5, 0), VIEW, this, null);
      assertTrue(gesture.tinker());
      assertNull(gesture.secondPress(200, EYE, VIEW));
      assertNotNull(gesture.secondPress(200, EYE.subtract(0, 0.5, 0), VIEW));
      gesture.update(2, FULL);
      assertNull(gesture.secondPress(200, EYE, VIEW));
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

   @Test void movedSnapshotRemainsIndependentOfWorldAndHasNoDistanceHandoff() {
      var distant = new InteractionShapeSnapshot(new BlockPos(0, 0, 200), FULL.boxes());
      assertNotNull(distant.clip(EYE, VIEW));
      var gesture = new IdleEntryGesture();
      gesture.remember(2, 100, FULL, EYE, VIEW, this, null);
      gesture.update(2, distant);
      assertEquals(distant.position(), gesture.secondPress(200, EYE, VIEW).getBlockPos());
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
