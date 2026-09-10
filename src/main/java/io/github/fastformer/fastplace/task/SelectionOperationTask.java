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
import java.util.ArrayDeque;
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
   private final Set<BlockPos> overlappingPlacementPositions = new HashSet<>();
   private boolean validateComplete;
   private final ArrayDeque<PlacementTarget> pendingTargets = new ArrayDeque<>();
   private Phase phase = Phase.SCAN;
   private int x;
   private int y;
   private int z;
   private int clearSourceIndex;
   private int placementSourceIndex;
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
      while (budget.tryConsume() || (firstWriteReady() && budget.tryConsumeFirstWrite())) {
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

   private boolean firstWriteReady() {
      if (hasWrites()) {
         return false;
      }
      if (phase == Phase.PLACE) {
         PlacementTarget target = pendingTargets.peekFirst();
         return target != null && journaledPositions.contains(target.pos());
      }
      return phase == Phase.CLEAR && clearSourceIndex < source.size()
         && journaledPositions.contains(source.get(clearSourceIndex).pos())
         && !overlappingPlacementPositions.contains(source.get(clearSourceIndex).pos());
   }

   @Override
   public CompletableFuture<Void> journalCompletion() {
      return journalPreparation.completion();
   }

   private Optional<OperationTaskResult> tickPhase(WorldTaskContext context, ServerLevel level) {
      return switch (phase) {
         case SCAN -> scanNext(level);
         case SCAN_COMPLETE -> finishScan();
         case VALIDATE -> validatePhase(level);
         case JOURNAL -> prepareJournalPhase(context);
         case CLEAR -> clearNext(level);
         case PLACE -> placePhase(level);
         case FINALIZE -> finalizePhase(level);
         case FINAL_JOURNAL -> prepareCommitPhase();
      };
   }

   private Optional<OperationTaskResult> scanNext(ServerLevel level) {
      MemoryReservationAttempt currentReservation = reserveMemory(source.size(), blockEntityReserve);
      if (currentReservation == MemoryReservationAttempt.REJECTED) {
         return Optional.of(OperationTaskResult.MEMORY_UNSAFE);
      }
      if (currentReservation == MemoryReservationAttempt.RETRY) {
         return Optional.of(OperationTaskResult.ACTIVE);
      }
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
         if (clearsSource()) {
            transaction.recordExpectedIfAbsent(pos, snapshot);
         }
         blockEntityReserve = WorldOperationMemory.saturatingAdd(
            blockEntityReserve, WorldOperationMemory.snapshotNbtReserve(snapshot)
         );
         MemoryReservationAttempt capturedReservation = reserveMemory(source.size(), blockEntityReserve);
         if (!advanceCursor()) {
            phase = Phase.SCAN_COMPLETE;
         }
         if (capturedReservation == MemoryReservationAttempt.REJECTED) {
            return Optional.of(OperationTaskResult.MEMORY_UNSAFE);
         }
         if (capturedReservation == MemoryReservationAttempt.RETRY) {
            return Optional.of(OperationTaskResult.ACTIVE);
         }
      } else {
         if (!advanceCursor()) {
            phase = Phase.SCAN_COMPLETE;
         }
      }
      if (phase == Phase.SCAN) {
         return Optional.empty();
      }
      return finishScan();
   }

   private Optional<OperationTaskResult> finishScan() {
      if (source.isEmpty()) {
         return Optional.of(OperationTaskResult.EMPTY);
      }
      long copies = placementRepetitionCount();
      if (copies <= 0L || source.size() > (long)maxPlacement / copies) {
         return Optional.of(OperationTaskResult.EXCEEDED);
      }
      plannedBlocks = (long)source.size() * copies;
      metrics.targetCount((int)Math.min(Integer.MAX_VALUE, plannedBlocks));
      MemoryReservationAttempt snapshotReservation = reserveMemory(plannedBlocks, blockEntityReserve);
      MemoryReservationAttempt journalReservation = snapshotReservation == MemoryReservationAttempt.ACQUIRED
         ? reserveJournalMemory()
         : snapshotReservation;
      if (journalReservation == MemoryReservationAttempt.REJECTED) {
         return Optional.of(OperationTaskResult.MEMORY_UNSAFE);
      }
      if (journalReservation == MemoryReservationAttempt.RETRY) {
         return Optional.of(OperationTaskResult.ACTIVE);
      }
      indexOverlappingPlacementPositions();
      phase = clearsSource() ? Phase.JOURNAL : Phase.VALIDATE;
      resetPlacementCursor();
      return Optional.empty();
   }

   private Optional<OperationTaskResult> validatePhase(ServerLevel level) {
      MemoryReservationAttempt currentReservation = reserveMemory(plannedBlocks, blockEntityReserve);
      if (currentReservation == MemoryReservationAttempt.REJECTED) {
         return Optional.of(OperationTaskResult.MEMORY_UNSAFE);
      }
      if (currentReservation == MemoryReservationAttempt.RETRY) {
         return Optional.of(OperationTaskResult.ACTIVE);
      }
      if (pendingTargets.size() >= PersistentRecoveryJournal.segmentCapacity(journaledCount)) {
         phase = unjournaledCount() > 0 ? Phase.JOURNAL : Phase.PLACE;
         return Optional.empty();
      }
      ValidationStep validation = validateNext(level);
      if (validation == ValidationStep.ADVANCED) {
         if (unjournaledCount() >= PersistentRecoveryJournal.segmentCapacity(journaledCount)) {
            phase = Phase.JOURNAL;
         }
         return Optional.empty();
      }
      if (validation == ValidationStep.RETRY) {
         return Optional.of(OperationTaskResult.ACTIVE);
      }
      if (validation == ValidationStep.REJECTED) {
         return Optional.of(OperationTaskResult.MEMORY_UNSAFE);
      }
      if (validation == ValidationStep.FAILED) {
         return Optional.of(OperationTaskResult.FAILED);
      }
      MemoryReservationAttempt journalReservation = reserveJournalMemory();
      if (journalReservation == MemoryReservationAttempt.REJECTED) {
         return Optional.of(OperationTaskResult.MEMORY_UNSAFE);
      }
      if (journalReservation == MemoryReservationAttempt.RETRY) {
         return Optional.of(OperationTaskResult.ACTIVE);
      }
      validateComplete = true;
      if (unjournaledCount() > 0) {
         phase = Phase.JOURNAL;
      } else {
         phase = Phase.PLACE;
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
      if (clearsSource() && clearSourceIndex < source.size()) {
         phase = Phase.CLEAR;
      } else {
         phase = Phase.PLACE;
      }
      return Optional.empty();
   }

   private Optional<OperationTaskResult> clearNext(ServerLevel level) {
      if (clearSourceIndex >= source.size()) {
         phase = Phase.VALIDATE;
         return Optional.empty();
      }
      ReversibleBlockSnapshot sourceBlock = source.get(clearSourceIndex);
      if (!journaledPositions.contains(sourceBlock.pos())) {
         phase = Phase.JOURNAL;
         return Optional.empty();
      }
      clearSourceIndex++;
      if (overlappingPlacementPositions.contains(sourceBlock.pos())) {
         ReversibleBlockSnapshot expected = expectedCurrent(sourceBlock.pos());
         if (expected == null || !expected.matches(level, sourceBlock.pos())) {
            failed = true;
            return Optional.of(OperationTaskResult.FAILED);
         }
         return Optional.empty();
      }
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
      phase = validateComplete ? Phase.FINALIZE : Phase.VALIDATE;
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
      if (finalJournal == JournalPreparation.FAILED && memoryUnsafe) {
         return Optional.of(OperationTaskResult.MEMORY_UNSAFE);
      }
      return Optional.of(finalJournal == JournalPreparation.READY
         ? OperationTaskResult.COMPLETE
         : OperationTaskResult.FAILED);
   }

   private boolean placeNext(ServerLevel level) {
      PlacementTarget target = pendingTargets.peekFirst();
      if (target == null) {
         return false;
      }
      if (!journaledPositions.contains(target.pos())) {
         phase = Phase.JOURNAL;
         return false;
      }
      pendingTargets.removeFirst();
      place(level, target.pos(), target.source());
      return !failed;
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

   private ValidationStep validateNext(ServerLevel level) {
      PlacementTarget target = nextPlacementTarget();
      if (target == null) {
         return ValidationStep.COMPLETE;
      }
      Optional<ReversibleBlockSnapshot> captured = ReversibleBlockSnapshot.capture(level, target.pos());
      if (captured.isEmpty()) {
         failed = true;
         return ValidationStep.FAILED;
      }
      ReversibleBlockSnapshot snapshot = captured.orElseThrow();
      metrics.snapshotCaptured();
      pendingTargets.addLast(target);
      if (transaction.recordExpectedIfAbsent(target.pos(), snapshot) == null) {
         blockEntityReserve = WorldOperationMemory.saturatingAdd(
            blockEntityReserve, WorldOperationMemory.snapshotNbtReserve(snapshot)
         );
         MemoryReservationAttempt reservation = reserveMemory(plannedBlocks, blockEntityReserve);
         if (reservation == MemoryReservationAttempt.RETRY) {
            return ValidationStep.RETRY;
         }
         if (reservation == MemoryReservationAttempt.REJECTED) {
            return ValidationStep.REJECTED;
         }
      }
      return ValidationStep.ADVANCED;
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
         ReversibleBlockSnapshot sourceBlock = source.get(placementSourceIndex++);
         BlockPos repetition = new BlockPos(repeatX, repeatY, repeatZ);
         BlockPos target = sourceBlock.pos().offset(
            OperationGeometry.stackDisplacement(bounds, repetition).offset(translation)
         );
         if (placementSourceIndex >= source.size()) {
            placementSourceIndex = 0;
            advanceRepetition();
         }
         return new PlacementTarget(target, sourceBlock);
      }
      return null;
   }

   private void resetPlacementCursor() {
      placementSourceIndex = 0;
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

   private void indexOverlappingPlacementPositions() {
      if (!clearsSource()) {
         return;
      }
      for (BlockPos repetition : stackRegion.repetitions(maxPlacement)) {
         if (skipOrigin() && repetition.equals(BlockPos.ZERO)) {
            continue;
         }
         BlockPos displacement = OperationGeometry.stackDisplacement(bounds, repetition).offset(translation);
         for (ReversibleBlockSnapshot sourceSnapshot : source) {
            BlockPos target = sourceSnapshot.pos().offset(displacement);
            if (transaction.expectedAt(target) != null) {
               overlappingPlacementPositions.add(target);
            }
         }
      }
   }

   private void place(ServerLevel level, BlockPos pos, ReversibleBlockSnapshot sourceBlock) {
      if (!clearsSource()) {
         ReversibleBlockSnapshot sourceExpected = expectedCurrent(sourceBlock.pos());
         if (sourceExpected == null) {
            sourceExpected = sourceBlock;
         }
         if (!sourceExpected.matches(level, sourceBlock.pos())) {
            failed = true;
            return;
         }
      }
      BlockState current = level.getBlockState(pos);
      ReversibleBlockSnapshot expectedSnapshot = expectedCurrent(pos);
      if (expectedSnapshot != null && !expectedSnapshot.matches(level, pos)) {
         failed = true;
         return;
      }
      boolean movingSource = overlappingPlacementPositions.contains(pos) && transaction.afterAt(pos) == null;
      if (conflictMode != OperationConflictMode.REPLACE && !movingSource && !current.canBeReplaced()) {
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

   private ReversibleBlockSnapshot expectedCurrent(BlockPos pos) {
      ReversibleBlockSnapshot written = transaction.afterAt(pos);
      return written == null ? transaction.expectedAt(pos) : written;
   }

   private boolean setBlock(ServerLevel level, BlockPos pos, BlockState state) {
      BlockState previous = level.getBlockState(pos);
      ReversibleBlockSnapshot expectedSnapshot = expectedCurrent(pos);
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
         case SCAN, SCAN_COMPLETE -> "扫描";
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
      JournalPreparation preparation = journalPreparation.journal() == null
         ? journalPreparation.poll(() -> journalSnapshots(slice).flatMap(prepared -> PersistentRecoveryJournal.begin(
            context.server(), context.owner(), dimension, prepared.before(), prepared.after(), operationId()
         )), context)
         : journalPreparation.pollAppend(() -> journalSnapshots(slice)
            .map(prepared -> journalPreparation.journal().appendSegment(prepared.before(), prepared.after()))
            .orElse(false));
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
      java.util.Set<BlockPos> positions = new java.util.HashSet<>();
      for (ReversibleBlockSnapshot original : originals) {
         positions.add(original.pos());
      }
      SelectionJournalPrediction prediction = new SelectionJournalPrediction(
         source, transaction::expectedAt, conflictMode
      );
      try {
         predictClearedSource(prediction, positions);
         predictTargets(prediction, positions);
      } catch (RuntimeException exception) {
         return Optional.empty();
      }
      return Optional.of(new JournalSnapshots(originals, prediction.snapshots(originals)));
   }

   private void predictClearedSource(SelectionJournalPrediction prediction, java.util.Set<BlockPos> positions) {
      if (!clearsSource()) {
         return;
      }
      for (ReversibleBlockSnapshot sourceSnapshot : source) {
         if (positions.contains(sourceSnapshot.pos())) {
            prediction.clear(sourceSnapshot.pos());
         }
      }
   }

   private void predictTargets(SelectionJournalPrediction prediction, java.util.Set<BlockPos> positions) {
      for (BlockPos repetition : stackRegion.repetitions(maxPlacement)) {
         if (skipOrigin() && repetition.equals(BlockPos.ZERO)) {
            continue;
         }
         BlockPos displacement = OperationGeometry.stackDisplacement(bounds, repetition).offset(translation);
         for (int sourceIndex = 0; sourceIndex < source.size(); sourceIndex++) {
            ReversibleBlockSnapshot sourceSnapshot = source.get(sourceIndex);
            BlockPos target = sourceSnapshot.pos().offset(displacement);
            if (positions.contains(target)) {
               prediction.place(target, sourceIndex);
            }
         }
      }
   }

   private JournalPreparation prepareCommit() {
      if (operationCommit == null) {
         MemoryReservationAttempt commitReservation = reserveCommitMemory();
         if (commitReservation == MemoryReservationAttempt.RETRY) {
            return JournalPreparation.PENDING;
         }
         if (commitReservation == MemoryReservationAttempt.REJECTED) {
            memoryUnsafe = true;
            return JournalPreparation.FAILED;
         }
         operationCommit = WorldOperationCommit.begin(dimension, transaction, journalPreparation.journal());
      }
      return operationCommit.poll();
   }

   private void releaseWriteStagingForCommit() {
      source.clear();
      transaction.clearExpected();
      clearSourceIndex = 0;
      placementSourceIndex = 0;
      pendingJournalSlice = List.of();
      journaledPositions.clear();
      overlappingPlacementPositions.clear();
      pendingTargets.clear();
   }

   private MemoryReservationAttempt reserveCommitMemory() {
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
   public MemoryReservationAttempt reserveWorkingSet() {
      if (memoryReservation != null) {
         return MemoryReservationAttempt.ACQUIRED;
      }
      long blocks = plannedBlocks > 0L ? plannedBlocks : source.size();
      if (blocks <= 0L) {
         return MemoryReservationAttempt.ACQUIRED;
      }
      if (phase == Phase.FINAL_JOURNAL) {
         return reserveCommitMemory();
      }
      return phase == Phase.JOURNAL
         ? reserveJournalMemory()
         : reserveMemory(blocks, blockEntityReserve);
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

   private MemoryReservationAttempt reserveMemory(long blocks, long additionalBytes) {
      var admission = WorldOperationMemory.snapshotAdmission(blocks, additionalBytes);
      return reserveMemory(admission);
   }

   private MemoryReservationAttempt reserveJournalMemory() {
      long blocks = plannedBlocks > 0L ? plannedBlocks : source.size();
      return reserveMemory(WorldOperationMemory.journalAdmission(blocks, blockEntityReserve));
   }

   private MemoryReservationAttempt reserveMemory(MemoryAdmission admission) {
      if (!admission.allowed()) {
         return MemoryReservationAttempt.REJECTED;
      }
      memoryThrottled = admission.throttled();
      if (memoryReservation == null) {
         memoryReservation = WorldOperationMemory.reserve(admission).orElse(null);
         return memoryReservation == null ? MemoryReservationAttempt.RETRY : MemoryReservationAttempt.ACQUIRED;
      }
      return memoryReservation.resize(admission.requestedBytes(), admission.usableBytes())
         ? MemoryReservationAttempt.ACQUIRED
         : MemoryReservationAttempt.RETRY;
   }

   private WorldOperationPhase phaseMetric() {
      return switch (phase) {
         case SCAN, SCAN_COMPLETE -> WorldOperationPhase.GENERATION;
         case VALIDATE -> WorldOperationPhase.SNAPSHOT;
         case JOURNAL -> WorldOperationPhase.JOURNAL;
         case CLEAR, PLACE -> WorldOperationPhase.WRITE;
         case FINALIZE -> WorldOperationPhase.FINALIZE;
         case FINAL_JOURNAL -> WorldOperationPhase.COMMIT;
      };
   }

   private enum Phase {
      SCAN,
      SCAN_COMPLETE,
      VALIDATE,
      JOURNAL,
      CLEAR,
      PLACE,
      FINALIZE,
      FINAL_JOURNAL
   }

   private enum ValidationStep {
      ADVANCED,
      COMPLETE,
      RETRY,
      REJECTED,
      FAILED
   }

   private record PlacementTarget(BlockPos pos, ReversibleBlockSnapshot source) {
   }

   private record JournalSnapshots(
      Collection<ReversibleBlockSnapshot> before,
      Collection<ReversibleBlockSnapshot> after
   ) {
   }
}
