package io.github.fastformer.fastplace.history;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoryStoreOrderingTest {
   @TempDir Path root;

   @Test
   void cancelledCallerCannotReleaseQueuedPayloadBeforeWriteFinishes() {
      ArrayDeque<Runnable> jobs = new ArrayDeque<>();
      HistoryStore store = new HistoryStore(root, jobs::addLast, 1, 80, 100);
      UUID owner = UUID.randomUUID();
      var first = store.save(owner, new byte[80]);
      first.cancel(false);
      assertTrue(store.save(owner, new byte[1]).isCompletedExceptionally());
      while (!jobs.isEmpty()) jobs.removeFirst().run();
      var retry = store.save(owner, new byte[]{7});
      while (!jobs.isEmpty()) jobs.removeFirst().run();
      retry.join();
      var read = store.load(owner);
      while (!jobs.isEmpty()) jobs.removeFirst().run();
      assertArrayEquals(new byte[]{7}, read.join().orElseThrow());
   }

   @Test
   void saveAndLoadKeepSubmissionOrderEvenWithReverseExecutor() {
      ArrayDeque<Runnable> jobs = new ArrayDeque<>();
      HistoryStore store = new HistoryStore(root, jobs::addLast, 1, 100, 1000);
      UUID owner = UUID.randomUUID();
      var first = store.save(owner, new byte[]{1});
      var second = store.save(owner, new byte[]{2});
      var read = store.load(owner);
      assertEquals(1, jobs.size());
      while (!jobs.isEmpty()) jobs.removeLast().run();
      first.join();
      second.join();
      assertArrayEquals(new byte[]{2}, read.join().orElseThrow());
   }
}
