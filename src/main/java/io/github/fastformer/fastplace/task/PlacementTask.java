package io.github.fastformer.fastplace.task;

import io.github.fastformer.fastplace.world.BlockEntitySnapshot;
import io.github.fastformer.fastplace.FastPlaceMessages;
import io.github.fastformer.fastplace.world.JournalPreparation;
import io.github.fastformer.fastplace.OperationConflictMode;
import io.github.fastformer.fastplace.world.PersistentRecoveryJournal;
import io.github.fastformer.fastplace.PlacementUpdateMode;
import io.github.fastformer.fastplace.world.ReversibleBlockSnapshot;
import io.github.fastformer.fastplace.world.WorldChangeBatch;
import io.github.fastformer.fastplace.world.WorldOperationCommit;
import io.github.fastformer.fastplace.world.WorldOperationMemory;
import io.github.fastformer.fastplace.world.WorldTaskBudget;
import io.github.fastformer.fastplace.world.WorldTaskContext;
import io.github.fastformer.fastplace.world.WorldWriteCoordinator;
import io.github.fastformer.fastplace.world.WorldWriteSideEffectGuard;
import io.github.fastformer.fastplace.geometry.generation.GenerationFailed;
import io.github.fastformer.fastplace.geometry.generation.ProgressiveBlockGeneration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Player-owned placement transaction from asynchronous shape generation through
 * validated world writes and durable history commit.
 */
public final class PlacementTask {
   private final CompletableFuture<Set<BlockPos>> future;
   private final CompletableFuture<Set<BlockPos>> smartOutlineFuture;
   private final ProgressiveBlockGeneration generationProgress;
   private final BlockState state;
   private final Function<Set<BlockPos>, Map<BlockPos, BlockState>> smartStateResolver;
   private final OperationConflictMode conflictMode;
   private final PlacementUpdateMode updateMode;
   private final int maxPlacement;
   private final ResourceKey<Level> dimension;
   private final ArrayDeque<ReversibleBlockSnapshot> undo = new ArrayDeque<>();
   private final Map<BlockPos, ReversibleBlockSnapshot> expected = new HashMap<>();
   private final Map<BlockPos, ReversibleBlockSnapshot> after = new HashMap<>();
   private Set<BlockPos> targets;
   private Set<BlockPos> smartOutline;
   private Map<BlockPos, BlockState> smartStates = Map.of();
   private Iterator<BlockPos> blocks;
   private Iterator<BlockPos> validationIterator;
   private int validationRemaining;
   private boolean snapshotsValidated;
   private int remaining;
   private int total;
   private int placed;
   private boolean exceededLimit;
   private boolean generationConstraintsFailed;
   private boolean memoryChecked;
   private boolean memoryUnsafe;
   private long blockEntityReserve;
   private boolean failed;
   private PersistentRecoveryJournal journal;
   private CompletableFuture<Optional<PersistentRecoveryJournal>> journalFuture;
   private WorldOperationCommit commitPreparation;
   private Iterator<Map.Entry<BlockPos, ReversibleBlockSnapshot>> finalizationIterator;
   private volatile boolean cancelled;

   private PlacementTask(
      CompletableFuture<Set<BlockPos>> future,
      CompletableFuture<Set<BlockPos>> smartOutlineFuture,
      ProgressiveBlockGeneration generationProgress,
      Set<BlockPos> targets,
      Set<BlockPos> smartOutline,
      BlockState state,
      Function<Set<BlockPos>, Map<BlockPos, BlockState>> smartStateResolver,
      OperationConflictMode conflictMode,
      PlacementUpdateMode updateMode,
      int maxPlacement,
      ResourceKey<Level> dimension
   ) {
      this.future = future;
      this.smartOutlineFuture = smartOutlineFuture;
      this.generationProgress = generationProgress;
      this.targets = targets;
      this.smartOutline = smartOutline;
      this.remaining = targets == null ? 0 : targets.size();
      this.total = this.remaining;
      this.validationRemaining = this.remaining;
      this.state = state;
      this.smartStateResolver = smartStateResolver;
      this.conflictMode = conflictMode;
      this.updateMode = updateMode;
      this.maxPlacement = maxPlacement;
      this.dimension = dimension;
   }

