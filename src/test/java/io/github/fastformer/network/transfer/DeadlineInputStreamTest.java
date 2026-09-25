package io.github.fastformer.network.transfer;

import java.io.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeadlineInputStreamTest {
   @Test void expiredBudgetRejectsBeforeReadingInput() {
      var input = new ByteArrayInputStream(new byte[] {1});
      var stream = new DeadlineInputStream(input, -1);
      assertThrows(IOException.class, stream::read);
      assertEquals(1, input.available());
   }
   @Test void cancellationInterruptRejectsBeforeReadingInput() {
      var input = new ByteArrayInputStream(new byte[] {1});
      var stream = new DeadlineInputStream(input, 1_000_000_000L);
      Thread.currentThread().interrupt();
      try { assertThrows(IOException.class, stream::read); assertEquals(1, input.available()); }
      finally { Thread.interrupted(); }
   }
}
