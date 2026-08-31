package io.github.fastformer.network.transfer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChunkedPayloadTransferTest {
   @Test
   void reassemblesChunksInIndexOrderAndIgnoresDuplicates() throws IOException {
      ChunkedPayloadTransfer transfer = new ChunkedPayloadTransfer(UUID.randomUUID(), 3);

      assertNull(transfer.accept(2, new byte[] {3}));
      assertNull(transfer.accept(0, new byte[] {1}));
      assertNull(transfer.accept(0, new byte[] {99}));

      assertArrayEquals(new byte[] {1, 2, 3}, transfer.accept(1, new byte[] {2}));
   }

   @Test
   void rejectsInvalidIndexesAndOversizedTransfer() throws IOException {
      ChunkedPayloadTransfer transfer = new ChunkedPayloadTransfer(UUID.randomUUID(), 1);

      assertThrows(IOException.class, () -> transfer.accept(1, new byte[] {1}));
      assertThrows(IOException.class, () -> transfer.accept(0, new byte[0]));
      assertThrows(IllegalArgumentException.class, () ->
         new ChunkedPayloadTransfer(UUID.randomUUID(), ChunkedPayloadTransfer.MAX_CHUNKS + 1)
      );
   }

   @Test
   void reportsExpirationAgainstTheSuppliedClock() {
      ChunkedPayloadTransfer transfer = new ChunkedPayloadTransfer(UUID.randomUUID(), 1);

      assertThrows(IllegalArgumentException.class, () -> transfer.expired(0L, -1L));
      assertTrue(transfer.expired(Long.MAX_VALUE, 1L));
   }
}
