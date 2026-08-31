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
import io.github.fastformer.fastplace.world.WorldChangeBatch;
import io.github.fastformer.fastplace.world.WorldOperationCommit;
import io.github.fastformer.fastplace.world.WorldOperationMemory;
import io.github.fastformer.fastplace.world.WorldTaskBudget;
import io.github.fastformer.fastplace.world.WorldTaskContext;
import io.github.fastformer.fastplace.world.WorldWriteCoordinator;
import io.github.fastformer.fastplace.world.WorldWriteSideEffectGuard;
import io.github.fastformer.fastplace.geometry.OperationGeometry;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
   private final List<ReversibleBlockSnapshot> source = new ArrayList<>();
   private final ArrayDeque<ReversibleBlockSnapshot> undo = new ArrayDeque<>();
   private final Map<BlockPos, ReversibleBlockSnapshot> expected = new HashMap<>();
   private final Map<BlockPos, ReversibleBlockSnapshot> after = new HashMap<>();
   private boolean failed;
   private PersistentRecoveryJournal journal;
   private CompletableFuture<Optional<PersistentRecoveryJournal>> journalFuture;
   private WorldOperationCommit commitPreparation;
   private volatile boolean cancelled;
   private Phase phase = Phase.SCAN;
   private int x;
   private int y;
   private int z;
   private int sourceIndex;
   private int repeatX;
   private int repeatY;
   private int repeatZ;
   private Iterator<Map.Entry<BlockPos, ReversibleBlockSnapshot>> finalizationIterator;
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
      resetCursor();
      resetPlacementCursor();
   }

   @Override
   public OperationTaskResult tick(WorldTaskContext context, ServerLevel level, WorldTaskBudget budget) {
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
         source.add(snapshot);
         expected.putIfAbsent(pos.immutable(), snapshot);
         blockEntityReserve = WorldOperationMemory.saturatingAdd(
            blockEntityReserve, WorldOperationMemory.snapshotNbtReserve(snapshot)
         );
         if (!WorldOperationMemory.canPrepare(source.size(), blockEntityReserve)) {
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
      if (!WorldOperationMemory.canPrepare(plannedBlocks, blockEntityReserve)) {
         return Optional.of(OperationTaskResult.MEMORY_UNSAFE);
      }
      phase = Phase.VALIDATE;
      resetPlacementCursor();
      return Optional.empty();
   }

   private Optional<OperationTaskResult> validatePhase(ServerLevel level) {
      if (validateNext(level)) {
         return Optional.empty();
      }
      if (memoryUnsafe) {
         return Optional.of(OperationTaskResult.MEMORY_UNSAFE);
      }
      if (failed) {
         return Optional.of(OperationTaskResult.FAILED);
      }
      phase = Phase.JOURNAL;
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
      phase = clearsSource() ? Phase.CLEAR : Phase.PLACE;
      resetPlacementCursor();
      resetCursor();
      return Optional.empty();
   }

   private Optional<OperationTaskResult> clearNext(ServerLevel level) {
      if (sourceIndex >= source.size()) {
         phase = Phase.PLACE;
         sourceIndex = 0;
         return Optional.empty();
      }
      ReversibleBlockSnapshot sourceBlock = source.get(sourceIndex++);
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
      PlacementTarget target = nextPlacementTarget();
      if (target == null) {
         return false;
      }
      place(level, target.pos(), target.source());
      return !failed;
   }

   private boolean finalizeNext(ServerLevel level) {
      if (finalizationIterator == null) {
         finalizationIterator = after.entrySet().iterator();
      }
      if (!finalizationIterator.hasNext()) {
         return false;
      }
      Map.Entry<BlockPos, ReversibleBlockSnapshot> entry = finalizationIterator.next();
      Optional<ReversibleBlockSnapshot> actual = ReversibleBlockSnapshot.capture(level, entry.getKey());
      if (actual.isEmpty()) {
         failed = true;
         return false;
      }
      entry.setValue(actual.orElseThrow());
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
      if (expected.putIfAbsent(target.pos().immutable(), snapshot) == null) {
         blockEntityReserve = WorldOperationMemory.saturatingAdd(
            blockEntityReserve, WorldOperationMemory.snapshotNbtReserve(snapshot)
         );
         if (!WorldOperationMemory.canPrepare(plannedBlocks, blockEntityReserve)) {
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
         ReversibleBlockSnapshot sourceExpected = expected.get(sourceBlock.pos());
         if (sourceExpected == null || !sourceExpected.matches(level, sourceBlock.pos())) {
            failed = true;
            return;
         }
      }
      BlockState current = level.getBlockState(pos);
      if (conflictMode == OperationConflictMode.KEEP_EXISTING && !current.canBeReplaced()) {
         return;
      }
      ReversibleBlockSnapshot expectedSnapshot = expected.get(pos);
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
      undo.addFirst(before);
      if (!sourceBlock.placeAt(level, pos, updateMode.flags())) {
         recordFailedWrite(level, pos);
         return;
      }
      Optional<ReversibleBlockSnapshot> written = ReversibleBlockSnapshot.capture(level, pos);
      if (written.isEmpty()) {
         ReversibleBlockSnapshot fallback = currentSnapshot(level, pos);
         expected.put(pos.immutable(), fallback);
         after.put(pos.immutable(), fallback);
         failed = true;
         return;
      }
      expected.put(pos.immutable(), written.orElseThrow());
      after.put(pos.immutable(), written.orElseThrow());
      if (updateMode == PlacementUpdateMode.NORMAL
         && !ReversibleBlockSnapshot.refreshTaskOwnedNeighbors(level, pos, expected, undo, after)) {
         failed = true;
      }
   }

   private boolean setBlock(ServerLevel level, BlockPos pos, BlockState state) {
      BlockState previous = level.getBlockState(pos);
      ReversibleBlockSnapshot expectedSnapshot = expected.get(pos);
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
         expected.put(pos.immutable(), snapshot);
         after.put(pos.immutable(), snapshot);
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
      undo.addFirst(before);
      if (!WorldWriteSideEffectGuard.setBlock(level, pos, state, updateMode.flags())) {
         recordFailedWrite(level, pos);
         return false;
      }
      Optional<ReversibleBlockSnapshot> written = ReversibleBlockSnapshot.capture(level, pos);
      if (written.isEmpty()) {
         ReversibleBlockSnapshot fallback = currentSnapshot(level, pos);
         expected.put(pos.immutable(), fallback);
         after.put(pos.immutable(), fallback);
         failed = true;
         return false;
      }
      expected.put(pos.immutable(), written.orElseThrow());
      after.put(pos.immutable(), written.orElseThrow());
      if (updateMode == PlacementUpdateMode.NORMAL
         && !ReversibleBlockSnapshot.refreshTaskOwnedNeighbors(level, pos, expected, undo, after)) {
         failed = true;
         return false;
      }
      return true;
   }

   private void recordFailedWrite(ServerLevel level, BlockPos pos) {
      after.put(pos.immutable(), ReversibleBlockSnapshot.capture(level, pos).orElseGet(() -> currentSnapshot(level, pos)));
      if (updateMode == PlacementUpdateMode.NORMAL) {
         ReversibleBlockSnapshot.refreshTaskOwnedNeighbors(level, pos, expected, undo, after);
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
      if (journal != null) {
         return JournalPreparation.READY;
      }
      if (journalFuture == null) {
         journalFuture = CompletableFuture.supplyAsync(
            () -> journalSnapshots().flatMap(snapshots -> snapshots.before().isEmpty()
               ? Optional.empty()
               : PersistentRecoveryJournal.begin(
                  context.server(), context.owner(), dimension, snapshots.before(), snapshots.after()
               )),
            PersistentRecoveryJournal.executor()
         );
         return JournalPreparation.PENDING;
      }
      if (!journalFuture.isDone()) {
         return JournalPreparation.PENDING;
      }
      try {
         journal = journalFuture.join().orElse(null);
      } catch (RuntimeException exception) {
         return JournalPreparation.FAILED;
      }
      return journal == null ? JournalPreparation.FAILED : JournalPreparation.READY;
   }

   private Optional<JournalSnapshots> journalSnapshots() {
      List<ReversibleBlockSnapshot> originals = new ArrayList<>(expected.values());
      for (ReversibleBlockSnapshot snapshot : source) {
         if (!expected.containsKey(snapshot.pos())) {
            originals.add(snapshot);
         }
      }
      Map<BlockPos, ReversibleBlockSnapshot> finalStates = new HashMap<>();
      for (ReversibleBlockSnapshot snapshot : originals) {
         finalStates.put(snapshot.pos(), snapshot);
      }
      try {
         predictClearedSource(finalStates);
         predictTargets(finalStates);
      } catch (RuntimeException exception) {
         return Optional.empty();
      }
      List<ReversibleBlockSnapshot> finalSnapshots = new ArrayList<>(originals.size());
      for (ReversibleBlockSnapshot original : originals) {
         ReversibleBlockSnapshot finalSnapshot = finalStates.get(original.pos());
         if (finalSnapshot == null) {
            return Optional.empty();
         }
         finalSnapshots.add(finalSnapshot);
      }
      return Optional.of(new JournalSnapshots(originals, finalSnapshots));
   }

   private void predictClearedSource(Map<BlockPos, ReversibleBlockSnapshot> finalStates) {
      if (!clearsSource()) {
         return;
      }
      for (ReversibleBlockSnapshot sourceSnapshot : source) {
         BlockPos pos = sourceSnapshot.pos();
         BlockState air = Blocks.AIR.defaultBlockState();
         finalStates.put(pos, new ReversibleBlockSnapshot(pos, air, air.getFluidState(), null));
      }
   }

   private void predictTargets(Map<BlockPos, ReversibleBlockSnapshot> finalStates) {
      for (BlockPos repetition : stackRegion.repetitions(maxPlacement)) {
         if (skipOrigin() && repetition.equals(BlockPos.ZERO)) {
            continue;
         }
         BlockPos displacement = OperationGeometry.stackDisplacement(bounds, repetition).offset(translation);
         for (ReversibleBlockSnapshot sourceSnapshot : source) {
            putPredictedTarget(finalStates, sourceSnapshot.pos().offset(displacement), sourceSnapshot);
         }
      }
   }

   private JournalPreparation prepareCommit() {
      if (commitPreparation == null) {
         commitPreparation = WorldOperationCommit.begin(dimension, undo, after, journal);
      }
      return commitPreparation.poll();
   }

   private void putPredictedTarget(
      Map<BlockPos, ReversibleBlockSnapshot> finalStates,
      BlockPos target,
      ReversibleBlockSnapshot sourceSnapshot
   ) {
      ReversibleBlockSnapshot current = finalStates.get(target);
      if (current == null) {
         current = expected.get(target);
      }
      if (current == null) {
         throw new IllegalStateException("Missing validated operation target");
      }
      if (conflictMode == OperationConflictMode.KEEP_EXISTING && !current.state().canBeReplaced()) {
         return;
      }
      finalStates.put(target.immutable(), new ReversibleBlockSnapshot(
         target, sourceSnapshot.state(), sourceSnapshot.fluidState(), sourceSnapshot.blockEntity()
      ));
   }

   @Override
   public void cancelJournalPreparation() {
      cancelled = true;
      if (commitPreparation != null) {
         commitPreparation.cancel();
      }
      if (journalFuture != null) {
         journalFuture.whenComplete((created, exception) -> {
            if (cancelled && journal == null && exception == null && created != null) {
               created.ifPresent(PersistentRecoveryJournal::discardUnused);
            }
         });
      }
   }

   @Override
   public PersistentRecoveryJournal journal() {
      return journal;
   }

   @Override
   public boolean acquireLease(WorldTaskContext context) {
      return WorldWriteCoordinator.tryAcquire(context.server(), dimension, context.owner());
   }

   @Override
   public void releaseLease(WorldTaskContext context) {
      WorldWriteCoordinator.release(context.server(), dimension, context.owner());
   }

   @Override
   public void releaseAfterCancelledJournal(WorldTaskContext context) {
      cancelJournalPreparation();
      WorldWriteCoordinator.releaseAfterUnusedJournal(
         context.server(), dimension, context.owner(), journal, journalFuture
      );
   }

   @Override
   public boolean hasWrites() {
      return !undo.isEmpty();
   }

   @Override
   public ArrayDeque<ReversibleBlockSnapshot> undoChanges() {
      return undo;
   }

   @Override
   public Map<BlockPos, ReversibleBlockSnapshot> afterChanges() {
      return after;
   }

   @Override
   public ResourceKey<Level> dimension() {
      return dimension;
   }

   @Override
   public Optional<WorldChangeBatch> preparedBatch() {
      return commitPreparation == null ? Optional.empty() : commitPreparation.batch();
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
      List<ReversibleBlockSnapshot> before,
      List<ReversibleBlockSnapshot> after
   ) {
   }
}
