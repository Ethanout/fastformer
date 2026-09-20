package io.github.fastformer.client.input;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.client.session.OperationDraftIdentity;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.network.payload.operation.OperationPointPayload;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class RemoteSelectionPointRequestTest {
   @Test
   void changedOrRemovedServerSelectionRejectsQueuedPoint() {
      UUID owner = UUID.randomUUID();
      var original = identity(BlockPos.ZERO);
      var request = new RemoteSelectionPointRequest(owner, OperationSelectionMode.CUBOID, original,
         OperationPointPayload.Role.SECOND);
      assertTrue(request.matches(owner, OperationSelectionMode.CUBOID, identity(BlockPos.ZERO)));
      assertFalse(request.matches(owner, OperationSelectionMode.CUBOID, identity(new BlockPos(1, 0, 0))));
      assertFalse(request.matches(owner, OperationSelectionMode.CUBOID, null));
      var initial = new RemoteSelectionPointRequest(owner, OperationSelectionMode.CUBOID, null,
         OperationPointPayload.Role.FIRST);
      assertFalse(initial.matches(owner, OperationSelectionMode.CUBOID, original));
   }

   private static OperationDraftIdentity identity(BlockPos point) {
      return new OperationDraftIdentity(OperationSelectionMode.CUBOID,
         List.of(point), 0, BlockPos.ZERO, BlockPos.ZERO, 0, point, point);
   }

   @Test
   void modeChangeRejectsAnOtherwiseMatchingRequest() {
      UUID owner = UUID.randomUUID();
      var request = new RemoteSelectionPointRequest(owner, OperationSelectionMode.CUBOID, null,
         OperationPointPayload.Role.FIRST);
      assertTrue(request.matches(owner, OperationSelectionMode.CUBOID, null));
      assertFalse(request.matches(owner, OperationSelectionMode.PRISM, null));
      assertFalse(request.matches(UUID.randomUUID(), OperationSelectionMode.CUBOID, null));
   }
}
