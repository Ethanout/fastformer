package io.github.fastformer.fastplace.task;

import io.github.fastformer.fastplace.world.JournalPreparation;
import io.github.fastformer.fastplace.OperationConflictMode;
import io.github.fastformer.fastplace.OperationExecutionSemantics;
import io.github.fastformer.fastplace.OperationMode;
import io.github.fastformer.fastplace.OperationSelectionVolume;
import io.github.fastformer.fastplace.OperationStackRegion;
import io.github.fastformer.fastplace.world.PersistentRecoveryJournal;
import io.github.fastformer.fastplace.PlacementUpdateMode;
import io.github.fastformer.fastplace.world.ReversibleBlockSnapshot;
import io.github.fastformer.fastplace.world.WorldChangeTransaction;
import io.github.fastformer.fastplace.world.WorldOperationMemory;
import io.github.fastformer.fastplace.world.WorldOperationCommit;
import io.github.fastformer.fastplace.world.WorldOperationMetrics;
import io.github.fastformer.fastplace.world.WorldJournalPreparation;
import io.github.fastformer.fastplace.world.WorldBatchFeedback;
import io.github.fastformer.fastplace.world.WorldOperationPhase;
import io.github.fastformer.fastplace.world.MemoryAdmission;
import io.github.fastformer.fastplace.world.MemoryReservation;
import io.github.fastformer.fastplace.world.WorldTaskBudget;
import io.github.fastformer.fastplace.world.WorldTaskContext;
import io.github.fastformer.fastplace.world.WorldWriteCoordinator;
import io.github.fastformer.fastplace.world.WorldWriteSideEffectGuard;
import io.github.fastformer.fastplace.geometry.OperationGeometry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Executes the legacy server-side selection move or stack workflow. */
public final class SelectionOperationTask implements WorldOperationTask {
   private final AABB bounds;
   private final OperationSelectionVolume selection;
   private final OperationMode mode;
   private final OperationConflictMode conflictMode;
   private final boolean copy;
   private final BlockPos translation;
   private final OperationStackRegion stackRegion;
   private final PlacementUpdateMode updateMode;
   private final int maxPlacement;
   private final ResourceKey<Level> dimension;
   private final WorldOperationMetrics metrics = new WorldOperationMetrics();
   private final WorldBatchFeedback batchFeedback = new WorldBatchFeedback(this.metrics);
   private MemoryReservation memoryReservation;
   private boolean memoryThrottled;
   private final List<ReversibleBlockSnapshot> source = new ArrayList<>();
   private final WorldChangeTransaction transaction = new WorldChangeTransaction();
   private WorldOperationCommit operationCommit;
   private boolean failed;
   private final WorldJournalPreparation journalPreparation = new WorldJournalPreparation();
   private int journaledCount;
   private List<ReversibleBlockSnapshot> pendingJournalSlice = List.of();
   private final Set<BlockPos> journaledPositions = new HashSet<>();
   private boolean validateComplete;
   private PlacementTarget pendingPlacementTarget;
   private Phase phase = Phase.SCAN;
   private int x;
   private int y;
   private int z;
   private int sourceIndex;
   private int repeatX;
   private int repeatY;
   private int repeatZ;
   private Iterator<BlockPos> finalizationIterator;
   private long plannedBlocks;
   private long blockEntityReserve;
   private boolean memoryUnsafe;

   public SelectionOperationTask(
      OperationSelectionVolume selection,
      OperationMode mode,
      OperationConflictMode conflictMode,
      boolean copy,
      BlockPos translation,
      OperationStackRegion stackRegion,
      PlacementUpdateMode updateMode,
      int maxPlacement,
      ResourceKey<Level> dimension
   ) {
      this.selection = selection;
      this.bounds = selection.bounds();
      this.mode = mode;
      this.conflictMode = conflictMode;
      this.copy = copy;
      this.translation = translation;
      this.stackRegion = stackRegion;
      this.updateMode = updateMode;
      this.maxPlacement = maxPlacement;
      this.dimension = dimension;
      this.metrics.queued();
      resetCursor();
      resetPlacementCursor();
   }

