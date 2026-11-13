package io.github.fastformer.server.task.runtime;

import io.github.fastformer.server.task.WorldTaskBudget;
import io.github.fastformer.server.task.WorldTaskContext;
import io.github.fastformer.fastplace.world.memory.MemoryReservation;
import io.github.fastformer.server.recovery.WorldJournalPreparation;
import io.github.fastformer.server.task.CancelReason;
import io.github.fastformer.server.task.TaskStep;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorldTaskProgressTest {
   private final WorldTaskContext context = new WorldTaskContext(null, UUID.randomUUID());

   @Test void generationWaitingRemainsRetryableWithoutTouchingTheWorld() {
      var task = new Writer();
      task.preparation = TaskStep.GENERATING;
      assertEquals(TaskStep.GENERATING, task.tick(context));
      assertEquals(TaskStep.GENERATING, task.tick(context));
      assertEquals(2, task.preparations);
      assertEquals(0, task.batches);
   }

   @Test void terminalResultDoesNotRepeatPreparationOrWorldWorkWhileHistoryIsPending() {
      var task = new Writer();
      task.result = TaskStep.COMPLETE;
      assertEquals(TaskStep.COMPLETE, batch(task));
      task.throwInBatch = true;
      assertEquals(TaskStep.COMPLETE, task.tick(context));
      assertEquals(TaskStep.COMPLETE, batch(task));
      assertEquals(0, task.preparations);
      assertEquals(1, task.batches);
   }

   @Test void runningBatchesKeepAdvancingUntilTheirTerminalResult() {
      var task = new Writer();
      assertEquals(TaskStep.ACTIVE, batch(task));
      task.result = TaskStep.COMPLETE;
      assertEquals(TaskStep.COMPLETE, batch(task));
      assertEquals(TaskStep.COMPLETE, batch(task));
      assertEquals(2, task.batches);
   }

   @Test void thrownBatchStillRecordsItsCostBeforeRecoveryTakesOwnership() {
      var task = new Writer();
      task.throwInBatch = true;
      assertThrows(IllegalStateException.class, () -> batch(task));
      assertEquals(1, task.previousBatchCells());
      assertTrue(task.previousBatchNanos() > 0);
      task.cancel(CancelReason.RECOVERY);
      assertEquals(TaskStep.CANCELLED, batch(task));
      assertEquals(1, task.batches);
   }

   @Test void cancelledTaskCannotAdvanceEvenWhenStoppingActivityFailed() {
      var task = new Writer();
      task.throwOnStop = true;
      assertThrows(IllegalStateException.class, () -> task.cancel(CancelReason.RECOVERY));
      assertEquals(TaskStep.CANCELLED, task.tick(context));
      assertEquals(TaskStep.CANCELLED, batch(task));
      assertEquals(0, task.preparations);
      assertEquals(0, task.batches);
      task.throwOnStop = false;
      assertDoesNotThrow(task::stopAndTransferRecovery);
   }

   @Test void recoveryKeepsMemoryUntilHandoverButShutdownReleasesIt() {
      var task = new Writer();
      TaskRuntimeFixtures.reserveWorkingSet(task, 64);
      MemoryReservation reservation = task.memoryReservation;
      try {
         task.cancel(CancelReason.RECOVERY);
         assertEquals(64, reservation.bytes());
         task.cancel(CancelReason.SHUTDOWN);
         assertEquals(0, reservation.bytes());
         task.cancel(CancelReason.SHUTDOWN);
         assertNull(task.memoryReservation);
      } finally {
         reservation.close();
      }
   }

   @Test void aBlockedWriterCannotRestartAfterTheGateReopens() {
      var task = new Writer();
      context.writeGate().blockNewWrites();
      assertEquals(TaskStep.BLOCKED, task.tick(context));
      long recovery = context.writeGate().beginRecovery();
      context.writeGate().finishRecovery(recovery, true);
      assertEquals(TaskStep.BLOCKED, task.tick(context));
      assertEquals(0, task.preparations);
      assertEquals(0, task.batches);
   }

   @Test void unloadedDimensionReleasesWorkingSetWithoutCompletingTheTask() {
      var task = new Writer();
      TaskRuntimeFixtures.reserveWorkingSet(task, 64);
      MemoryReservation reservation = task.memoryReservation;
      try {
         assertEquals(TaskStep.WORLD_UNLOADED, task.tick(context));
         assertEquals(0, reservation.bytes());
         assertEquals(TaskStep.WORLD_UNLOADED, task.tick(context));
         assertEquals(2, task.preparations);
         assertEquals(0, task.batches);
      } finally {
         reservation.close();
      }
   }

   private TaskStep batch(Writer task) {
      return task.tickBatch(context, null, WorldTaskBudget.forServerTick());
   }

   private static final class Writer extends LeasedJournaledTask {
      private TaskStep preparation = TaskStep.ACTIVE;
      private TaskStep result = TaskStep.ACTIVE;
      private int preparations;
      private int batches;
      private boolean throwInBatch;
      private boolean throwOnStop;

      private Writer() { super(new WorldJournalPreparation(Runnable::run)); }
      @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
      @Override protected TaskStep prepareTick(WorldTaskContext context) { preparations++; return preparation; }
      @Override protected void stopTaskActivity() {
         if (throwOnStop) throw new IllegalStateException("injected stop failure");
      }
      @Override protected TaskStep tickBody(WorldTaskContext context, ServerLevel level, WorldTaskBudget budget) {
         batches++;
         budget.tryConsume();
         if (throwInBatch) throw new IllegalStateException("injected batch failure");
         return result;
      }
   }
}
