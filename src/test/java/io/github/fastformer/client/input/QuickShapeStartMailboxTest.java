package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.fastplace.quickshape.RaycastPlacement;
import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import io.github.fastformer.network.payload.placement.StartPlacementPayload;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class QuickShapeStartMailboxTest {
   private StartPlacementPayload.Target target() {
      var scope = new OperationCallbackScope(UUID.randomUUID(), ResourceLocation.withDefaultNamespace("overworld"), UUID.randomUUID());
      return new StartPlacementPayload.Target(1, scope, RaycastPlacement.SURFACE,
         new BlockHitResult(new Vec3(5, 1, 0), Direction.WEST, new BlockPos(5, 1, 0), false),
         new Vec3(0, 1, 0), new Vec3(1, 0, 0));
   }

   private void drain(ClientInputSession input, Consumer<KeyboardInputSnapshot> keys,
      Consumer<StartPlacementPayload.Target> starts) {
      input.drainPhysicalEvents(() -> true, keys, e -> fail(), e -> fail(), e -> fail(),
         e -> fail(), e -> fail(), e -> fail(), starts);
   }

   @Test
   void cancelBeforeDispatchDiscardsStartAndReleasesPendingOwnership() {
      var input = new ClientInputSession();
      input.postKeyboard(new KeyboardInputSnapshot(81, 0, 1, 0, 50, false, false));
      input.postStartPlacement(target());
      assertTrue(input.canCancelPendingSessionStart());
      assertTrue(input.ownsQuickShapeStart());
      drain(input, e -> assertTrue(input.cancel()), e -> fail("Cancelled start was dispatched"));
      assertFalse(input.canCancelPendingSessionStart());
      assertFalse(input.ownsQuickShapeStart());
      assertFalse(input.hasQueuedPhysicalInput());
   }

   @Test
   void cancelAfterDispatchCancelsTheWaitingRequest() {
      var input = new ClientInputSession();
      var captured = target();
      input.postStartPlacement(captured);
      input.postKeyboard(new KeyboardInputSnapshot(81, 0, 1, 0, 50, false, false));
      drain(input, e -> assertTrue(input.cancel()), event -> {
         assertSame(captured, event);
         assertFalse(input.canCancelPendingSessionStart());
         assertTrue(input.routing.startPlacement(1));
      });
      assertEquals(ClientInputStateMachine.State.CANCELLING, input.routing.state());
   }

   @Test
   void twoStartsInOneTickCannotOwnTwoRequests() {
      var input = new ClientInputSession();
      input.postStartPlacement(target());
      input.postStartPlacement(target());
      var results = new java.util.ArrayList<Boolean>();
      drain(input, e -> fail(), event -> results.add(input.routing.startPlacement(results.size() + 1L)));
      assertEquals(java.util.List.of(true, false), results);
      assertTrue(input.routing.awaitsPlacementRequest(1L));
      assertFalse(input.hasQueuedPhysicalInput());
   }

   @Test
   void environmentLossDropsStartAndLeftButtonOwnership() {
      var input = new ClientInputSession();
      input.captureQuickShapeButton(0);
      input.postStartPlacement(target());
      assertTrue(input.hasQuickShapeButtons());
      input.drainPhysicalEvents(() -> false, e -> fail(), e -> fail(), e -> fail(), e -> fail(),
         e -> fail(), e -> fail(), e -> fail(), e -> fail());
      assertFalse(input.hasQuickShapeButtons());
      assertFalse(input.canCancelPendingSessionStart());
      assertFalse(input.hasQueuedPhysicalInput());
   }
}