   @Override
   public OperationTaskResult tick(WorldTaskContext context, ServerLevel level, WorldTaskBudget budget) {
      this.metrics.phase(phaseMetric());
      while (budget.tryConsume()) {
         if (!PersistentRecoveryJournal.writesAllowed()) {
            failed = true;
            return OperationTaskResult.FAILED;
         }
         Optional<OperationTaskResult> result = tickPhase(context, level);
         if (result.isPresent()) {
            return result.orElseThrow();
         }
      }
      return OperationTaskResult.ACTIVE;
   }

   private Optional<OperationTaskResult> tickPhase(WorldTaskContext context, ServerLevel level) {
      return switch (phase) {
         case SCAN -> scanNext(level);
         case VALIDATE -> validatePhase(level);
         case JOURNAL -> prepareJournalPhase(context);
         case CLEAR -> clearNext(level);
         case PLACE -> placePhase(level);
         case FINALIZE -> finalizePhase(level);
         case FINAL_JOURNAL -> prepareCommitPhase();
      };
   }

   private Optional<OperationTaskResult> scanNext(ServerLevel level) {
      BlockPos pos = cursorPos();
      BlockState state = level.getBlockState(pos);
      if (!state.isAir() && selection.intersects(new AABB(pos))) {
         Optional<ReversibleBlockSnapshot> captured = ReversibleBlockSnapshot.capture(level, pos);
         if (captured.isEmpty()) {
            failed = true;
            return Optional.of(OperationTaskResult.FAILED);
         }
         ReversibleBlockSnapshot snapshot = captured.orElseThrow();
         metrics.snapshotCaptured();
         source.add(snapshot);
         transaction.recordExpectedIfAbsent(pos, snapshot);
         blockEntityReserve = WorldOperationMemory.saturatingAdd(
            blockEntityReserve, WorldOperationMemory.snapshotNbtReserve(snapshot)
         );
         if (!reserveMemory(source.size(), blockEntityReserve)) {
            return Optional.of(OperationTaskResult.MEMORY_UNSAFE);
         }
      }
      if (advanceCursor()) {
         return Optional.empty();
      }
      if (source.isEmpty()) {
         return Optional.of(OperationTaskResult.EMPTY);
      }
      long copies = placementRepetitionCount();
      if (copies <= 0L || source.size() > (long)maxPlacement / copies) {
         return Optional.of(OperationTaskResult.EXCEEDED);
      }
      plannedBlocks = (long)source.size() * copies;
      metrics.targetCount((int)Math.min(Integer.MAX_VALUE, plannedBlocks));
      if (!reserveMemory(plannedBlocks, blockEntityReserve) || !reserveJournalMemory()) {
         return Optional.of(OperationTaskResult.MEMORY_UNSAFE);
      }
      phase = Phase.JOURNAL;
      resetPlacementCursor();
      return Optional.empty();
   }

   private Optional<OperationTaskResult> validatePhase(ServerLevel level) {
      if (validateNext(level)) {
         if (unjournaledCount() >= PersistentRecoveryJournal.segmentBatchSize(journaledCount, unjournaledCount())) {
            phase = Phase.JOURNAL;
         }
         return Optional.empty();
      }
      if (memoryUnsafe) {
         return Optional.of(OperationTaskResult.MEMORY_UNSAFE);
      }
      if (failed) {
         return Optional.of(OperationTaskResult.FAILED);
      }
      if (!reserveJournalMemory()) {
         return Optional.of(OperationTaskResult.MEMORY_UNSAFE);
      }
      validateComplete = true;
      if (unjournaledCount() > 0) {
         phase = Phase.JOURNAL;
      } else {
         phase = Phase.PLACE;
         resetPlacementCursor();
      }
      return Optional.empty();
   }

   private Optional<OperationTaskResult> prepareJournalPhase(WorldTaskContext context) {
      JournalPreparation preparation = prepareJournal(context);
      if (preparation == JournalPreparation.PENDING) {
         return Optional.of(OperationTaskResult.ACTIVE);
      }
      if (preparation == JournalPreparation.FAILED) {
         return Optional.of(OperationTaskResult.JOURNAL_FAILED);
      }
      if (clearsSource() && sourceIndex < source.size()) {
         phase = Phase.CLEAR;
      } else if (!validateComplete) {
         phase = Phase.VALIDATE;
      } else {
         phase = Phase.PLACE;
      }
      return Optional.empty();
   }

