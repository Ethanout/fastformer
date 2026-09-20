package io.github.fastformer.network.transfer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.fastformer.network.payload.operation.OperationWorkspaceApplyPayload;
import io.github.fastformer.network.payload.placement.ShapePlacementPayload;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IncomingPayloadTransfersTest {
   @Test
   void staleWorkspaceFailurePreservesTheCurrentTransfer() throws IOException {
      IncomingPayloadTransfers transfers = new IncomingPayloadTransfers();
      UUID owner = UUID.randomUUID();
      UUID current = UUID.randomUUID();
      UUID stale = UUID.randomUUID();
      assertNull(transfers.acceptWorkspace(owner, new OperationWorkspaceApplyPayload(current, 0, 2, new byte[] {1})));
      assertThrows(IOException.class, () -> transfers.acceptWorkspace(
         owner, new OperationWorkspaceApplyPayload(stale, 1, 2, new byte[] {9})));
      transfers.forgetWorkspace(owner, stale);
      assertArrayEquals(new byte[] {1, 2}, transfers.acceptWorkspace(
         owner, new OperationWorkspaceApplyPayload(current, 1, 2, new byte[] {2})));
   }

   @Test
   void staleShapeFailurePreservesTheCurrentTransfer() throws IOException {
      IncomingPayloadTransfers transfers = new IncomingPayloadTransfers();
      UUID owner = UUID.randomUUID();
      UUID current = UUID.randomUUID();
      UUID stale = UUID.randomUUID();
      assertNull(transfers.acceptShape(owner, new ShapePlacementPayload(current, 0, 2, new byte[] {1})));
      assertThrows(IOException.class, () -> transfers.acceptShape(
         owner, new ShapePlacementPayload(stale, 1, 2, new byte[] {9})));
      transfers.forgetShape(owner, stale);
      assertArrayEquals(new byte[] {1, 2}, transfers.acceptShape(
         owner, new ShapePlacementPayload(current, 1, 2, new byte[] {2})));
   }

   @Test
   void matchingFailureRemovesOnlyItsTransferKind() throws IOException {
      IncomingPayloadTransfers transfers = new IncomingPayloadTransfers();
      UUID owner = UUID.randomUUID();
      UUID id = UUID.randomUUID();
      transfers.acceptWorkspace(owner, new OperationWorkspaceApplyPayload(id, 0, 2, new byte[] {1}));
      transfers.acceptShape(owner, new ShapePlacementPayload(id, 0, 2, new byte[] {3}));
      transfers.forgetWorkspace(owner, id);
      assertThrows(IOException.class, () -> transfers.acceptWorkspace(
         owner, new OperationWorkspaceApplyPayload(id, 1, 2, new byte[] {2})));
      assertArrayEquals(new byte[] {3, 4}, transfers.acceptShape(
         owner, new ShapePlacementPayload(id, 1, 2, new byte[] {4})));
   }

   @Test
   void keepsWorkspaceAndShapeTransfersIndependentForTheSamePlayer() throws IOException {
      IncomingPayloadTransfers transfers = new IncomingPayloadTransfers();
      UUID owner = UUID.randomUUID();
      UUID workspaceId = UUID.randomUUID();
      UUID shapeId = UUID.randomUUID();

      assertNull(transfers.acceptWorkspace(
         owner, new OperationWorkspaceApplyPayload(workspaceId, 0, 2, new byte[] {1})
      ));
      assertNull(transfers.acceptShape(
         owner, new ShapePlacementPayload(shapeId, 0, 2, new byte[] {3})
      ));

      assertArrayEquals(
         new byte[] {1, 2},
         transfers.acceptWorkspace(
            owner, new OperationWorkspaceApplyPayload(workspaceId, 1, 2, new byte[] {2})
         )
      );
      assertArrayEquals(
         new byte[] {3, 4},
         transfers.acceptShape(owner, new ShapePlacementPayload(shapeId, 1, 2, new byte[] {4}))
      );
   }

   @Test
   void rejectsAReplacementTransferThatDoesNotStartAtChunkZero() throws IOException {
      IncomingPayloadTransfers transfers = new IncomingPayloadTransfers();
      UUID owner = UUID.randomUUID();
      UUID firstId = UUID.randomUUID();

      assertNull(transfers.acceptWorkspace(
         owner, new OperationWorkspaceApplyPayload(firstId, 0, 2, new byte[] {1})
      ));

      assertThrows(IOException.class, () -> transfers.acceptWorkspace(
         owner, new OperationWorkspaceApplyPayload(UUID.randomUUID(), 1, 2, new byte[] {2})
      ));
   }

   @Test
   void purgesStalledTransfersWithoutAnotherChunk() throws IOException {
      IncomingPayloadTransfers transfers = new IncomingPayloadTransfers();
      UUID owner = UUID.randomUUID();
      UUID transferId = UUID.randomUUID();
      assertNull(transfers.acceptWorkspace(
         owner, new OperationWorkspaceApplyPayload(transferId, 0, 2, new byte[] {1})
      ));

      org.junit.jupiter.api.Assertions.assertEquals(
         java.util.List.of(new IncomingPayloadTransfers.ExpiredTransfer(owner, transferId)),
         transfers.purgeExpired(Long.MAX_VALUE)
      );
      org.junit.jupiter.api.Assertions.assertTrue(transfers.purgeExpired(Long.MAX_VALUE).isEmpty());

      assertThrows(IOException.class, () -> transfers.acceptWorkspace(
         owner, new OperationWorkspaceApplyPayload(transferId, 1, 2, new byte[] {2})
      ));
   }

   @Test
   void purgesStalledShapeTransfersWithAFailureEvent() throws IOException {
      IncomingPayloadTransfers transfers = new IncomingPayloadTransfers();
      UUID owner = UUID.randomUUID();
      UUID transferId = UUID.randomUUID();
      assertNull(transfers.acceptShape(
         owner, new ShapePlacementPayload(transferId, 0, 2, new byte[] {1})
      ));

      org.junit.jupiter.api.Assertions.assertEquals(
         java.util.List.of(new IncomingPayloadTransfers.ExpiredTransfer(owner, transferId, true)),
         transfers.purgeExpired(Long.MAX_VALUE)
      );
   }
}
