package io.github.fastformer.server.history;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class HistoryWriteQueueTest {
   private final UUID owner = UUID.randomUUID();

   @Test
   void fullQueueCannotConsumeAnotherRepositorysBudget() {
      ArrayDeque<Runnable> firstIo = new ArrayDeque<>();
      HistoryWriteQueue first = new HistoryWriteQueue(firstIo::addLast, 1, 8);
      HistoryWriteQueue second = new HistoryWriteQueue(Runnable::run, 1, 8);
      CompletableFuture<String> pending = first.submit(owner, 8, () -> CompletableFuture.completedFuture("first"));

      assertThrows(CompletionException.class, () -> first.submit(UUID.randomUUID(), 1,
         () -> CompletableFuture.completedFuture("overflow")).join());
      assertEquals("second", second.submit(owner, 8, () -> CompletableFuture.completedFuture("second")).join());
      firstIo.removeFirst().run();
      assertEquals("first", pending.join());
      assertTrue(first.pendingWrites().isDone());
   }

   @Test
   void callerCancellationRetainsBudgetAndDoesNotBreakOwnerOrdering() {
      ArrayDeque<Runnable> io = new ArrayDeque<>();
      HistoryWriteQueue queue = new HistoryWriteQueue(io::addLast, 2, 8);
      CompletableFuture<Void> actualWrite = new CompletableFuture<>();
      List<String> order = new ArrayList<>();
      CompletableFuture<Void> caller = queue.submit(owner, 4, () -> {
         order.add("first");
         return actualWrite;
      });
      assertTrue(caller.cancel(false));
      CompletableFuture<Void> second = queue.submit(owner, 4, () -> {
         order.add("second");
         return CompletableFuture.completedFuture(null);
      });
      assertThrows(CompletionException.class, () -> queue.submit(UUID.randomUUID(), 0,
         () -> CompletableFuture.completedFuture(null)).join());
      io.removeFirst().run();
      assertEquals(List.of("first"), order);
      assertTrue(io.isEmpty());
      assertFalse(queue.pendingWrites().isDone());
      actualWrite.complete(null);
      io.removeFirst().run();
      second.join();
      assertEquals(List.of("first", "second"), order);
      assertTrue(queue.pendingWrites().isDone());
   }

   @Test
   void failedWriteReleasesItsBudgetAndAllowsTheNextOwnerWrite() {
      HistoryWriteQueue queue = new HistoryWriteQueue(Runnable::run, 1, 4);
      assertThrows(CompletionException.class, () -> queue.submit(owner, 4,
         () -> CompletableFuture.failedFuture(new IllegalStateException("disk unavailable"))).join());
      assertEquals(7, queue.submit(owner, 4, () -> CompletableFuture.completedFuture(7)).join());
   }

   @Test
   void closeRejectsNewWorkWhileAcceptedWritesStillComplete() {
      ArrayDeque<Runnable> io = new ArrayDeque<>();
      HistoryWriteQueue queue = new HistoryWriteQueue(io::addLast, 2, 8);
      CompletableFuture<Void> first = queue.submit(owner, 4, () -> CompletableFuture.completedFuture(null));
      CompletableFuture<Void> second = queue.submit(owner, 4, () -> CompletableFuture.completedFuture(null));
      queue.close();
      queue.close();
      CompletableFuture<Void> barrier = queue.pendingWrites();
      assertFalse(barrier.isDone());
      assertThrows(CompletionException.class, () -> queue.submit(UUID.randomUUID(), 0,
         () -> { throw new AssertionError("closed queue ran new work"); }).join());
      while (!io.isEmpty()) io.removeFirst().run();
      first.join();
      second.join();
      barrier.join();
   }

   @Test
   void executorRejectionCannotLeakAdmission() {
      AtomicBoolean rejecting = new AtomicBoolean(true);
      HistoryWriteQueue queue = new HistoryWriteQueue(command -> {
         if (rejecting.get()) throw new RejectedExecutionException("stopped");
         command.run();
      }, 1, 4);
      assertThrows(CompletionException.class, () -> queue.submit(owner, 4,
         () -> CompletableFuture.completedFuture(1)).join());
      rejecting.set(false);
      assertEquals(2, queue.submit(owner, 4, () -> CompletableFuture.completedFuture(2)).join());
   }

   @Test
   void synchronousReentryWaitsForTheReservedOwnerPosition() {
      HistoryWriteQueue queue = new HistoryWriteQueue(Runnable::run, 2, 8);
      CompletableFuture<Void> actualWrite = new CompletableFuture<>();
      List<String> order = new ArrayList<>();
      CompletableFuture<Void> first = queue.submit(owner, 4, () -> {
         order.add("first");
         queue.submit(owner, 4, () -> {
            order.add("second");
            return CompletableFuture.completedFuture(null);
         });
         return actualWrite;
      });
      assertEquals(List.of("first"), order);
      actualWrite.complete(null);
      first.join();
      assertEquals(List.of("first", "second"), order);
      assertTrue(queue.pendingWrites().isDone());
   }
}
