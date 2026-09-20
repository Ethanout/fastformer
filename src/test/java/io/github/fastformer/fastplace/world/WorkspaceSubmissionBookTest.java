package io.github.fastformer.fastplace.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.fastformer.network.payload.operation.OperationSubmissionOutcome;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * Tests the eviction rules of one server's submission book.
 *
 * <p>These tests exercise the class directly, because a live server is not available in
 * a unit test. The rules under test are the reason the book exists: a running transfer
 * must never leave, and the book must stay bounded over all owners.</p>
 */
class WorkspaceSubmissionBookTest {
   private static final ResourceLocation OVERWORLD =
      ResourceLocation.fromNamespaceAndPath("minecraft", "overworld");
   private static final ResourceLocation NETHER =
      ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether");
   private static final long NOW = 1_000_000L;

   private static UUID owner(int seed) {
      return new UUID(seed, seed);
   }

   private static UUID transfer(int seed) {
      return new UUID(0xABCDEFL, seed);
   }

   @Test
   void recordedOutcomeIsReadableByOwnerAndDimension() {
      WorkspaceSubmissionBook book = new WorkspaceSubmissionBook();
      book.record(owner(1), OVERWORLD, transfer(1), OperationSubmissionOutcome.IN_PROGRESS, NOW);

      assertNotNull(book.find(owner(1), OVERWORLD, transfer(1)));
      assertEquals(
         OperationSubmissionOutcome.IN_PROGRESS,
         book.find(owner(1), OVERWORLD, transfer(1)).outcome()
      );
   }

   @Test
   void anotherDimensionDoesNotAnswer() {
      WorkspaceSubmissionBook book = new WorkspaceSubmissionBook();
      book.record(owner(1), OVERWORLD, transfer(1), OperationSubmissionOutcome.APPLIED, NOW);

      // The same transfer id in another dimension is a different submission. A match on
      // the transfer id alone would settle a submission of an environment that never
      // received it.
      assertNull(book.find(owner(1), NETHER, transfer(1)));
   }

   @Test
   void anotherOwnerDoesNotAnswer() {
      WorkspaceSubmissionBook book = new WorkspaceSubmissionBook();
      book.record(owner(1), OVERWORLD, transfer(1), OperationSubmissionOutcome.APPLIED, NOW);

      assertNull(book.find(owner(2), OVERWORLD, transfer(1)));
   }

   @Test
   void aRepeatedTransferUpdatesInsteadOfAdding() {
      WorkspaceSubmissionBook book = new WorkspaceSubmissionBook();
      book.record(owner(1), OVERWORLD, transfer(1), OperationSubmissionOutcome.IN_PROGRESS, NOW);
      book.record(owner(1), OVERWORLD, transfer(1), OperationSubmissionOutcome.APPLIED, NOW);

      assertEquals(1, book.sizeFor(owner(1)));
      assertEquals(OperationSubmissionOutcome.APPLIED, book.find(owner(1), OVERWORLD, transfer(1)).outcome());
   }

   @Test
   void runningTransferSurvivesTheOwnerLimit() {
      WorkspaceSubmissionBook book = new WorkspaceSubmissionBook();
      // One running transfer, then more finished entries than the owner limit allows.
      book.record(owner(1), OVERWORLD, transfer(1), OperationSubmissionOutcome.IN_PROGRESS, NOW);
      for (int index = 0; index < WorkspaceSubmissionBook.MAX_ENTRIES_PER_OWNER + 20; index++) {
         book.record(owner(1), OVERWORLD, transfer(100 + index), OperationSubmissionOutcome.APPLIED, NOW + index);
      }

      // The running transfer must stay. An eviction would answer UNKNOWN for work that
      // the server still performs, and the client could then send it again.
      assertNotNull(book.find(owner(1), OVERWORLD, transfer(1)));
      assertEquals(
         OperationSubmissionOutcome.IN_PROGRESS,
         book.find(owner(1), OVERWORLD, transfer(1)).outcome()
      );
      assertTrue(book.sizeFor(owner(1)) <= WorkspaceSubmissionBook.MAX_ENTRIES_PER_OWNER);
   }