   public static PlacementTask generating(
      CompletableFuture<Set<BlockPos>> future,
      ProgressiveBlockGeneration progress,
      BlockState state,
      OperationConflictMode conflictMode,
      PlacementUpdateMode updateMode,
      int maxPlacement,
      ResourceKey<Level> dimension
   ) {
      return new PlacementTask(
         future, null, progress, null, null, state, null,
         conflictMode, updateMode, maxPlacement, dimension
      );
   }

   public static PlacementTask generating(
      CompletableFuture<Set<BlockPos>> future,
      CompletableFuture<Set<BlockPos>> smartOutlineFuture,
      ProgressiveBlockGeneration progress,
      BlockState state,
      Function<Set<BlockPos>, Map<BlockPos, BlockState>> smartStateResolver,
      OperationConflictMode conflictMode,
      PlacementUpdateMode updateMode,
      int maxPlacement,
      ResourceKey<Level> dimension
   ) {
      return new PlacementTask(
         future, smartOutlineFuture, progress, null, null, state, smartStateResolver,
         conflictMode, updateMode, maxPlacement, dimension
      );
   }

   public static PlacementTask ready(
      Set<BlockPos> blocks,
      BlockState state,
      OperationConflictMode conflictMode,
      PlacementUpdateMode updateMode,
      int maxPlacement,
      ResourceKey<Level> dimension
   ) {
      return new PlacementTask(
         null, null, null, blocks, null, state, null,
         conflictMode, updateMode, maxPlacement, dimension
      );
   }

   public static PlacementTask ready(
      Set<BlockPos> blocks,
      Set<BlockPos> smartOutline,
      BlockState state,
      Function<Set<BlockPos>, Map<BlockPos, BlockState>> smartStateResolver,
      OperationConflictMode conflictMode,
      PlacementUpdateMode updateMode,
      int maxPlacement,
      ResourceKey<Level> dimension
   ) {
      return new PlacementTask(
         null, null, null, blocks, smartOutline, state, smartStateResolver,
         conflictMode, updateMode, maxPlacement, dimension
      );
   }

   public boolean prepare() {
      if (this.targets != null) {
         prepareSmartStates();
         checkMemory();
         return true;
      }
      if (!this.future.isDone() || this.smartOutlineFuture != null && !this.smartOutlineFuture.isDone()) {
         return false;
      }
      Set<BlockPos> generated;
      try {
         generated = this.future.join();
      } catch (RuntimeException exception) {
         this.failed = true;
         this.targets = Set.of();
         return true;
      }
      if (GenerationFailed.is(generated)) {
         this.generationConstraintsFailed = true;
         this.targets = Set.of();
         this.remaining = 0;
         this.total = 0;
         this.validationRemaining = 0;
         return true;
      }
      this.exceededLimit = generated.size() > this.maxPlacement;
      this.targets = this.exceededLimit ? Set.of() : generated;
      resolveGeneratedSmartOutline();
      this.remaining = this.exceededLimit ? 0 : generated.size();
      this.total = this.remaining;
      this.validationRemaining = this.remaining;
      prepareSmartStates();
      checkMemory();
      return true;
   }

   private void resolveGeneratedSmartOutline() {
      if (this.smartOutlineFuture == null) {
         return;
      }
      try {
         Set<BlockPos> generatedOutline = this.smartOutlineFuture.join();
         this.smartOutline = GenerationFailed.is(generatedOutline) ? Set.of() : generatedOutline;
      } catch (RuntimeException ignored) {
         this.smartOutline = Set.of();
      }
   }

   private void prepareSmartStates() {
      if (!this.smartStates.isEmpty() || this.smartStateResolver == null || this.smartOutline == null) {
         return;
      }
      Set<BlockPos> applicable = new HashSet<>(this.smartOutline);
      applicable.retainAll(this.targets);
      this.smartStates = Map.copyOf(this.smartStateResolver.apply(Set.copyOf(applicable)));
   }

   private BlockState stateAt(BlockPos pos) {
      return this.smartStates.getOrDefault(pos, this.state);
   }

   private void checkMemory() {
      if (this.memoryChecked || this.exceededLimit || this.targets == null) {
         return;
      }
      this.memoryChecked = true;
      this.memoryUnsafe = !WorldOperationMemory.canPrepare(this.targets.size());
      if (this.memoryUnsafe) {
         this.targets = Set.of();
         this.remaining = 0;
         this.validationRemaining = 0;
      }
   }

