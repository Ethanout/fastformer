package io.github.fastformer.client.operation.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ClientOperationEventStackTest {
   @Test
   void chainedEventsUndoInReverseOrder() {
      ClientOperationEventStack stack = new ClientOperationEventStack();
      AtomicInteger value = new AtomicInteger();
      stack.push(() -> value.addAndGet(-1));
      stack.push(() -> value.addAndGet(-10));
      value.set(11);
      assertTrue(stack.undo());
      assertEquals(1, value.get());
      assertTrue(stack.undo());
      assertEquals(0, value.get());
   }
}