   private Optional<OperationTaskResult> clearNext(ServerLevel level) {
      if (sourceIndex >= source.size()) {
         sourceIndex = 0;
         phase = validateComplete ? Phase.PLACE : Phase.VALIDATE;
         if (phase == Phase.PLACE) {
            resetPlacementCursor();
         }
         return Optional.empty();
      }
      ReversibleBlockSnapshot sourceBlock = source.get(sourceIndex);
      if (!journaledPositions.contains(sourceBlock.pos())) {
         phase = Phase.JOURNAL;
         return Optional.empty();
      }
      sourceIndex++;
      return setBlock(level, sourceBlock.pos(), Blocks.AIR.defaultBlockState())
         ? Optional.empty()
         : Optional.of(OperationTaskResult.FAILED);
   }

   private Optional<OperationTaskResult> placePhase(ServerLevel level) {
      if (placeNext(level)) {
         return Optional.empty();
      }
      if (failed) {
         return Optional.of(OperationTaskResult.FAILED);
      }
      if (phase == Phase.JOURNAL || phase == Phase.VALIDATE) {
         return Optional.empty();
      }
      phase = Phase.FINALIZE;
      return Optional.empty();
   }

   private Optional<OperationTaskResult> finalizePhase(ServerLevel level) {
      if (finalizeNext(level)) {
         return Optional.empty();
      }
      if (failed) {
         return Optional.of(OperationTaskResult.FAILED);
      }
      releaseWriteStagingForCommit();
      phase = Phase.FINAL_JOURNAL;
      return Optional.empty();
   }

   private Optional<OperationTaskResult> prepareCommitPhase() {
      JournalPreparation finalJournal = prepareCommit();
      if (finalJournal == JournalPreparation.PENDING) {
         return Optional.of(OperationTaskResult.ACTIVE);
      }
      return Optional.of(finalJournal == JournalPreparation.READY
         ? OperationTaskResult.COMPLETE
         : OperationTaskResult.FAILED);
   }

   private boolean placeNext(ServerLevel level) {
      PlacementTarget target = nextBufferedPlacementTarget();
      if (target == null) {
         return false;
      }
      if (!journaledPositions.contains(target.pos())) {
         pendingPlacementTarget = target;
         phase = unjournaledCount() > 0 || !validateComplete ? (validateComplete ? Phase.JOURNAL : Phase.VALIDATE) : Phase.JOURNAL;
         return false;
      }
      place(level, target.pos(), target.source());
      return !failed;
   }

   private PlacementTarget nextBufferedPlacementTarget() {
      if (pendingPlacementTarget != null) {
         PlacementTarget target = pendingPlacementTarget;
         pendingPlacementTarget = null;
         return target;
      }
      return nextPlacementTarget();
   }

   private int unjournaledCount() {
      return transaction.expectedCount() - journaledCount - pendingJournalSlice.size();
   }

   private boolean finalizeNext(ServerLevel level) {
      if (finalizationIterator == null) {
         finalizationIterator = transaction.afterPositions();
      }
      if (!finalizationIterator.hasNext()) {
         return false;
      }
      BlockPos position = finalizationIterator.next();
      Optional<ReversibleBlockSnapshot> actual = ReversibleBlockSnapshot.capture(level, position);
      if (actual.isEmpty()) {
         failed = true;
         return false;
      }
      transaction.recordAfter(position, actual.orElseThrow());
      return true;
   }

   private boolean validateNext(ServerLevel level) {
      PlacementTarget target = nextPlacementTarget();
      if (target == null) {
         return false;
      }
      Optional<ReversibleBlockSnapshot> captured = ReversibleBlockSnapshot.capture(level, target.pos());
      if (captured.isEmpty()) {
         failed = true;
         return false;
      }
      ReversibleBlockSnapshot snapshot = captured.orElseThrow();
      metrics.snapshotCaptured();
      if (transaction.recordExpectedIfAbsent(target.pos(), snapshot) == null) {
         blockEntityReserve = WorldOperationMemory.saturatingAdd(
            blockEntityReserve, WorldOperationMemory.snapshotNbtReserve(snapshot)
         );
         if (!reserveMemory(plannedBlocks, blockEntityReserve)) {
            memoryUnsafe = true;
            return false;
         }
      }
      return true;
   }