   @Test
   void runningTransferSurvivesTheServerLimit() {
      WorkspaceSubmissionBook book = new WorkspaceSubmissionBook();
      UUID running = transfer(1);
      book.record(owner(1), OVERWORLD, running, OperationSubmissionOutcome.IN_PROGRESS, NOW);
      // Enough owners with finished entries to pass the server limit many times over.
      int owners = WorkspaceSubmissionBook.MAX_ENTRIES_PER_SERVER / WorkspaceSubmissionBook.MAX_ENTRIES_PER_OWNER + 4;
      for (int index = 0; index < owners; index++) {
         for (int entry = 0; entry < WorkspaceSubmissionBook.MAX_ENTRIES_PER_OWNER; entry++) {
            book.record(
               owner(1000 + index), OVERWORLD, transfer(1000 + index * 100 + entry),
               OperationSubmissionOutcome.APPLIED, NOW + index
            );
         }
      }

      assertNotNull(book.find(owner(1), OVERWORLD, running));
      assertTrue(book.totalSize() <= WorkspaceSubmissionBook.MAX_ENTRIES_PER_SERVER);
   }

   @Test
   void manyOwnersStayBounded() {
      WorkspaceSubmissionBook book = new WorkspaceSubmissionBook();
      // Every owner stays under its own limit, so the server limit is the only bound
      // that can hold the book down. A per-owner limit alone is not a bound.
      int owners = WorkspaceSubmissionBook.MAX_ENTRIES_PER_SERVER / 8 + 64;
      for (int index = 0; index < owners; index++) {
         for (int entry = 0; entry < 8; entry++) {
            book.record(
               owner(2000 + index), OVERWORLD, transfer(2000 + index * 10 + entry),
               OperationSubmissionOutcome.APPLIED, NOW + index
            );
         }
      }

      assertTrue(book.totalSize() <= WorkspaceSubmissionBook.MAX_ENTRIES_PER_SERVER);
   }

   @Test
   void finishedEntryAgesOut() {
      WorkspaceSubmissionBook book = new WorkspaceSubmissionBook();
      book.record(owner(1), OVERWORLD, transfer(1), OperationSubmissionOutcome.APPLIED, NOW);
      book.expireFinished(NOW + WorkspaceSubmissionBook.FINISHED_TTL_MILLIS + 1);

      assertNull(book.find(owner(1), OVERWORLD, transfer(1)));
   }

   @Test
   void runningEntryDoesNotAgeOut() {
      WorkspaceSubmissionBook book = new WorkspaceSubmissionBook();
      book.record(owner(1), OVERWORLD, transfer(1), OperationSubmissionOutcome.IN_PROGRESS, NOW);
      book.expireFinished(NOW + WorkspaceSubmissionBook.FINISHED_TTL_MILLIS * 10);

      // A long world write can pass the time limit. It must keep its answer, because the
      // server still performs the work.
      assertNotNull(book.find(owner(1), OVERWORLD, transfer(1)));
   }

   @Test
   void clearOwnerDropsOnlyThatOwner() {
      WorkspaceSubmissionBook book = new WorkspaceSubmissionBook();
      book.record(owner(1), OVERWORLD, transfer(1), OperationSubmissionOutcome.APPLIED, NOW);
      book.record(owner(2), OVERWORLD, transfer(2), OperationSubmissionOutcome.APPLIED, NOW);
      book.clearOwner(owner(1));

      assertNull(book.find(owner(1), OVERWORLD, transfer(1)));
      assertNotNull(book.find(owner(2), OVERWORLD, transfer(2)));
   }

