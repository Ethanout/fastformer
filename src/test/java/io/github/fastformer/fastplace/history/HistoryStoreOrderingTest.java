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
