package io.github.fastformer.client.operation.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.fastformer.fastplace.OperationMode;
import io.github.fastformer.fastplace.OperationSelectionMode;
import io.github.fastformer.fastplace.OperationStageMode;
import io.github.fastformer.network.payload.operation.OperationPreviewPayload;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class ClientOperationControllerTest {
   @Test
   void inactiveSnapshotClearsWorkspaceRetainedAcrossReconnect() {
      assertTrue(ClientOperationController.shouldClearWorkspaceAfterSnapshot(false, true, false));
   }

   @Test
   void inactiveSnapshotPreservesWorkspaceWhileSubmissionIsPending() {
      assertFalse(ClientOperationController.shouldClearWorkspaceAfterSnapshot(false, true, true));
   }

   @Test
   void inactiveSnapshotClearsWorkspaceAfterActiveServerOperationEnds() {
      assertTrue(ClientOperationController.shouldClearWorkspaceAfterSnapshot(true, false, false));
   }

   @Test
   void ordinaryInactiveSnapshotDoesNotClearLocalWorkspace() {
      assertFalse(ClientOperationController.shouldClearWorkspaceAfterSnapshot(false, false, false));
   }

   @Test
   void reconnectActiveSnapshotDoesNotHydrateClientWorkspace() {
      assertTrue(ClientOperationController.shouldSuppressServerPreviewHydration(true, true));
      assertFalse(ClientOperationController.shouldSuppressServerPreviewHydration(false, true));
      assertFalse(ClientOperationController.shouldSuppressServerPreviewHydration(true, false));
   }

   @Test
   void reconnectSnapshotWaitsForConfirmationBeforeHydratingWorkspace() {
      OperationPreviewPayload payload = reconnectPayload(17L);
      ClientOperationController.onDisconnected();

      assertTrue(ClientOperationController.synchronize(payload));
      assertTrue(ClientOperationController.reconnectRestorePending());
      assertTrue(ClientOperationController.workspace().isEmpty());

      assertTrue(ClientOperationController.confirmReconnectRestore());
      assertFalse(ClientOperationController.reconnectRestorePending());
      assertFalse(ClientOperationController.workspace().isEmpty());

      ClientOperationController.onDisconnected();
   }

   @Test
   void dismissingReconnectSnapshotPreventsLaterRestore() {
      ClientOperationController.onDisconnected();

      assertTrue(ClientOperationController.synchronize(reconnectPayload(23L)));
      assertTrue(ClientOperationController.reconnectRestorePending());
      ClientOperationController.dismissReconnectRestore();

      assertFalse(ClientOperationController.reconnectRestorePending());
      assertFalse(ClientOperationController.confirmReconnectRestore());
      assertTrue(ClientOperationController.workspace().isEmpty());

      ClientOperationController.onDisconnected();
   }

   private static OperationPreviewPayload reconnectPayload(long revision) {
      return OperationPreviewPayload.active(
         revision,
         true,
         true,
         List.of(new BlockPos(1, 64, 1), new BlockPos(2, 64, 2)),
         new BlockPos(1, 64, 1),
         new BlockPos(2, 64, 2),
         BlockPos.ZERO,
         BlockPos.ZERO,
         OperationSelectionMode.CUBOID,
         0,
         -1,
         0,
         OperationMode.MOVE,
         OperationStageMode.TRANSFORM,
         BlockPos.ZERO,
         BlockPos.ZERO,
         BlockPos.ZERO,
         Vec3.ZERO,
         false,
         false,
         false
      );
   }
}
