package io.github.fastformer.server.network.upload;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.fastformer.protocol.c2s.workspace.OperationWorkspaceApplyPayload;
import io.github.fastformer.protocol.c2s.workspace.ShapePlacementPayload;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IncomingPayloadTransfersTest {
   @Test
   void duplicateChunksDoNotConsumeOwnerOrGlobalBudget() throws IOException {
      var transfers = new IncomingPayloadTransfers(ignored -> {}, 8, 6);
      UUID owner = UUID.randomUUID(), other = UUID.randomUUID();
      UUID workspace = UUID.randomUUID(), shape = UUID.randomUUID(), otherTransfer = UUID.randomUUID();
      var chunk = new OperationWorkspaceApplyPayload(workspace, 0, 2, new byte[] {1, 2, 3});
      assertNull(transfers.accept(UploadKind.WORKSPACE, owner, chunk));
      assertNull(transfers.accept(UploadKind.SHAPE, owner, new ShapePlacementPayload(shape, 0, 2, new byte[] {4, 5, 6})));
      assertNull(transfers.accept(UploadKind.WORKSPACE, other, new OperationWorkspaceApplyPayload(otherTransfer, 0, 2, new byte[] {7, 8})));
      assertNull(transfers.accept(UploadKind.WORKSPACE, owner, chunk));
      transfers.forget(UploadKind.SHAPE, owner);
      assertArrayEquals(new byte[] {1, 2, 3, 9}, transfers.accept(UploadKind.WORKSPACE, owner,
         new OperationWorkspaceApplyPayload(workspace, 1, 2, new byte[] {9})));
   }

   @Test void ownerBudgetIncludesBothKindsAndGlobalBudgetIncludesAllOwners() throws IOException {
      var transfers = new IncomingPayloadTransfers(ignored -> {}, 8, 6);
      UUID first = UUID.randomUUID(), second = UUID.randomUUID();
      UUID workspace = UUID.randomUUID(), shape = UUID.randomUUID(), other = UUID.randomUUID();
      transfers.accept(UploadKind.WORKSPACE, first, new OperationWorkspaceApplyPayload(workspace, 0, 3, new byte[] {1, 2, 3}));
      transfers.accept(UploadKind.SHAPE, first, new ShapePlacementPayload(shape, 0, 3, new byte[] {4, 5, 6}));
      assertThrows(IOException.class, () -> transfers.accept(UploadKind.SHAPE, first, new ShapePlacementPayload(shape, 1, 3, new byte[] {7})));
      transfers.accept(UploadKind.WORKSPACE, second, new OperationWorkspaceApplyPayload(other, 0, 3, new byte[] {7, 8}));
      assertThrows(IOException.class, () -> transfers.accept(UploadKind.WORKSPACE, second, new OperationWorkspaceApplyPayload(other, 1, 3, new byte[] {9})));
      transfers.forget(first);
      assertNull(transfers.accept(UploadKind.WORKSPACE, second, new OperationWorkspaceApplyPayload(other, 1, 3, new byte[] {9})));
      assertArrayEquals(new byte[] {7, 8, 9, 10}, transfers.accept(UploadKind.WORKSPACE, second, new OperationWorkspaceApplyPayload(other, 2, 3, new byte[] {10})));
   }
   @Test void replacementEvictsAllPreviousIdentities() throws IOException {
      var evicted = new java.util.ArrayList<IncomingPayloadTransfers.ExpiredTransfer>();
      var transfers = new IncomingPayloadTransfers(evicted::add);
      UUID owner = UUID.randomUUID();
      for (int i = 0; i < 1000; i++) transfers.accept(UploadKind.WORKSPACE, owner,
         new OperationWorkspaceApplyPayload(UUID.randomUUID(), 0, 2, new byte[] {1}));
      org.junit.jupiter.api.Assertions.assertEquals(999, evicted.size());
      transfers.forget(owner);
      org.junit.jupiter.api.Assertions.assertEquals(1000, evicted.size());
      org.junit.jupiter.api.Assertions.assertEquals(1000, evicted.stream().map(IncomingPayloadTransfers.ExpiredTransfer::transferId).distinct().count());
   }
   @Test
   void staleWorkspaceFailurePreservesTheCurrentTransfer() throws IOException {
      IncomingPayloadTransfers transfers = new IncomingPayloadTransfers();
      UUID owner = UUID.randomUUID();
      UUID current = UUID.randomUUID();
      UUID stale = UUID.randomUUID();
      assertNull(transfers.accept(UploadKind.WORKSPACE, owner, new OperationWorkspaceApplyPayload(current, 0, 2, new byte[] {1})));
      assertThrows(IOException.class, () -> transfers.accept(UploadKind.WORKSPACE,
         owner, new OperationWorkspaceApplyPayload(stale, 1, 2, new byte[] {9})));
      transfers.forget(UploadKind.WORKSPACE, owner, stale);
      assertArrayEquals(new byte[] {1, 2}, transfers.accept(UploadKind.WORKSPACE,
         owner, new OperationWorkspaceApplyPayload(current, 1, 2, new byte[] {2})));
   }

   @Test
   void staleShapeFailurePreservesTheCurrentTransfer() throws IOException {
      IncomingPayloadTransfers transfers = new IncomingPayloadTransfers();
      UUID owner = UUID.randomUUID();
      UUID current = UUID.randomUUID();
      UUID stale = UUID.randomUUID();
      assertNull(transfers.accept(UploadKind.SHAPE, owner, new ShapePlacementPayload(current, 0, 2, new byte[] {1})));
      assertThrows(IOException.class, () -> transfers.accept(UploadKind.SHAPE,
         owner, new ShapePlacementPayload(stale, 1, 2, new byte[] {9})));
      transfers.forget(UploadKind.SHAPE, owner, stale);
      assertArrayEquals(new byte[] {1, 2}, transfers.accept(UploadKind.SHAPE,
         owner, new ShapePlacementPayload(current, 1, 2, new byte[] {2})));
   }

   @Test
   void matchingFailureRemovesOnlyItsTransferKind() throws IOException {
      IncomingPayloadTransfers transfers = new IncomingPayloadTransfers();
      UUID owner = UUID.randomUUID();
      UUID id = UUID.randomUUID();
      transfers.accept(UploadKind.WORKSPACE, owner, new OperationWorkspaceApplyPayload(id, 0, 2, new byte[] {1}));
      transfers.accept(UploadKind.SHAPE, owner, new ShapePlacementPayload(id, 0, 2, new byte[] {3}));
      transfers.forget(UploadKind.WORKSPACE, owner, id);
      assertThrows(IOException.class, () -> transfers.accept(UploadKind.WORKSPACE,
         owner, new OperationWorkspaceApplyPayload(id, 1, 2, new byte[] {2})));
      assertArrayEquals(new byte[] {3, 4}, transfers.accept(UploadKind.SHAPE,
         owner, new ShapePlacementPayload(id, 1, 2, new byte[] {4})));
   }

   @Test
   void keepsWorkspaceAndShapeTransfersIndependentForTheSamePlayer() throws IOException {
      IncomingPayloadTransfers transfers = new IncomingPayloadTransfers();
      UUID owner = UUID.randomUUID();
      UUID workspaceId = UUID.randomUUID();
      UUID shapeId = UUID.randomUUID();

      assertNull(transfers.accept(UploadKind.WORKSPACE,
         owner, new OperationWorkspaceApplyPayload(workspaceId, 0, 2, new byte[] {1})
      ));
      assertNull(transfers.accept(UploadKind.SHAPE,
         owner, new ShapePlacementPayload(shapeId, 0, 2, new byte[] {3})
      ));

      assertArrayEquals(
         new byte[] {1, 2},
         transfers.accept(UploadKind.WORKSPACE,
            owner, new OperationWorkspaceApplyPayload(workspaceId, 1, 2, new byte[] {2})
         )
      );
      assertArrayEquals(
         new byte[] {3, 4},
         transfers.accept(UploadKind.SHAPE, owner, new ShapePlacementPayload(shapeId, 1, 2, new byte[] {4}))
      );
   }

   @Test
   void rejectsAReplacementTransferThatDoesNotStartAtChunkZero() throws IOException {
      IncomingPayloadTransfers transfers = new IncomingPayloadTransfers();
      UUID owner = UUID.randomUUID();
      UUID firstId = UUID.randomUUID();

      assertNull(transfers.accept(UploadKind.WORKSPACE,
         owner, new OperationWorkspaceApplyPayload(firstId, 0, 2, new byte[] {1})
      ));

      assertThrows(IOException.class, () -> transfers.accept(UploadKind.WORKSPACE,
         owner, new OperationWorkspaceApplyPayload(UUID.randomUUID(), 1, 2, new byte[] {2})
      ));
   }

   @Test
   void purgesStalledTransfersWithoutAnotherChunk() throws IOException {
      IncomingPayloadTransfers transfers = new IncomingPayloadTransfers();
      UUID owner = UUID.randomUUID();
      UUID transferId = UUID.randomUUID();
      assertNull(transfers.accept(UploadKind.WORKSPACE,
         owner, new OperationWorkspaceApplyPayload(transferId, 0, 2, new byte[] {1})
      ));

      org.junit.jupiter.api.Assertions.assertEquals(
         java.util.List.of(new IncomingPayloadTransfers.ExpiredTransfer(owner, transferId, UploadKind.WORKSPACE)),
         transfers.purgeExpired(Long.MAX_VALUE)
      );
      org.junit.jupiter.api.Assertions.assertTrue(transfers.purgeExpired(Long.MAX_VALUE).isEmpty());

      assertThrows(IOException.class, () -> transfers.accept(UploadKind.WORKSPACE,
         owner, new OperationWorkspaceApplyPayload(transferId, 1, 2, new byte[] {2})
      ));
   }

   @Test
   void purgesStalledShapeTransfersWithAFailureEvent() throws IOException {
      IncomingPayloadTransfers transfers = new IncomingPayloadTransfers();
      UUID owner = UUID.randomUUID();
      UUID transferId = UUID.randomUUID();
      assertNull(transfers.accept(UploadKind.SHAPE,
         owner, new ShapePlacementPayload(transferId, 0, 2, new byte[] {1})
      ));

      org.junit.jupiter.api.Assertions.assertEquals(
         java.util.List.of(new IncomingPayloadTransfers.ExpiredTransfer(owner, transferId, UploadKind.SHAPE)),
         transfers.purgeExpired(Long.MAX_VALUE)
      );
   }
}
