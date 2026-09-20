package io.github.fastformer.client.operation.clipboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

class OperationClipboardStateTest {
   @Test
   void failedSaveLeavesThePreviousClipboardAvailable() throws Exception {
      OperationClipboardState state = new OperationClipboardState();
      OperationClipboard previous = clipboard();
      OperationClipboard replacement = clipboard();
      state.saveAndPublish(previous, value -> { });

      assertThrows(IOException.class, () -> state.saveAndPublish(replacement, value -> {
         throw new IOException("write failed");
      }));

      assertSame(previous, state.cached().orElseThrow());
   }

   @Test
   void failedInitialLoadDoesNotPreventARetry() throws Exception {
      OperationClipboardState state = new OperationClipboardState();
      OperationClipboard expected = clipboard();
      AtomicInteger attempts = new AtomicInteger();

      assertThrows(IOException.class, () -> state.load(() -> {
         attempts.incrementAndGet();
         throw new IOException("temporary read failure");
      }));
      assertSame(expected, state.load(() -> {
         attempts.incrementAndGet();
         return Optional.of(expected);
      }).orElseThrow());
      assertEquals(2, attempts.get());
      assertSame(expected, state.load(() -> {
         throw new AssertionError("A successful load must use the cached clipboard");
      }).orElseThrow());
   }

   @Test
   void successfulSavePublishesOnlyAfterTheWriterReturns() throws Exception {
      OperationClipboardState state = new OperationClipboardState();
      OperationClipboard previous = clipboard();
      OperationClipboard replacement = clipboard();
      state.saveAndPublish(previous, value -> { });
      List<OperationClipboard> written = new ArrayList<>();
      List<OperationClipboard> visibleDuringWrite = new ArrayList<>();

      state.saveAndPublish(replacement, value -> {
         written.add(value);
         visibleDuringWrite.add(state.cached().orElse(null));
      });

      assertEquals(List.of(replacement), written);
      assertEquals(List.of(previous), visibleDuringWrite);
      assertSame(replacement, state.cached().orElseThrow());
   }

   @Test
   void aRuntimeFailureFromTheWriterAlsoKeepsThePreviousClipboard() throws Exception {
      OperationClipboardState state = new OperationClipboardState();
      OperationClipboard previous = clipboard();
      state.saveAndPublish(previous, value -> { });

      assertThrows(IllegalArgumentException.class, () -> state.saveAndPublish(clipboard(), value -> {
         throw new IllegalArgumentException("clipboard exceeds the client block limit");
      }));

      assertSame(previous, state.cached().orElseThrow());
   }

   @Test
   void aMissingDurableClipboardStaysRetryable() throws Exception {
      OperationClipboardState state = new OperationClipboardState();
      OperationClipboard expected = clipboard();
      AtomicInteger attempts = new AtomicInteger();

      assertTrue(state.load(() -> {
         attempts.incrementAndGet();
         return Optional.empty();
      }).isEmpty());
      assertTrue(state.cached().isEmpty());

      assertSame(expected, state.load(() -> {
         attempts.incrementAndGet();
         return Optional.of(expected);
      }).orElseThrow());
      assertEquals(2, attempts.get());
   }

   @Test
   void aPublishedClipboardIsServedWithoutReadingDurableStorage() throws Exception {
      OperationClipboardState state = new OperationClipboardState();
      OperationClipboard published = clipboard();
      state.saveAndPublish(published, value -> { });

      assertSame(published, state.load(() -> {
         throw new AssertionError("A published clipboard must not read durable storage");
      }).orElseThrow());
   }

   private static OperationClipboard clipboard() {
      try {
         Field field = Unsafe.class.getDeclaredField("theUnsafe");
         field.setAccessible(true);
         return (OperationClipboard)((Unsafe)field.get(null)).allocateInstance(OperationClipboard.class);
      } catch (ReflectiveOperationException exception) {
         throw new AssertionError(exception);
      }
   }
}