   public boolean validateSnapshots(ServerLevel level, WorldTaskBudget budget) {
      if (this.snapshotsValidated || this.failed || this.exceededLimit) {
         return true;
      }
      if (this.validationIterator == null) {
         this.validationIterator = this.targets.iterator();
      }
      while (this.validationIterator.hasNext() && budget.tryConsume()) {
         BlockPos pos = this.validationIterator.next();
         this.validationRemaining--;
         BlockState previous = level.getBlockState(pos);
         Optional<ReversibleBlockSnapshot> snapshot = ReversibleBlockSnapshot.capture(level, pos);
         if (snapshot.isEmpty()) {
            this.failed = true;
            return true;
         }
         ReversibleBlockSnapshot captured = snapshot.orElseThrow();
         this.blockEntityReserve = WorldOperationMemory.saturatingAdd(
            this.blockEntityReserve,
            WorldOperationMemory.snapshotNbtReserve(captured)
         );
         if (!WorldOperationMemory.canPrepare(this.targets.size(), this.blockEntityReserve)) {
            this.memoryUnsafe = true;
            return true;
         }
         this.expected.put(pos.immutable(), captured);
         if (previous.equals(stateAt(pos))
            || this.conflictMode == OperationConflictMode.KEEP_EXISTING && !previous.canBeReplaced()) {
            continue;
         }
      }
      if (!this.validationIterator.hasNext()) {
         this.snapshotsValidated = true;
         this.blocks = this.targets.iterator();
      }
      return this.snapshotsValidated;
   }

   public JournalPreparation prepareJournal(WorldTaskContext context) {
      if (this.journal != null || this.expected.isEmpty()) {
         return JournalPreparation.READY;
      }
      if (this.journalFuture == null) {
         beginJournalPreparation(context);
         return JournalPreparation.PENDING;
      }
      if (!this.journalFuture.isDone()) {
         return JournalPreparation.PENDING;
      }
      try {
         this.journal = this.journalFuture.join().orElse(null);
      } catch (RuntimeException exception) {
         this.failed = true;
         return JournalPreparation.FAILED;
      }
      return this.journal == null ? JournalPreparation.FAILED : JournalPreparation.READY;
   }

   private void beginJournalPreparation(WorldTaskContext context) {
      ServerLevel journalLevel = context.level(this.dimension);
      var server = context.server();
      UUID owner = context.owner();
      this.journalFuture = CompletableFuture.supplyAsync(() -> {
         List<ReversibleBlockSnapshot> journalBefore = List.copyOf(this.expected.values());
         Optional<List<ReversibleBlockSnapshot>> journalAfter = predictedPlacementAfter(
            journalLevel, journalBefore, this::stateAt, this.conflictMode
         );
         return journalAfter.flatMap(afterSnapshots -> PersistentRecoveryJournal.begin(
            server, owner, this.dimension, journalBefore, afterSnapshots
         ));
      }, PersistentRecoveryJournal.executor());
   }

   public boolean finalizeSnapshots(ServerLevel level, WorldTaskBudget budget) {
      if (this.after.isEmpty()) {
         return true;
      }
      if (this.finalizationIterator == null) {
         this.finalizationIterator = this.after.entrySet().iterator();
      }
      while (this.finalizationIterator.hasNext() && budget.tryConsume()) {
         Map.Entry<BlockPos, ReversibleBlockSnapshot> entry = this.finalizationIterator.next();
         Optional<ReversibleBlockSnapshot> actual = ReversibleBlockSnapshot.capture(level, entry.getKey());
         if (actual.isEmpty()) {
            this.failed = true;
            return true;
         }
         entry.setValue(actual.orElseThrow());
      }
      return !this.finalizationIterator.hasNext();
   }

   public JournalPreparation prepareCommit() {
      if (this.commitPreparation == null) {
         this.commitPreparation = WorldOperationCommit.begin(this.dimension, this.undo, this.after, this.journal);
      }
      return this.commitPreparation.poll();
   }

   public Optional<WorldChangeBatch> preparedBatch() {
      return this.commitPreparation == null ? Optional.empty() : this.commitPreparation.batch();
   }

   public PersistentRecoveryJournal journal() {
      return this.journal;
   }

   public boolean acquireLease(WorldTaskContext context) {
      return WorldWriteCoordinator.tryAcquire(context.server(), this.dimension, context.owner());
   }

   public void releaseLease(WorldTaskContext context) {
      WorldWriteCoordinator.release(context.server(), this.dimension, context.owner());
   }

   public void releaseAfterCancelledJournal(WorldTaskContext context) {
      WorldWriteCoordinator.releaseAfterUnusedJournal(
         context.server(), this.dimension, context.owner(), this.journal, this.journalFuture
      );
   }