   private PlacementTarget nextPlacementTarget() {
      while (repeatX <= stackRegion.max().getX()) {
         boolean skippedOrigin = skipOrigin() && repeatX == 0 && repeatY == 0 && repeatZ == 0;
         if (skippedOrigin) {
            if (!advanceRepetition()) {
               return null;
            }
            continue;
         }
         ReversibleBlockSnapshot sourceBlock = source.get(sourceIndex++);
         BlockPos repetition = new BlockPos(repeatX, repeatY, repeatZ);
         BlockPos target = sourceBlock.pos().offset(
            OperationGeometry.stackDisplacement(bounds, repetition).offset(translation)
         );
         if (sourceIndex >= source.size()) {
            sourceIndex = 0;
            advanceRepetition();
         }
         return new PlacementTarget(target, sourceBlock);
      }
      return null;
   }

   private void resetPlacementCursor() {
      sourceIndex = 0;
      repeatX = stackRegion.min().getX();
      repeatY = stackRegion.min().getY();
      repeatZ = stackRegion.min().getZ();
   }

   private boolean advanceRepetition() {
      if (++repeatZ <= stackRegion.max().getZ()) {
         return true;
      }
      repeatZ = stackRegion.min().getZ();
      if (++repeatY <= stackRegion.max().getY()) {
         return true;
      }
      repeatY = stackRegion.min().getY();
      return ++repeatX <= stackRegion.max().getX();
   }

   private boolean skipOrigin() {
      return mode == OperationMode.STACK && translation.equals(BlockPos.ZERO);
   }

   private boolean clearsSource() {
      return OperationExecutionSemantics.clearsSource(mode, translation, copy);
   }

   private long placementRepetitionCount() {
      long count = stackRegion.cellCount();
      return skipOrigin() ? Math.max(0L, count - 1L) : count;
   }

   private void place(ServerLevel level, BlockPos pos, ReversibleBlockSnapshot sourceBlock) {
      if (!clearsSource()) {
         ReversibleBlockSnapshot sourceExpected = transaction.expectedAt(sourceBlock.pos());
         if (sourceExpected == null || !sourceExpected.matches(level, sourceBlock.pos())) {
            failed = true;
            return;
         }
      }
      BlockState current = level.getBlockState(pos);
      if (conflictMode == OperationConflictMode.KEEP_EXISTING && !current.canBeReplaced()) {
         return;
      }
      ReversibleBlockSnapshot expectedSnapshot = transaction.expectedAt(pos);
      if (expectedSnapshot != null && !expectedSnapshot.matches(level, pos)) {
         failed = true;
         return;
      }
      if (conflictMode != OperationConflictMode.REPLACE && !current.canBeReplaced()) {
         return;
      }
      ReversibleBlockSnapshot before = expectedSnapshot;
      if (before == null) {
         Optional<ReversibleBlockSnapshot> captured = ReversibleBlockSnapshot.capture(level, pos);
         if (captured.isEmpty()) {
            failed = true;
            return;
         }
         before = captured.orElseThrow();
      }
      transaction.recordBefore(before);
      transaction.recordExpectedIfAbsent(pos, before);
      if (!sourceBlock.placeAt(level, pos, updateMode.flags())) {
         metrics.writeAttempt(false);
         recordFailedWrite(level, pos);
         return;
      }
      metrics.writeAttempt(true);
      Optional<ReversibleBlockSnapshot> written = ReversibleBlockSnapshot.capture(level, pos);
      if (written.isEmpty()) {
         ReversibleBlockSnapshot fallback = currentSnapshot(level, pos);
         transaction.recordAfter(pos, fallback);
         failed = true;
         return;
      }
      transaction.recordAfter(pos, written.orElseThrow());
      if (updateMode == PlacementUpdateMode.NORMAL) {
         ReversibleBlockSnapshot.refreshTaskOwnedNeighbors(level, pos, transaction);
      }
   }

