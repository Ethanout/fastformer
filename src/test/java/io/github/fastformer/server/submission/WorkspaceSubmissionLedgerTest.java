package io.github.fastformer.server.submission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.fastformer.workspace.submission.OperationSubmissionOutcome;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class WorkspaceSubmissionLedgerTest {
   private static final ResourceLocation DIMENSION = ResourceLocation.withDefaultNamespace("overworld");
   private final UUID owner = UUID.randomUUID();
   private final UUID transfer = UUID.randomUUID();

   @Test
   void finishingAnswersTheStrongestStoredOutcome() {
      var ledger = new WorkspaceSubmissionLedger();
      ledger.begin(owner, DIMENSION, transfer);
      assertEquals(OperationSubmissionOutcome.IN_PROGRESS, ledger.outcomeFor(owner, DIMENSION, transfer));
      assertEquals(OperationSubmissionOutcome.APPLIED,
         ledger.finish(owner, DIMENSION, transfer, true, false, false));
      assertEquals(OperationSubmissionOutcome.APPLIED,
         ledger.finish(owner, DIMENSION, transfer, false, true, false));
      assertEquals(OperationSubmissionOutcome.APPLIED, ledger.outcomeFor(owner, DIMENSION, transfer));
   }

   @Test
   void discardedLedgerCannotBeRecreatedByLateWrites() {
      var ledger = new WorkspaceSubmissionLedger();
      ledger.begin(owner, DIMENSION, transfer);
      ledger.onDiscard();
      ledger.onDiscard();
      ledger.begin(owner, DIMENSION, transfer);
      assertEquals(OperationSubmissionOutcome.UNKNOWN,
         ledger.finish(owner, DIMENSION, transfer, true, false, false));
      ledger.tick();
      assertNull(ledger.find(owner, DIMENSION, transfer));
      assertEquals(OperationSubmissionOutcome.UNKNOWN, ledger.outcomeFor(owner, DIMENSION, transfer));
   }

   @Test
   void discardingOneServerPreservesAnotherServersSameTransfer() {
      var first = new WorkspaceSubmissionLedger();
      var second = new WorkspaceSubmissionLedger();
      first.begin(owner, DIMENSION, transfer);
      second.finish(owner, DIMENSION, transfer, true, false, false);
      first.onDiscard();
      first.finish(owner, DIMENSION, transfer, false, true, false);
      assertEquals(OperationSubmissionOutcome.APPLIED, second.outcomeFor(owner, DIMENSION, transfer));
   }

   @Test
   void ownerCleanupDoesNotEraseAnotherOwnersRecord() {
      var ledger = new WorkspaceSubmissionLedger();
      UUID other = UUID.randomUUID();
      ledger.begin(owner, DIMENSION, transfer);
      ledger.begin(other, DIMENSION, transfer);
      ledger.clearOwner(owner);
      assertEquals(OperationSubmissionOutcome.UNKNOWN, ledger.outcomeFor(owner, DIMENSION, transfer));
      assertEquals(OperationSubmissionOutcome.IN_PROGRESS, ledger.outcomeFor(other, DIMENSION, transfer));
   }
}
