package io.github.fastformer.client.operation.input;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.client.session.draft.OperationDraftIdentity;
import io.github.fastformer.common.selection.OperationSelectionMode;
import io.github.fastformer.protocol.c2s.operation.OperationPointPayload;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class RemoteSelectionPointRequestTest {
   @Test void newerRevisionAndDifferentCallbackScopeRejectTheSameSelectionIdentity() {
      UUID owner = UUID.randomUUID();
      var scope = new io.github.fastformer.protocol.callback.OperationCallbackScope(owner,
         net.minecraft.resources.ResourceLocation.withDefaultNamespace("overworld"), UUID.randomUUID());
      var request = new RemoteSelectionPointRequest(owner, OperationSelectionMode.CUBOID, identity(BlockPos.ZERO),
         OperationPointPayload.Role.SECOND, 12L, scope);
      assertTrue(request.matches(owner, OperationSelectionMode.CUBOID, identity(BlockPos.ZERO), 12L, scope));
      assertFalse(request.matches(owner, OperationSelectionMode.CUBOID, identity(BlockPos.ZERO), 13L, scope));
      assertFalse(request.matches(owner, OperationSelectionMode.CUBOID, identity(BlockPos.ZERO), 12L,
         io.github.fastformer.protocol.callback.OperationCallbackScope.unscoped()));
   }
   @Test
   void changedOrRemovedServerSelectionRejectsQueuedPoint() {
      UUID owner = UUID.randomUUID();
      var original = identity(BlockPos.ZERO);
      var request = new RemoteSelectionPointRequest(owner, OperationSelectionMode.CUBOID, original,
         OperationPointPayload.Role.SECOND, 0L, io.github.fastformer.protocol.callback.OperationCallbackScope.unscoped());
      assertTrue(request.matches(owner, OperationSelectionMode.CUBOID, identity(BlockPos.ZERO), 0L, io.github.fastformer.protocol.callback.OperationCallbackScope.unscoped()));
      assertFalse(request.matches(owner, OperationSelectionMode.CUBOID, identity(new BlockPos(1, 0, 0)), 0L, io.github.fastformer.protocol.callback.OperationCallbackScope.unscoped()));
      assertFalse(request.matches(owner, OperationSelectionMode.CUBOID, null, 0L, io.github.fastformer.protocol.callback.OperationCallbackScope.unscoped()));
      var initial = new RemoteSelectionPointRequest(owner, OperationSelectionMode.CUBOID, null,
         OperationPointPayload.Role.FIRST, 0L, io.github.fastformer.protocol.callback.OperationCallbackScope.unscoped());
      assertFalse(initial.matches(owner, OperationSelectionMode.CUBOID, original, 0L, io.github.fastformer.protocol.callback.OperationCallbackScope.unscoped()));
   }

   private static OperationDraftIdentity identity(BlockPos point) {
      return new OperationDraftIdentity(OperationSelectionMode.CUBOID,
         List.of(point), 0, BlockPos.ZERO, BlockPos.ZERO, 0, point, point);
   }

   @Test
   void modeChangeRejectsAnOtherwiseMatchingRequest() {
      UUID owner = UUID.randomUUID();
      var request = new RemoteSelectionPointRequest(owner, OperationSelectionMode.CUBOID, null,
         OperationPointPayload.Role.FIRST, 0L, io.github.fastformer.protocol.callback.OperationCallbackScope.unscoped());
      assertTrue(request.matches(owner, OperationSelectionMode.CUBOID, null, 0L, io.github.fastformer.protocol.callback.OperationCallbackScope.unscoped()));
      assertFalse(request.matches(owner, OperationSelectionMode.PRISM, null, 0L, io.github.fastformer.protocol.callback.OperationCallbackScope.unscoped()));
      assertFalse(request.matches(UUID.randomUUID(), OperationSelectionMode.CUBOID, null, 0L, io.github.fastformer.protocol.callback.OperationCallbackScope.unscoped()));
   }
}