   private boolean setBlock(ServerLevel level, BlockPos pos, BlockState state) {
      BlockState previous = level.getBlockState(pos);
      ReversibleBlockSnapshot expectedSnapshot = transaction.expectedAt(pos);
      if (expectedSnapshot != null && !expectedSnapshot.matches(level, pos)) {
         failed = true;
         return false;
      }
      if (previous.equals(state)) {
         Optional<ReversibleBlockSnapshot> captured = ReversibleBlockSnapshot.capture(level, pos);
         if (captured.isEmpty()) {
            failed = true;
            return false;
         }
         ReversibleBlockSnapshot snapshot = captured.orElseThrow();
         transaction.recordExpectedIfAbsent(pos, snapshot);
         transaction.recordAfter(pos, snapshot);
         return true;
      }
      ReversibleBlockSnapshot before = expectedSnapshot;
      if (before == null) {
         Optional<ReversibleBlockSnapshot> captured = ReversibleBlockSnapshot.capture(level, pos);
         if (captured.isEmpty()) {
            failed = true;
            return false;
         }
         before = captured.orElseThrow();
      }
      transaction.recordBefore(before);
      transaction.recordExpectedIfAbsent(pos, before);
      if (!WorldWriteSideEffectGuard.setBlock(level, pos, state, updateMode.flags())) {
         metrics.writeAttempt(false);
         recordFailedWrite(level, pos);
         return false;
      }
      metrics.writeAttempt(true);
      Optional<ReversibleBlockSnapshot> written = ReversibleBlockSnapshot.capture(level, pos);
      if (written.isEmpty()) {
         ReversibleBlockSnapshot fallback = currentSnapshot(level, pos);
         transaction.recordAfter(pos, fallback);
         failed = true;
         return false;
      }
      transaction.recordAfter(pos, written.orElseThrow());
      if (updateMode == PlacementUpdateMode.NORMAL) {
         ReversibleBlockSnapshot.refreshTaskOwnedNeighbors(level, pos, transaction);
      }
      return true;
   }

   private void recordFailedWrite(ServerLevel level, BlockPos pos) {
      transaction.recordAfter(
         pos,
         ReversibleBlockSnapshot.capture(level, pos).orElseGet(() -> currentSnapshot(level, pos))
      );
      if (updateMode == PlacementUpdateMode.NORMAL) {
         ReversibleBlockSnapshot.refreshTaskOwnedNeighbors(level, pos, transaction);
      }
      failed = true;
   }

   private static ReversibleBlockSnapshot currentSnapshot(ServerLevel level, BlockPos pos) {
      return new ReversibleBlockSnapshot(pos, level.getBlockState(pos), level.getFluidState(pos), null);
   }

   private BlockPos cursorPos() {
      return new BlockPos(x, y, z);
   }

   private void resetCursor() {
      x = Mth.floor(bounds.minX);
      y = Mth.floor(bounds.minY);
      z = Mth.floor(bounds.minZ);
   }

   private boolean advanceCursor() {
      if (++z < Mth.ceil(bounds.maxZ)) {
         return true;
      }
      z = Mth.floor(bounds.minZ);
      if (++y < Mth.ceil(bounds.maxY)) {
         return true;
      }
      y = Mth.floor(bounds.minY);
      if (++x < Mth.ceil(bounds.maxX)) {
         return true;
      }
      resetCursor();
      return false;
   }

   @Override
   public String phaseName() {
      return switch (phase) {
         case SCAN -> "扫描";
         case VALIDATE -> "验证快照";
         case JOURNAL -> "写入安全日志";
         case CLEAR -> "清空";
         case PLACE -> "写入";
         case FINALIZE -> "确认最终状态";
         case FINAL_JOURNAL -> "压缩最终恢复状态";
      };
   }

