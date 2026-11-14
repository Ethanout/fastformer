package io.github.fastformer.server.session.execution;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.common.api.ShapeCommitted;
import io.github.fastformer.core.gate.Gate;
import io.github.fastformer.server.history.HistoryService;
import io.github.fastformer.server.history.NoDiskHistoryRepository;
import io.github.fastformer.server.history.entry.WorldChangeBatch;
import io.github.fastformer.server.recovery.PersistentRecoveryJournal;
import io.github.fastformer.server.recovery.WorldRecoverySnapshot;
import io.github.fastformer.server.task.CancelReason;
import io.github.fastformer.server.task.TaskStep;
import io.github.fastformer.server.task.WorldTask;
import io.github.fastformer.server.task.WorldTaskContext;
import io.github.fastformer.server.worldwrite.snapshot.BlockEntitySnapshot;
import io.github.fastformer.server.worldwrite.snapshot.ReversibleBlockSnapshot;
import io.github.fastformer.server.worldwrite.transaction.WorldChangeTransaction;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class OperationCommittedEventTest {
   @Test void commitPublishesOnceAfterHistoryAndQueueSettlementDespiteThrowingListener() {
      var execution = execution();
      var context = new WorldTaskContext(null, UUID.randomUUID());
      var task = new CompletedWriter(TaskStep.COMPLETE, true);
      List<ShapeCommitted> events = new ArrayList<>();
      context.events().subscribe(ShapeCommitted.class, event -> { throw new IllegalStateException("extension"); });
      try (var history = history()) {
         context.events().subscribe(ShapeCommitted.class, event -> {
            assertFalse(execution.active(context.owner()), "Callback must observe the completed queue removal");
            assertTrue(task.released);
            events.add(event);
            execution.service(context, history);
         });
         execution.admit(context.owner(), task);
         execution.service(context, history);
         assertTrue(events.isEmpty(), "Initial history save is pending");
         execution.service(context, history);
         assertTrue(events.isEmpty(), "History index is still pending");
         execution.service(context, history);
         execution.service(context, history);
         assertEquals(1, events.size());
         assertEquals(task.operationId(), events.getFirst().operationId());
         assertEquals(1, events.getFirst().changedBlocks());
         assertEquals(ShapeCommitted.Kind.OPERATION, events.getFirst().kind());
         assertFalse(execution.active(context.owner()));
      }
   }

   @Test void noOpAndRecoveryHandoverDoNotAnnounceACommittedShape() {
      for (TaskStep terminal : List.of(TaskStep.EMPTY, TaskStep.FAILED)) {
         var execution = execution();
         var context = new WorldTaskContext(null, UUID.randomUUID());
         var task = new CompletedWriter(terminal, terminal == TaskStep.FAILED);
         List<ShapeCommitted> events = new ArrayList<>();
         context.events().subscribe(ShapeCommitted.class, events::add);
         try (var history = history()) {
            execution.admit(context.owner(), task);
            execution.service(context, history);
            assertTrue(events.isEmpty(), terminal.name());
            assertFalse(execution.active(context.owner()));
         }
      }
   }

   private static OperationTasks execution() {
      return new OperationTasks(() -> false, ignored -> false, context -> (completion, player) -> {});
   }

   private static HistoryService history() {
      return new HistoryService(Gate.require("test", ignored -> true), Gate.require("test", ignored -> true), new NoDiskHistoryRepository());
   }

   /** Supplies a finished writer at the public execution boundary; history persistence is real. */
   private static final class CompletedWriter implements WorldTask {
      private final UUID id = UUID.randomUUID();
      private final TaskStep terminal;
      private final boolean writes;
      private final WorldChangeTransaction transaction = new WorldChangeTransaction();
      private WorldChangeBatch batch;
      private boolean released;

      private CompletedWriter(TaskStep terminal, boolean writes) {
         this.terminal = terminal;
         this.writes = writes;
         if (writes) {
            CompoundTag before = new CompoundTag();
            before.putString("marker", "before");
            CompoundTag after = new CompoundTag();
            after.putString("marker", "after");
            batch = WorldChangeBatch.capturePairs(Level.OVERWORLD,
               List.of(new ReversibleBlockSnapshot(BlockPos.ZERO, null, null, new BlockEntitySnapshot(before))),
               Map.of(BlockPos.ZERO.asLong(), new ReversibleBlockSnapshot(BlockPos.ZERO, null, null, new BlockEntitySnapshot(after))))
               .orElseThrow().withOperationId(id);
         }
      }

      public TaskStep tick(WorldTaskContext context) { return terminal; }
      public void cancel(CancelReason reason) {}
      public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
      public String phaseName() { return "COMPLETE"; }
      public UUID operationId() { return id; }
      public String metricsSummary() { return "completed event fixture"; }
      public boolean hasWrites() { return writes; }
      public WorldChangeTransaction transaction() { return transaction; }
      public PersistentRecoveryJournal journal() { return null; }
      public Optional<WorldChangeBatch> preparedBatch() { return Optional.ofNullable(batch); }
      public WorldRecoverySnapshot stopAndTransferRecovery() {
         return new WorldRecoverySnapshot(new ArrayDeque<>(), new java.util.HashMap<>(), CompletableFuture.completedFuture(null));
      }
      public boolean acquireLease(WorldTaskContext context) { return true; }
      public void releaseLease(WorldTaskContext context) {}
      public void releaseAfterCancelledJournal(WorldTaskContext context) {}
      public void releaseMemoryReservation() {}
      public void releaseCommittedTransactionState() { released = true; batch = null; }
      public void markComplete() {}
   }
}
