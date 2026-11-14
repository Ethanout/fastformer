package io.github.fastformer.client.operation.preview;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.common.selection.OperationMode;
import io.github.fastformer.common.selection.OperationSelectionMode;
import io.github.fastformer.common.selection.OperationStageMode;
import io.github.fastformer.protocol.s2c.operation.OperationPreviewPayload;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class OperationPreviewStateTest {
   @Test
   void revisionsAndReconnectCandidatesBelongToTheirOwnState() {
      var first = new OperationPreviewState();
      var second = new OperationPreviewState();
      assertTrue(first.receive(active(80), false).accepted());
      assertFalse(first.receive(active(79), false).accepted());
      assertTrue(second.receive(active(2), false).accepted());
      first.leaveEnvironment();
      assertFalse(first.receive(active(1), false).accepted());
      assertTrue(first.restorePending());
      assertFalse(second.restorePending());
      assertEquals(2, second.live().operationRevision());
   }

   @Test
   void storedDraftRequiresConfirmationEvenAfterTheReconnectWindowHasEnded() {
      var state = new OperationPreviewState();
      var payload = active(3);
      assertFalse(state.receive(payload, true).accepted());
      assertTrue(state.restorePending());
      assertFalse(state.receive(payload, true).accepted());
      assertFalse(state.live().active());
      assertSame(payload, state.takePending());
      assertTrue(state.receive(payload, false).accepted());
      assertFalse(state.restorePending());
      assertSame(payload, state.live());
   }

   @Test
   void inactiveSnapshotAsksTheOwnerToDiscardButTimeoutDoesNot() {
      var state = new OperationPreviewState();
      assertTrue(state.receive(OperationPreviewPayload.inactive(1), true).discardStoredDraft());
      state.leaveEnvironment();
      var update = state.receive(OperationPreviewPayload.inactive(1), false);
      assertTrue(update.accepted());
      assertTrue(update.discardStoredDraft());
      boolean received = false;
      for (int i = 0; i < 50; i++) received |= state.tick();
      assertTrue(received);
      assertTrue(state.receive(active(2), false).accepted());

      state.leaveEnvironment();
      for (int i = 0; i < 50; i++) assertFalse(state.tick());
      assertTrue(state.receive(active(1), false).accepted());
   }

   @Test
   void newerLiveRevisionSupersedesAnUnconfirmedCandidate() {
      var state = new OperationPreviewState();
      state.leaveEnvironment();
      assertFalse(state.receive(active(1), false).accepted());
      assertTrue(state.receive(active(2), false).accepted());
      assertFalse(state.restorePending());
      assertNull(state.takePending());
      assertTrue(state.receive(OperationPreviewPayload.inactive(3), false).previousActive());
   }

   private static OperationPreviewPayload active(long revision) {
      return OperationPreviewPayload.active(revision, true, true,
         List.of(BlockPos.ZERO, new BlockPos(1, 1, 1)), BlockPos.ZERO, new BlockPos(1, 1, 1),
         BlockPos.ZERO, BlockPos.ZERO, OperationSelectionMode.CUBOID, 0, -1, 0,
         OperationMode.MOVE, OperationStageMode.TRANSFORM, BlockPos.ZERO, BlockPos.ZERO,
         BlockPos.ZERO, Vec3.ZERO, false, false, false);
   }
}