   private JournalPreparation prepareJournal(WorldTaskContext context) {
      if (pendingJournalSlice.isEmpty()) {
         int from = journaledCount;
         int remaining = transaction.expectedCount() - from;
         if (remaining <= 0) {
            return JournalPreparation.READY;
         }
         int size = PersistentRecoveryJournal.segmentBatchSize(from, remaining);
         pendingJournalSlice = transaction.expectedRange(from, from + size);
      }
      List<ReversibleBlockSnapshot> slice = pendingJournalSlice;
      Optional<JournalSnapshots> snapshots = journalSnapshots(slice);
      if (snapshots.isEmpty() || snapshots.orElseThrow().before().isEmpty()) {
         return JournalPreparation.FAILED;
      }
      JournalSnapshots prepared = snapshots.orElseThrow();
      JournalPreparation preparation = journalPreparation.journal() == null
         ? journalPreparation.poll(() -> PersistentRecoveryJournal.begin(
            context.server(), context.owner(), dimension, prepared.before(), prepared.after(), operationId()
         ))
         : journalPreparation.pollAppend(() ->
            journalPreparation.journal().appendSegment(prepared.before(), prepared.after())
         );
      if (preparation == JournalPreparation.READY) {
         metrics.journalReady();
         for (ReversibleBlockSnapshot snapshot : slice) {
            journaledPositions.add(snapshot.pos());
         }
         journaledCount += slice.size();
         pendingJournalSlice = List.of();
      }
      return preparation;
   }

   private Optional<JournalSnapshots> journalSnapshots(Collection<ReversibleBlockSnapshot> originals) {
      SelectionJournalPrediction prediction = new SelectionJournalPrediction(
         source, transaction::expectedAt, conflictMode
      );
      try {
         predictClearedSource(prediction);
         predictTargets(prediction);
      } catch (RuntimeException exception) {
         return Optional.empty();
      }
      return Optional.of(new JournalSnapshots(originals, prediction.snapshots(originals)));
   }

   private void predictClearedSource(SelectionJournalPrediction prediction) {
      if (!clearsSource()) {
         return;
      }
      for (ReversibleBlockSnapshot sourceSnapshot : source) {
         prediction.clear(sourceSnapshot.pos());
      }
   }

   private void predictTargets(SelectionJournalPrediction prediction) {
      for (BlockPos repetition : stackRegion.repetitions(maxPlacement)) {
         if (skipOrigin() && repetition.equals(BlockPos.ZERO)) {
            continue;
         }
         BlockPos displacement = OperationGeometry.stackDisplacement(bounds, repetition).offset(translation);
         for (int sourceIndex = 0; sourceIndex < source.size(); sourceIndex++) {
            ReversibleBlockSnapshot sourceSnapshot = source.get(sourceIndex);
            prediction.place(sourceSnapshot.pos().offset(displacement), sourceIndex);
         }
      }
   }

   private JournalPreparation prepareCommit() {
      if (operationCommit == null) {
         if (!reserveCommitMemory()) {
            return JournalPreparation.PENDING;
         }
         operationCommit = WorldOperationCommit.begin(dimension, transaction, journalPreparation.journal());
      }
      return operationCommit.poll();
   }

   private void releaseWriteStagingForCommit() {
      source.clear();
      transaction.clearExpected();
      sourceIndex = 0;
      pendingJournalSlice = List.of();
      journaledPositions.clear();
      pendingPlacementTarget = null;
   }

   private boolean reserveCommitMemory() {
      var admission = WorldOperationMemory.commitAdmission(
         transaction.snapshotCount(), transaction.largestSideCount(), transaction.commitBlockEntityReserve()
      );
      return reserveMemory(admission);
   }

   @Override
   public void cancelJournalPreparation() {
      journalPreparation.cancel();
   }

   @Override
   public PersistentRecoveryJournal journal() {
      return journalPreparation.journal();
   }

   @Override
   public boolean acquireLease(WorldTaskContext context) {
      boolean acquired = WorldWriteCoordinator.tryAcquire(context.server(), dimension, context.owner());
      if (acquired) {
         this.metrics.leaseAcquired();
      }
      return acquired;
   }

   @Override
   public void releaseLease(WorldTaskContext context) {
      WorldWriteCoordinator.release(context.server(), dimension, context.owner());
   }