   @Test
   void anExpiredOwnerLeavesTheMap() {
      WorkspaceSubmissionBook book = new WorkspaceSubmissionBook();
      // Thousands of owners, one finished entry each. These are players that joined once
      // and never returned, which is the normal case on a busy server. The count stays
      // below the server entry limit, so no trim removes them first.
      int owners = 3000;
      for (int index = 0; index < owners; index++) {
         book.record(owner(3000 + index), OVERWORLD, transfer(3000 + index), OperationSubmissionOutcome.APPLIED, NOW);
      }
      assertEquals(owners, book.ownerCount());

      book.expireFinished(NOW + WorkspaceSubmissionBook.FINISHED_TTL_MILLIS + 1);

      // The entry limit alone does not bound the owner map. Every owner must leave when
      // its last entry expires, or the map grows for the lifetime of the server.
      assertEquals(0, book.ownerCount());
      assertEquals(0, book.totalSize());
   }

   @Test
   void anOwnerWithARunningEntryStaysAfterExpiry() {
      WorkspaceSubmissionBook book = new WorkspaceSubmissionBook();
      book.record(owner(1), OVERWORLD, transfer(1), OperationSubmissionOutcome.IN_PROGRESS, NOW);
      book.record(owner(2), OVERWORLD, transfer(2), OperationSubmissionOutcome.APPLIED, NOW);

      book.expireFinished(NOW + WorkspaceSubmissionBook.FINISHED_TTL_MILLIS + 1);

      // The running owner keeps its queue. The finished owner leaves the map.
      assertEquals(1, book.ownerCount());
      assertNotNull(book.find(owner(1), OVERWORLD, transfer(1)));
   }

   @Test
   void theServerTrimLeavesNoEmptyOwner() {
      WorkspaceSubmissionBook book = new WorkspaceSubmissionBook();
      // Each owner holds one entry, so the server limit can only be met by removing whole
      // owners. Every removed owner must leave the map.
      int owners = WorkspaceSubmissionBook.MAX_ENTRIES_PER_SERVER + 500;
      for (int index = 0; index < owners; index++) {
         book.record(owner(4000 + index), OVERWORLD, transfer(4000 + index), OperationSubmissionOutcome.APPLIED, NOW);
      }

      assertEquals(WorkspaceSubmissionBook.MAX_ENTRIES_PER_SERVER, book.totalSize());
      assertEquals(WorkspaceSubmissionBook.MAX_ENTRIES_PER_SERVER, book.ownerCount());
   }

   @Test
   void aTerminalEntryIsNotReopened() {
      WorkspaceSubmissionBook book = new WorkspaceSubmissionBook();
      book.record(owner(1), OVERWORLD, transfer(1), OperationSubmissionOutcome.APPLIED, NOW);

      // A client replay can send the same transfer again. The server already answered it,
      // and a reopened entry would report running work that does not exist. The answer is
      // the stored state, so the caller reports applied work instead of running work.
      assertEquals(
         OperationSubmissionOutcome.APPLIED,
         book.record(owner(1), OVERWORLD, transfer(1), OperationSubmissionOutcome.IN_PROGRESS, NOW + 1)
      );
      assertEquals(OperationSubmissionOutcome.APPLIED, book.find(owner(1), OVERWORLD, transfer(1)).outcome());
   }

   @Test
   void anAppliedEntryIsNotReplacedByAFailure() {
      // This is the defect that produced a contradictory packet. A rejected replay reported
      // a retryable failure for a transfer that already applied, and the ledger accepted it.
      WorkspaceSubmissionBook book = new WorkspaceSubmissionBook();
      book.record(owner(1), OVERWORLD, transfer(1), OperationSubmissionOutcome.APPLIED, NOW);

      assertEquals(
         OperationSubmissionOutcome.APPLIED,
         book.record(owner(1), OVERWORLD, transfer(1), OperationSubmissionOutcome.FAILED_RETRYABLE, NOW + 1)
      );
      assertEquals(
         OperationSubmissionOutcome.APPLIED,
         book.record(owner(1), OVERWORLD, transfer(1), OperationSubmissionOutcome.FAILED_NONRETRYABLE, NOW + 2)
      );
      assertEquals(
         OperationSubmissionOutcome.APPLIED,
         book.record(owner(1), OVERWORLD, transfer(1), OperationSubmissionOutcome.RECOVERY_REQUIRED, NOW + 3)
      );
      assertEquals(OperationSubmissionOutcome.APPLIED, book.find(owner(1), OVERWORLD, transfer(1)).outcome());
   }

