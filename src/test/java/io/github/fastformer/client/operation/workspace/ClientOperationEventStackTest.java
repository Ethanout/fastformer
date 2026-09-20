package io.github.fastformer.client.operation.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
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

   @Test
   void oneChainRunsItsChildrenInReverseOrderAsOneNode() {
      ClientOperationEventStack stack = new ClientOperationEventStack();
      List<Integer> order = new ArrayList<>();
      stack.pushChain(List.of(() -> order.add(1), () -> order.add(2)));

      assertEquals(1, stack.size());
      // One node plus its two child closures.
      assertEquals(3L, stack.retainedWeight());
      assertTrue(stack.undo());
      assertEquals(List.of(2, 1), order);
      assertEquals(0L, stack.retainedWeight());
   }

   @Test
   void theOldestNodesAreDiscardedWhenTheCountBudgetIsExceeded() {
      ClientOperationEventStack stack = new ClientOperationEventStack(3, 1_000_000);
      List<Integer> order = new ArrayList<>();
      for (int index = 1; index <= 5; index++) {
         int marker = index;
         stack.push(() -> order.add(marker));
      }

      assertEquals(3, stack.size());
      assertEquals(2L, stack.discardedRecords());
      for (int index = 5; index >= 3; index--) {
         assertTrue(stack.undo());
      }
      assertEquals(List.of(5, 4, 3), order);
      assertFalse(stack.undo());
   }

   @Test
   void theOldestNodesAreDiscardedWhenTheWeightBudgetIsExceeded() {
      ClientOperationEventStack stack = new ClientOperationEventStack(100, 10);
      for (int index = 0; index < 4; index++) {
         stack.push(() -> {}, ClientOperationEventStack.Retention.of(4));
      }

      assertEquals(2, stack.size());
      assertEquals(8L, stack.retainedWeight());
      assertEquals(2L, stack.discardedRecords());
   }

   @Test
   void theNewestNodeSurvivesEvenWhenItAloneExceedsTheWeightBudget() {
      ClientOperationEventStack stack = new ClientOperationEventStack(100, 5);
      Object holder = new Object();
      stack.push(() -> {}, ClientOperationEventStack.Retention.of(
         1, List.of(ClientOperationEventStack.SharedPayload.of(holder, 1_000))
      ));

      // Dropping this node would silently remove the newest action of the
      // player, so the stack keeps one node past the budget instead.
      assertEquals(1, stack.size());
      assertEquals(1_001L, stack.retainedWeight());
      assertTrue(stack.undo());
      assertEquals(0L, stack.retainedWeight());
   }

   @Test
   void aPayloadSharedBySeveralNodesIsChargedOnce() {
      ClientOperationEventStack stack = new ClientOperationEventStack(100, 10_000);
      Object holder = new Object();
      ClientOperationEventStack.Retention retention = ClientOperationEventStack.Retention.of(
         1, List.of(ClientOperationEventStack.SharedPayload.of(holder, 50))
      );
      stack.push(() -> {}, retention);
      assertEquals(51L, stack.retainedWeight());

      // The second node keeps the same holder alive, so it only adds its own
      // unit. This is the "count the shared snapshot, not every reference" rule.
      stack.push(() -> {}, retention);
      assertEquals(52L, stack.retainedWeight());
      assertEquals(1, stack.retainedPayloadCount());

      assertTrue(stack.undo());
      assertEquals(51L, stack.retainedWeight());
      assertEquals(1, stack.retainedPayloadCount());
      assertTrue(stack.undo());
      assertEquals(0L, stack.retainedWeight());
      assertEquals(0, stack.retainedPayloadCount());
   }

   @Test
   void clearingAndDiscardingReleaseTheRetainedPayload() {
      ClientOperationEventStack stack = new ClientOperationEventStack(1, 10_000);
      stack.push(() -> {}, payloadRetention(7));
      assertEquals(1, stack.retainedPayloadCount());

      // The count budget of one record drops the older node, which releases the
      // only reference to its payload holder.
      stack.push(() -> {}, payloadRetention(9));
      assertEquals(1, stack.size());
      assertEquals(1L, stack.discardedRecords());
      assertEquals(1, stack.retainedPayloadCount());

      stack.clear();
      assertEquals(0, stack.size());
      assertEquals(0L, stack.retainedWeight());
      assertEquals(0, stack.retainedPayloadCount());
   }

   @Test
   void aDiscardedPayloadBecomesUnreachable() {
      ClientOperationEventStack stack = new ClientOperationEventStack(2, 10_000);
      WeakReference<Object> payload = retainPayloadAndReturnWeakReference(stack);
      stack.push(() -> {});
      stack.push(() -> {});

      assertEquals(2, stack.size());
      assertEquals(1L, stack.discardedRecords());
      assertCollected(payload);
   }

   @Test
   void anUndonePayloadBecomesUnreachable() {
      ClientOperationEventStack stack = new ClientOperationEventStack();
      WeakReference<Object> payload = retainPayloadAndReturnWeakReference(stack);

      assertTrue(stack.undo());
      assertCollected(payload);
   }

   @Test
   void aNullInverseIsIgnoredAndAnUndeclaredRetentionCostsOneUnit() {
      ClientOperationEventStack stack = new ClientOperationEventStack();
      stack.push(null);
      assertEquals(0, stack.size());

      stack.push(() -> {}, null);
      assertEquals(1, stack.size());
      assertEquals(1L, stack.retainedWeight());
   }

   @Test
   void invalidBudgetsAreRejected() {
      assertThrows(IllegalArgumentException.class, () -> new ClientOperationEventStack(0, 10));
      assertThrows(IllegalArgumentException.class, () -> new ClientOperationEventStack(10, 0));
   }

   private static ClientOperationEventStack.Retention payloadRetention(int units) {
      return ClientOperationEventStack.Retention.of(
         1, List.of(ClientOperationEventStack.SharedPayload.of(new Object(), units))
      );
   }

   @Test
   void conflictingPayloadWeightsAreRejectedBeforeChangingTheStack() {
      ClientOperationEventStack stack = new ClientOperationEventStack();
      Object holder = new Object();
      stack.push(() -> {}, ClientOperationEventStack.Retention.of(
         1, List.of(new ClientOperationEventStack.SharedPayload(holder, 5))));
      assertThrows(IllegalArgumentException.class, () -> stack.push(() -> {},
         ClientOperationEventStack.Retention.of(1, List.of(new ClientOperationEventStack.SharedPayload(holder, 9)))));
      assertEquals(1, stack.size());
      assertEquals(6L, stack.retainedWeight());
      assertTrue(stack.undo());
      assertEquals(0L, stack.retainedWeight());
   }

   @Test
   void retentionIsCapturedWhenTheEventIsPushed() {
      ClientOperationEventStack stack = new ClientOperationEventStack();
      AtomicInteger units = new AtomicInteger(3);
      List<ClientOperationEventStack.SharedPayload> payloads = new ArrayList<>();
      payloads.add(new ClientOperationEventStack.SharedPayload(new Object(), 7));
      stack.push(() -> {}, new ClientOperationEventStack.Retention() {
         public int nodeUnits() { return units.get(); }
         public List<ClientOperationEventStack.SharedPayload> sharedPayloads() { return payloads; }
      });
      units.set(-100);
      payloads.clear();
      assertEquals(10L, stack.retainedWeight());
      assertTrue(stack.undo());
      assertEquals(0L, stack.retainedWeight());
      assertEquals(0, stack.retainedPayloadCount());
      assertThrows(IllegalArgumentException.class, () -> ClientOperationEventStack.Retention.of(-1));
   }

   private static WeakReference<Object> retainPayloadAndReturnWeakReference(ClientOperationEventStack stack) {
      Object holder = new Object();
      stack.push(() -> {}, ClientOperationEventStack.Retention.of(
         1, List.of(ClientOperationEventStack.SharedPayload.of(holder, 4))
      ));
      return new WeakReference<>(holder);
   }

   private static void assertCollected(WeakReference<Object> reference) {
      for (int attempt = 0; attempt < 50 && reference.get() != null; attempt++) {
         System.gc();
         try {
            Thread.sleep(5L);
         } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            break;
         }
      }
      assertNull(reference.get(), "A released history payload must become unreachable");
   }
}
