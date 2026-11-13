package io.github.fastformer.testkit;

import io.github.fastformer.server.task.WorldTaskContext;
import io.github.fastformer.fastplace.world.WorldWriteCoordinator;
import io.github.fastformer.server.history.entry.EntryCodec;
import io.github.fastformer.server.history.entry.EntryCodecRegistry;
import io.github.fastformer.server.history.entry.HistoryBounds;
import io.github.fastformer.server.history.entry.HistoryEntry;
import io.github.fastformer.server.history.replay.EntryReplayRegistry;
import io.github.fastformer.server.history.replay.EntryReplayState;
import io.github.fastformer.server.history.replay.ReplayJournal;
import io.github.fastformer.server.recovery.JournalPreparation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** A non-block entry with an observable journal, shared by storage tests and real replay-loop GameTests. */
public final class CounterHistory {
   public static final ResourceLocation TYPE = ResourceLocation.fromNamespaceAndPath("test", "counter");
   public final AtomicInteger value = new AtomicInteger();
   public final List<String> events = new ArrayList<>();
   public boolean failNextApply;

   public CounterEntry entry(int before, int after) { return new CounterEntry(UUID.randomUUID(), before, after); }

   public EntryCodecRegistry codecs() {
      return EntryCodecRegistry.standard(registry -> registry.register(TYPE, CounterEntry.class, new EntryCodec<>() {
         @Override public CompoundTag encode(CounterEntry entry) {
            var root = new CompoundTag();
            root.putUUID("Operation", entry.operationId());
            root.putInt("Before", entry.before());
            root.putInt("After", entry.after());
            return root;
         }
         @Override public CounterEntry decode(HolderLookup.Provider registries, CompoundTag root) {
            return new CounterEntry(root.getUUID("Operation"), root.getInt("Before"), root.getInt("After"));
         }
      }));
   }

   public EntryReplayRegistry replays() {
      return EntryReplayRegistry.standard(registry -> registry.register(TYPE, CounterEntry.class, CounterReplay::new));
   }

   public final class CounterEntry implements HistoryEntry {
      private final UUID operationId;
      private final int before;
      private final int after;

      private CounterEntry(UUID operationId, int before, int after) {
         this.operationId = operationId;
         this.before = before;
         this.after = after;
      }
      public int before() { return before; }
      public int after() { return after; }
      @Override public ResourceLocation type() { return TYPE; }
      @Override public ResourceKey<Level> dimension() { return Level.OVERWORLD; }
      @Override public UUID operationId() { return operationId; }
      @Override public CounterEntry withOperationId(UUID id) { return new CounterEntry(id, before, after); }
      @Override public int size() { return 1; }
      @Override public int estimatedBytes() { return 64; }
      @Override public HistoryBounds bounds() { return new HistoryBounds(0, 0, 0, 0, 0, 0); }
      @Override public BlockPos position(int index) { return BlockPos.ZERO; }
      @Override public int match(ServerLevel level, int index, boolean undo) {
         return value.get() == (undo ? after : before) ? 1 : value.get() == (undo ? before : after) ? 2 : 0;
      }
      @Override public boolean apply(ServerLevel level, int index, boolean undo, int flags) {
         value.set(undo ? before : after);
         if (!failNextApply) return true;
         failNextApply = false;
         return false;
      }
   }

   private final class CounterReplay implements EntryReplayState {
      private final CounterEntry entry;
      private final boolean undo;
      private Integer conflictSource;
      private boolean skipped;

      private CounterReplay(CounterEntry entry, boolean undo) { this.entry = entry; this.undo = undo; }
      @Override public boolean captureConflict(ServerLevel level, int index, boolean skip) {
         conflictSource = value.get();
         skipped = skip;
         return true;
      }
      @Override public int match(ServerLevel level, int index) {
         if (skipped) return 2;
         int match = entry.match(level, index, undo);
         return match == 2 || conflictSource == null ? match : value.get() == conflictSource ? 1 : 0;
      }
      @Override public boolean rollback(ServerLevel level, int index, int flags) {
         value.set(conflictSource == null ? undo ? entry.after() : entry.before() : conflictSource);
         return true;
      }
      @Override public int rollbackMatch(ServerLevel level, int index) {
         return conflictSource != null && value.get() == conflictSource ? 2 : entry.match(level, index, !undo);
      }
      @Override public Optional<Predicate<ServerLevel>> capturePartial(ServerLevel level, int index) {
         int captured = value.get();
         return Optional.of(ignored -> value.get() == captured);
      }
      @Override public boolean hasSkippedCells() { return skipped; }
      @Override public ReplayJournal journal() {
         return new ReplayJournal() {
            @Override public JournalPreparation prepare(WorldTaskContext context, UUID operation) {
               events.add("prepare");
               return JournalPreparation.READY;
            }
            @Override public boolean complete() { events.add("complete"); return true; }
            @Override public boolean resolve(WorldTaskContext context) { events.add("resolve"); return true; }
            @Override public void cancelPreparation() { events.add("cancel"); }
            @Override public void releaseAfterCancellation(WorldWriteCoordinator.Lease lease) {
               events.add("release");
               WorldWriteCoordinator.release(lease);
            }
            @Override public String failureReason() { return "counter journal"; }
         };
      }
   }
}
