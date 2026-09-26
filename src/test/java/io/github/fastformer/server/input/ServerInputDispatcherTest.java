package io.github.fastformer.server.input;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.network.payload.geometry.GeometryPointPayload;
import io.github.fastformer.network.payload.operation.OperationCallbackScope;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ServerInputDispatcherTest {
   private static final OperationCallbackScope SCOPE = new OperationCallbackScope(
      UUID.randomUUID(), ResourceLocation.withDefaultNamespace("overworld"), UUID.randomUUID()
   );

   @Test
   void raycastRangeUsesActualDistanceAndRejectsInvalidRanges() {
      Vec3 origin = Vec3.ZERO;
      assertTrue(ServerInputDispatcher.isWithinRaycastRange(origin, new Vec3(3.0, 0.0, 0.0), 3.0));
      assertFalse(ServerInputDispatcher.isWithinRaycastRange(origin, new Vec3(3.01, 0.0, 0.0), 3.0));
      assertFalse(ServerInputDispatcher.isWithinRaycastRange(origin, new Vec3(1.0, 0.0, 0.0), -1.0));
      assertFalse(ServerInputDispatcher.isWithinRaycastRange(origin, new Vec3(1.0, 0.0, 0.0), Double.NaN));
   }

   @Test
   void geometryPointAcceptsOnlyTheCurrentScopeAndNearbyCapturedEye() {
      GeometryPointPayload payload = new GeometryPointPayload(1L, 7L, SCOPE, new Vec3(3.0, 64.0, 3.0), new Vec3(0.0, 0.0, 1.0));

      assertTrue(ServerInputDispatcher.acceptsGeometryPointSnapshot(payload, SCOPE, new Vec3(3.0, 64.0, 4.0), 4.5));
      assertFalse(ServerInputDispatcher.acceptsGeometryPointSnapshot(payload,
         new OperationCallbackScope(SCOPE.playerId(), SCOPE.dimension(), UUID.randomUUID()), new Vec3(3.0, 64.0, 4.0), 4.5));
      assertFalse(ServerInputDispatcher.acceptsGeometryPointSnapshot(payload, SCOPE, new Vec3(3.0, 64.0, 9.0), 4.5));
   }

   @Test
   void geometryConfirmationKeepsAirConfirmAndRejectsNearbyCapturedBlock() {
      Vec3 eye = new Vec3(0.5, 64.5, 0.5);
      BlockHitResult air = BlockHitResult.miss(eye.add(0.0, 0.0, 12.0), Direction.NORTH, new BlockPos(0, 64, 12));
      BlockHitResult nearbyBlock = new BlockHitResult(eye.add(0.0, 0.0, 2.0), Direction.NORTH, new BlockPos(0, 64, 2), false);

      assertTrue(ServerInputDispatcher.capturedGeometryConfirmAllowed(air, eye, 4.5));
      assertFalse(ServerInputDispatcher.capturedGeometryConfirmAllowed(nearbyBlock, eye, 4.5));
   }

   @Test
   void geometryRequestIdsRejectReplays() {
      UUID owner = UUID.randomUUID();

      try {
         assertTrue(ServerInputDispatcher.acceptGeometryAction(owner, 1L));
         assertFalse(ServerInputDispatcher.acceptGeometryAction(owner, 1L));
         assertFalse(ServerInputDispatcher.acceptGeometryAction(owner, 0L));
         assertTrue(ServerInputDispatcher.acceptGeometryAction(owner, 2L));
      } finally {
         ServerInputDispatcher.clearPlacementActions(owner);
      }
   }

   @Test
   void operationPointCommandsRejectOlderIdsWithoutRewindingTheSequence() {
      UUID owner = UUID.randomUUID();

      try {
         assertTrue(ServerInputDispatcher.acceptOperationCommand(owner, 10L));
         assertFalse(ServerInputDispatcher.acceptOperationCommand(owner, 9L));
         assertFalse(ServerInputDispatcher.acceptOperationCommand(owner, 10L));
         assertTrue(ServerInputDispatcher.acceptOperationCommand(owner, 11L));
      } finally {
         ServerInputDispatcher.clearPlacementActions(owner);
      }
   }
}
