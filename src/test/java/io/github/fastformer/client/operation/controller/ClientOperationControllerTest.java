package io.github.fastformer.client.operation.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