   public boolean hasWrites() {
      return !this.undo.isEmpty();
   }

   public int validationRemaining() {
      return Math.max(0, this.validationRemaining);
   }

   public void cancel() {
      this.cancelled = true;
      if (this.commitPreparation != null) {
         this.commitPreparation.cancel();
      }
      discardJournalCreatedAfterCancellation();
      if (this.future != null) {
         this.future.cancel(true);
      }
      if (this.smartOutlineFuture != null && this.smartOutlineFuture != this.future) {
         this.smartOutlineFuture.cancel(true);
      }
      if (this.generationProgress != null) {
         this.generationProgress.cancel();
      }
   }

   private void discardJournalCreatedAfterCancellation() {
      if (this.journalFuture == null) {
         return;
      }
      this.journalFuture.whenComplete((created, exception) -> {
         if (this.cancelled && this.journal == null && exception == null && created != null) {
            created.ifPresent(PersistentRecoveryJournal::discardUnused);
         }
      });
   }

   public Iterator<BlockPos> blocks() {
      return this.blocks;
   }

   public void consumed() {
      this.remaining--;
   }

   public int total() {
      return this.total;
   }

   public int processed() {
      return Math.max(0, this.total - this.remaining);
   }

   public ProgressiveBlockGeneration.Snapshot generationProgress() {
      return this.generationProgress == null ? null : this.generationProgress.snapshot();
   }

   public void place(WorldTaskContext context, ServerLevel level, BlockPos pos) {
      BlockWriteResult result = setBlockWithUndo(
         context,
         level,
         pos,
         stateAt(pos),
         this.conflictMode,
         this.updateMode,
         this.undo,
         this.expected,
         this.after
      );
      if (result == BlockWriteResult.PLACED) {
         this.placed++;
      } else if (result == BlockWriteResult.FAILED) {
         this.failed = true;
      }
   }

   public int placed() {
      return this.placed;
   }

   public int maxPlacement() {
      return this.maxPlacement;
   }

   public ResourceKey<Level> dimension() {
      return this.dimension;
   }

   public boolean exceededLimit() {
      return this.exceededLimit;
   }

   public boolean generationConstraintsFailed() {
      return this.generationConstraintsFailed;
   }

   public boolean memoryUnsafe() {
      return this.memoryUnsafe;
   }

   public boolean failed() {
      return this.failed;
   }

   public ArrayDeque<ReversibleBlockSnapshot> undoChanges() {
      return this.undo;
   }

   public Map<BlockPos, ReversibleBlockSnapshot> afterChanges() {
      return this.after;
   }

   private static BlockWriteResult setBlockWithUndo(
      WorldTaskContext context,
      ServerLevel level,
      BlockPos pos,
      BlockState state,
      OperationConflictMode conflictMode,
      PlacementUpdateMode updateMode,
      ArrayDeque<ReversibleBlockSnapshot> undo,
      Map<BlockPos, ReversibleBlockSnapshot> expected,
      Map<BlockPos, ReversibleBlockSnapshot> after
   ) {
      BlockState previous = level.getBlockState(pos);
      ReversibleBlockSnapshot expectedSnapshot = expected == null ? null : expected.get(pos);
      if (expectedSnapshot != null && !expectedSnapshot.matches(level, pos)) {
         return conflictMode == OperationConflictMode.KEEP_EXISTING && !previous.canBeReplaced()
            ? BlockWriteResult.SKIPPED
            : BlockWriteResult.FAILED;
      }
      if (conflictMode == OperationConflictMode.KEEP_EXISTING && !previous.canBeReplaced()) {
         return BlockWriteResult.SKIPPED;
      }
      if (previous.equals(state)) {
         return BlockWriteResult.SKIPPED;
      }
      ReversibleBlockSnapshot before = expectedSnapshot;
      if (before == null) {
         Optional<ReversibleBlockSnapshot> snapshot = ReversibleBlockSnapshot.capture(level, pos);
         if (snapshot.isEmpty()) {
            context.actionBar(FastPlaceMessages.text("fastformer.message.block_snapshot_failed", pos.toShortString()));
            return BlockWriteResult.FAILED;
         }
         before = snapshot.orElseThrow();
      }
      // Record recovery state before callbacks in setBlock can mutate the world.
      undo.addFirst(before);
      if (WorldWriteSideEffectGuard.setBlock(level, pos, state, updateMode.flags())) {
         return captureWrittenState(level, pos, updateMode, undo, expected, after);
      }
      // A failed callback can still leave a partial world mutation.
      captureFailedWriteState(level, pos, updateMode, undo, expected, after);
      return BlockWriteResult.FAILED;
   }