   @Test
   void aRunningEntryIsNotReplacedByAWeakFailure() {
      WorkspaceSubmissionBook book = new WorkspaceSubmissionBook();
      book.record(owner(1), OVERWORLD, transfer(1), OperationSubmissionOutcome.FAILED_RETRYABLE, NOW);

      // A retryable failure is below a failure that removes the retry option. A later,
      // stronger failure may replace it, because the client must stop offering a retry.
      assertEquals(
         OperationSubmissionOutcome.FAILED_NONRETRYABLE,
         book.record(owner(1), OVERWORLD, transfer(1), OperationSubmissionOutcome.FAILED_NONRETRYABLE, NOW + 1)
      );
      assertEquals(
         OperationSubmissionOutcome.FAILED_NONRETRYABLE,
         book.record(owner(1), OVERWORLD, transfer(1), OperationSubmissionOutcome.FAILED_RETRYABLE, NOW + 2)
      );
      assertEquals(
         OperationSubmissionOutcome.FAILED_NONRETRYABLE,
         book.record(owner(1), OVERWORLD, transfer(1), OperationSubmissionOutcome.IN_PROGRESS, NOW + 3)
      );
   }

   @Test
   void aRetryableFailureStillMovesToApplied() {
      WorkspaceSubmissionBook book = new WorkspaceSubmissionBook();
      book.record(owner(1), OVERWORLD, transfer(1), OperationSubmissionOutcome.FAILED_RETRYABLE, NOW);

      // Applied work is the strongest state. The normal completion of a task must land.
      assertEquals(
         OperationSubmissionOutcome.APPLIED,
         book.record(owner(1), OVERWORLD, transfer(1), OperationSubmissionOutcome.APPLIED, NOW + 1)
      );
   }

   @Test
   void aTerminalEntryIsNotReopenedAsInFlight() {
      WorkspaceSubmissionBook book = new WorkspaceSubmissionBook();
      book.record(owner(1), OVERWORLD, transfer(1), OperationSubmissionOutcome.FAILED_NONRETRYABLE, NOW);

      assertEquals(
         OperationSubmissionOutcome.FAILED_NONRETRYABLE,
         book.record(owner(1), OVERWORLD, transfer(1), OperationSubmissionOutcome.IN_FLIGHT, NOW + 1)
      );
      assertEquals(
         OperationSubmissionOutcome.FAILED_NONRETRYABLE, book.find(owner(1), OVERWORLD, transfer(1)).outcome()
      );
   }

   @Test
   void aRunningEntryMovesToATerminalState() {
      WorkspaceSubmissionBook book = new WorkspaceSubmissionBook();
      assertEquals(
         OperationSubmissionOutcome.IN_PROGRESS,
         book.record(owner(1), OVERWORLD, transfer(1), OperationSubmissionOutcome.IN_PROGRESS, NOW)
      );

      // The normal path must still work: running work reaches its result.
      assertEquals(
         OperationSubmissionOutcome.APPLIED,
         book.record(owner(1), OVERWORLD, transfer(1), OperationSubmissionOutcome.APPLIED, NOW + 1)
      );
      assertEquals(OperationSubmissionOutcome.APPLIED, book.find(owner(1), OVERWORLD, transfer(1)).outcome());
   }

