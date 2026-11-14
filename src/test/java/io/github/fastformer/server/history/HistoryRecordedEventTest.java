package io.github.fastformer.server.history;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.common.api.HistoryRecorded;
import io.github.fastformer.core.gate.Gate;
import io.github.fastformer.server.api.ServerEventStream;
import io.github.fastformer.server.history.entry.HistoryBounds;
import io.github.fastformer.server.history.entry.HistoryEntry;
import io.github.fastformer.server.history.replay.EntryReplayRegistry;
import io.github.fastformer.server.recovery.JournalPreparation;
import io.github.fastformer.server.task.WorldTaskContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

class HistoryRecordedEventTest {
   @Test void durableRecordWaitsForBothBatchAndIndexAndSurvivesListenerFailure() {
      var repository = new ControlledRepository();
      var owner = new OwnerHistory();
      var context = new WorldTaskContext(null, UUID.randomUUID());
      List<HistoryRecorded> events = new ArrayList<>();
      context.events().subscribe(HistoryRecorded.class, event -> { throw new IllegalStateException("extension"); });
      context.events().subscribe(HistoryRecorded.class, events::add);
      var entry = new Entry(UUID.randomUUID());
      assertEquals(JournalPreparation.PENDING, owner.pollCommit(context, entry, null, repository));
      assertEquals(JournalPreparation.PENDING, owner.pollCommit(context, entry, null, repository));
      assertTrue(events.isEmpty());
      repository.batch.complete(null);
      assertEquals(JournalPreparation.PENDING, owner.pollCommit(context, entry, null, repository));
      assertTrue(events.isEmpty());
      repository.index.complete(null);
      assertEquals(JournalPreparation.READY, owner.pollCommit(context, entry, null, repository));
      assertEquals(1, events.size());
      assertEquals(HistoryRecorded.Persistence.DURABLE, events.getFirst().persistence());
      assertEquals(entry.operationId(), events.getFirst().operationId());
      assertSame(entry, owner.history().peek(true));
      assertNull(owner.durableCommit);
   }

   @Test void failedBatchDoesNotPublishAnAcceptedRecord() {
      var repository = new ControlledRepository();
      var owner = new OwnerHistory();
      var context = new WorldTaskContext(null, UUID.randomUUID());
      List<HistoryRecorded> events = new ArrayList<>();
      context.events().subscribe(HistoryRecorded.class, events::add);
      var entry = new Entry(UUID.randomUUID());
      owner.pollCommit(context, entry, null, repository);
      repository.batch.completeExceptionally(new IllegalStateException("disk"));
      assertEquals(JournalPreparation.FAILED, owner.pollCommit(context, entry, null, repository));
      assertTrue(events.isEmpty());
      assertEquals(0, owner.history().size(true));
   }

   @Test void ordinaryAdmissionExplicitlyReportsQueuedPersistenceEvenWhileSaveIsPending() {
      var repository = new ControlledRepository();
      var context = new WorldTaskContext(null, UUID.randomUUID());
      List<HistoryRecorded> events = new ArrayList<>();
      context.events().subscribe(HistoryRecorded.class, events::add);
      var replay = EntryReplayRegistry.create(registry -> registry.register(Entry.TYPE, Entry.class,
         (entry, undo) -> { throw new AssertionError("Admission must not replay"); }));
      try (var history = new HistoryService(Gate.require("test", ignored -> true), Gate.require("test", ignored -> true), repository, replay)) {
         var entry = new Entry(UUID.randomUUID());
         history.addBatch(context, entry);
         assertEquals(1, events.size());
         assertEquals(HistoryRecorded.Persistence.QUEUED, events.getFirst().persistence());
         assertFalse(repository.batch.isDone());
         repository.batch.complete(null);
         assertEquals(1, events.size(), "Save completion is not another history admission");
      }
   }

   private record Entry(UUID operationId) implements HistoryEntry {
      private static final ResourceLocation TYPE = ResourceLocation.parse("example:event_entry");
      public ResourceLocation type() { return TYPE; }
      public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
      public Entry withOperationId(UUID id) { return new Entry(id); }
      public int size() { return 2; }
      public int estimatedBytes() { return 64; }
      public HistoryBounds bounds() { return new HistoryBounds(0, 0, 0, 1, 0, 0); }
      public BlockPos position(int index) { return new BlockPos(index, 0, 0); }
      public int match(ServerLevel level, int index, boolean undo) { throw new AssertionError("No replay"); }
      public boolean apply(ServerLevel level, int index, boolean undo, int flags) { throw new AssertionError("No replay"); }
   }

   private static final class ControlledRepository implements HistoryRepository<HistoryEntry> {
      private final CompletableFuture<Void> batch = new CompletableFuture<>();
      private final CompletableFuture<Void> index = new CompletableFuture<>();
      public CompletableFuture<Void> publishNewBatch(UUID owner, HistoryEntry entry, List<UUID> undo, List<UUID> redo) { return batch; }
      public CompletableFuture<Void> publishBatchOnly(UUID owner, HistoryEntry entry) { return batch; }
      public CompletableFuture<Void> publishIndex(UUID owner, List<UUID> undo, List<UUID> redo) { return index; }
      public CompletableFuture<Void> publishSnapshot(UUID owner, List<HistoryEntry> undo, List<HistoryEntry> redo,
         List<UUID> undoOrder, List<UUID> redoOrder) { return CompletableFuture.completedFuture(null); }
      public CompletableFuture<Void> reconcileCommittedJournal(UUID owner, UUID operation) { return CompletableFuture.completedFuture(null); }
      public CompletableFuture<Snapshot<HistoryEntry>> loadSnapshot(UUID owner, int count, long bytes) { return CompletableFuture.completedFuture(Snapshot.empty()); }
      public CompletableFuture<List<HistoryEntry>> loadPage(UUID owner, List<UUID> ids, int count, long bytes) { return CompletableFuture.completedFuture(List.of()); }
      public CompletableFuture<Void> pendingWrites() { return CompletableFuture.completedFuture(null); }
      public void resumeCleanup(Set<UUID> owners) {}
      public void close() {}
   }
}