   private static BlockWriteResult captureWrittenState(
      ServerLevel level,
      BlockPos pos,
      PlacementUpdateMode updateMode,
      ArrayDeque<ReversibleBlockSnapshot> undo,
      Map<BlockPos, ReversibleBlockSnapshot> expected,
      Map<BlockPos, ReversibleBlockSnapshot> after
   ) {
      if (after == null) {
         return BlockWriteResult.PLACED;
      }
      Optional<ReversibleBlockSnapshot> written = ReversibleBlockSnapshot.capture(level, pos);
      if (written.isEmpty()) {
         after.put(
            pos.immutable(),
            new ReversibleBlockSnapshot(pos, level.getBlockState(pos), level.getFluidState(pos), null)
         );
         return BlockWriteResult.FAILED;
      }
      after.put(pos.immutable(), written.orElseThrow());
      if (updateMode == PlacementUpdateMode.NORMAL
         && !ReversibleBlockSnapshot.refreshTaskOwnedNeighbors(level, pos, expected, undo, after)) {
         return BlockWriteResult.FAILED;
      }
      return BlockWriteResult.PLACED;
   }

   private static void captureFailedWriteState(
      ServerLevel level,
      BlockPos pos,
      PlacementUpdateMode updateMode,
      ArrayDeque<ReversibleBlockSnapshot> undo,
      Map<BlockPos, ReversibleBlockSnapshot> expected,
      Map<BlockPos, ReversibleBlockSnapshot> after
   ) {
      if (after == null) {
         return;
      }
      Optional<ReversibleBlockSnapshot> written = ReversibleBlockSnapshot.capture(level, pos);
      after.put(
         pos.immutable(),
         written.orElseGet(() -> new ReversibleBlockSnapshot(
            pos, level.getBlockState(pos), level.getFluidState(pos), null
         ))
      );
      if (updateMode == PlacementUpdateMode.NORMAL) {
         ReversibleBlockSnapshot.refreshTaskOwnedNeighbors(level, pos, expected, undo, after);
      }
   }

   private static Optional<List<ReversibleBlockSnapshot>> predictedPlacementAfter(
      ServerLevel level,
      List<ReversibleBlockSnapshot> before,
      Function<BlockPos, BlockState> placedStateAt,
      OperationConflictMode conflictMode
   ) {
      List<ReversibleBlockSnapshot> result = new ArrayList<>(before.size());
      try {
         for (ReversibleBlockSnapshot snapshot : before) {
            result.add(predictedSnapshot(level, snapshot, placedStateAt.apply(snapshot.pos()), conflictMode));
         }
      } catch (RuntimeException exception) {
         return Optional.empty();
      }
      return Optional.of(result);
   }

   private static ReversibleBlockSnapshot predictedSnapshot(
      ServerLevel level,
      ReversibleBlockSnapshot snapshot,
      BlockState placedState,
      OperationConflictMode conflictMode
   ) {
      if (snapshot.state().equals(placedState)
         || conflictMode == OperationConflictMode.KEEP_EXISTING && !snapshot.state().canBeReplaced()) {
         return snapshot;
      }
      BlockEntitySnapshot blockEntityData = predictedBlockEntity(level, snapshot, placedState);
      return new ReversibleBlockSnapshot(
         snapshot.pos(), placedState, placedState.getFluidState(), blockEntityData
      );
   }

   private static BlockEntitySnapshot predictedBlockEntity(
      ServerLevel level,
      ReversibleBlockSnapshot snapshot,
      BlockState placedState
   ) {
      if (!(placedState.getBlock() instanceof EntityBlock entityBlock)) {
         return null;
      }
      if (snapshot.state().getBlock() == placedState.getBlock() && snapshot.blockEntity() != null) {
         return snapshot.blockEntity();
      }
      BlockEntity blockEntity = entityBlock.newBlockEntity(snapshot.pos(), placedState);
      return blockEntity == null
         ? null
         : new BlockEntitySnapshot(blockEntity.saveWithFullMetadata(level.registryAccess()));
   }

   private enum BlockWriteResult {
      PLACED,
      SKIPPED,
      FAILED
   }
}
