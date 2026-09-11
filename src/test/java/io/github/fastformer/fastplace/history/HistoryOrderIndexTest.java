package io.github.fastformer.fastplace.history;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class HistoryOrderIndexTest {
   @Test
   void claimedEntryCountCannotAllocateBeyondPayload() {
      byte[] payload = ByteBuffer.allocate(4).putInt(Integer.MAX_VALUE).array();
      assertThrows(IOException.class, () -> HistoryOrderIndex.decode(payload, Integer.MAX_VALUE));
   }
}