   @Test
   void aRunningEntryMovesToEveryFailureState() {
      for (OperationSubmissionOutcome failure : java.util.List.of(
         OperationSubmissionOutcome.FAILED_RETRYABLE,
         OperationSubmissionOutcome.FAILED_NONRETRYABLE,
         OperationSubmissionOutcome.RECOVERY_REQUIRED
      )) {
         WorkspaceSubmissionBook book = new WorkspaceSubmissionBook();
         book.record(owner(1), OVERWORLD, transfer(1), OperationSubmissionOutcome.IN_PROGRESS, NOW);

         // Every failure state is stronger than running work, so every one of them lands.
         assertEquals(failure, book.record(owner(1), OVERWORLD, transfer(1), failure, NOW + 1), failure.name());
      }
   }

   @Test
   void theSameOutcomeIsNotRecordedTwice() {
      WorkspaceSubmissionBook book = new WorkspaceSubmissionBook();
      book.record(owner(1), OVERWORLD, transfer(1), OperationSubmissionOutcome.APPLIED, NOW);

      assertEquals(
         OperationSubmissionOutcome.APPLIED,
         book.record(owner(1), OVERWORLD, transfer(1), OperationSubmissionOutcome.APPLIED, NOW + 1)
      );
      assertEquals(1, book.sizeFor(owner(1)));
   }

   @Test
   void theStrengthOrderIsMonotonic() {
      // Running work is weakest, applied work is strongest.
      assertTrue(
         WorkspaceSubmissionBook.rank(OperationSubmissionOutcome.APPLIED)
            > WorkspaceSubmissionBook.rank(OperationSubmissionOutcome.FAILED_NONRETRYABLE)
      );
      assertTrue(
         WorkspaceSubmissionBook.rank(OperationSubmissionOutcome.FAILED_NONRETRYABLE)
            > WorkspaceSubmissionBook.rank(OperationSubmissionOutcome.FAILED_RETRYABLE)
      );
      assertTrue(
         WorkspaceSubmissionBook.rank(OperationSubmissionOutcome.FAILED_RETRYABLE)
            > WorkspaceSubmissionBook.rank(OperationSubmissionOutcome.IN_PROGRESS)
      );
   }

   @Test
   void anInvalidRecordReturnsNoState() {
      WorkspaceSubmissionBook book = new WorkspaceSubmissionBook();

      assertNull(book.record(null, OVERWORLD, transfer(1), OperationSubmissionOutcome.APPLIED, NOW));
      assertNull(book.record(owner(1), null, transfer(1), OperationSubmissionOutcome.APPLIED, NOW));
      assertNull(book.record(owner(1), OVERWORLD, null, OperationSubmissionOutcome.APPLIED, NOW));
      assertNull(book.record(owner(1), OVERWORLD, transfer(1), null, NOW));
   }

   @Test
   void holdsReportsAnExistingTransfer() {
      WorkspaceSubmissionBook book = new WorkspaceSubmissionBook();
      book.record(owner(1), OVERWORLD, transfer(1), OperationSubmissionOutcome.APPLIED, NOW);

      // The admission path uses this to refuse a replay that the server already answered.
      assertTrue(book.holds(owner(1), OVERWORLD, transfer(1)));
      assertFalse(book.holds(owner(1), NETHER, transfer(1)));
      assertFalse(book.holds(owner(1), OVERWORLD, transfer(2)));
   }

   @Test
   void anInvalidRecordIsIgnored() {
      WorkspaceSubmissionBook book = new WorkspaceSubmissionBook();
      book.record(null, OVERWORLD, transfer(1), OperationSubmissionOutcome.APPLIED, NOW);
      book.record(owner(1), null, transfer(1), OperationSubmissionOutcome.APPLIED, NOW);
      book.record(owner(1), OVERWORLD, null, OperationSubmissionOutcome.APPLIED, NOW);
      book.record(owner(1), OVERWORLD, transfer(1), null, NOW);

      assertEquals(0, book.totalSize());
      assertNull(book.find(owner(1), OVERWORLD, transfer(1)));
   }
}