   @Override
   public void releaseAfterCancelledJournal(WorldTaskContext context) {
      if (operationCommit != null) {
         operationCommit.cancel();
      }
      journalPreparation.releaseAfterCancellation(context, dimension);
   }

   @Override
   public WorldChangeTransaction transaction() {
      return transaction;
   }

   @Override
   public WorldOperationCommit operationCommit() {
      return operationCommit;
   }

   @Override
   public ResourceKey<Level> dimension() {
      return dimension;
   }

   @Override
   public UUID operationId() {
      return metrics.operationId();
   }

   @Override
   public String metricsSummary() {
      return metrics.summary();
   }

   @Override
   public void markWorldUnloaded() {
      metrics.phase(WorldOperationPhase.WORLD_UNLOADED);
   }

   @Override
   public void markComplete() {
      metrics.complete();
   }

   @Override
   public boolean memoryThrottled() {
      return memoryThrottled;
   }

   @Override
   public boolean ensureMemoryReservation() {
      if (memoryReservation != null) {
         return true;
      }
      long blocks = plannedBlocks > 0L ? plannedBlocks : source.size();
      if (blocks <= 0L) {
         return true;
      }
      if (phase == Phase.FINAL_JOURNAL) {
         return reserveCommitMemory();
      }
      return phase == Phase.JOURNAL ? reserveJournalMemory() : reserveMemory(blocks, blockEntityReserve);
   }

   @Override
   public int previousBatchCells() {
      return batchFeedback.cells();
   }

   @Override
   public long previousBatchNanos() {
      return batchFeedback.elapsedNanos();
   }

   @Override
   public void recordBatch(int cells, long elapsedNanos) {
      batchFeedback.record(cells, elapsedNanos);
   }

   @Override
   public void releaseMemoryReservation() {
      if (memoryReservation != null) {
         MemoryReservation reservation = memoryReservation;
         memoryReservation = null;
         CompletableFuture<?> publicationCompletion = operationCommit == null
            ? CompletableFuture.completedFuture(null)
            : operationCommit.completion();
         journalPreparation.releaseWhenIdle(reservation, publicationCompletion);
      }
   }

   @Override
   public void releaseCommittedTransactionState() {
      source.clear();
      transaction.releaseWriteState();
      operationCommit = null;
      finalizationIterator = null;
   }

   private boolean reserveMemory(long blocks, long additionalBytes) {
      var admission = WorldOperationMemory.snapshotAdmission(blocks, additionalBytes);
      return reserveMemory(admission);
   }

   private boolean reserveJournalMemory() {
      long blocks = plannedBlocks > 0L ? plannedBlocks : source.size();
      return reserveMemory(WorldOperationMemory.journalAdmission(blocks, blockEntityReserve));
   }

   private boolean reserveMemory(MemoryAdmission admission) {
      if (!admission.allowed()) {
         return false;
      }
      memoryThrottled = admission.throttled();
      if (memoryReservation == null) {
         memoryReservation = WorldOperationMemory.reserve(admission).orElse(null);
         return memoryReservation != null;
      }
      return memoryReservation.resize(admission.requestedBytes(), admission.usableBytes());
   }

   private WorldOperationPhase phaseMetric() {
      return switch (phase) {
         case SCAN -> WorldOperationPhase.GENERATION;
         case VALIDATE -> WorldOperationPhase.SNAPSHOT;
         case JOURNAL -> WorldOperationPhase.JOURNAL;
         case CLEAR, PLACE -> WorldOperationPhase.WRITE;
         case FINALIZE -> WorldOperationPhase.FINALIZE;
         case FINAL_JOURNAL -> WorldOperationPhase.COMMIT;
      };
   }

   private enum Phase {
      SCAN,
      VALIDATE,
      JOURNAL,
      CLEAR,
      PLACE,
      FINALIZE,
      FINAL_JOURNAL
   }

   private record PlacementTarget(BlockPos pos, ReversibleBlockSnapshot source) {
   }

   private record JournalSnapshots(
      Collection<ReversibleBlockSnapshot> before,
      Collection<ReversibleBlockSnapshot> after
   ) {
   }
}
