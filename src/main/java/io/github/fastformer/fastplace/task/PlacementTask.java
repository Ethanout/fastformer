package io.github.fastformer.fastplace.task;

import io.github.fastformer.fastplace.world.BlockEntitySnapshot;
import io.github.fastformer.fastplace.FastPlaceMessages;
import io.github.fastformer.fastplace.world.JournalPreparation;
import io.github.fastformer.fastplace.OperationConflictMode;
import io.github.fastformer.fastplace.world.PersistentRecoveryJournal;
import io.github.fastformer.fastplace.PlacementUpdateMode;
import io.github.fastformer.fastplace.world.ReversibleBlockSnapshot;
import io.github.fastformer.fastplace.world.WorldChangeBatch;
import io.github.fastformer.fastplace.world.WorldChangeTransaction;
import io.github.fastformer.fastplace.world.WorldOperationMemory;
import io.github.fastformer.fastplace.world.WorldOperationCommit;
import io.github.fastformer.fastplace.world.WorldOperationMetrics;
import io.github.fastformer.fastplace.world.WorldJournalPreparation;
import io.github.fastformer.fastplace.world.WorldBatchFeedback;
import io.github.fastformer.fastplace.world.WorldOperationPhase;
import io.github.fastformer.fastplace.world.WorldRecoverySnapshot;
import io.github.fastformer.fastplace.world.MemoryAdmission;
import io.github.fastformer.fastplace.world.MemoryReservation;
import io.github.fastformer.fastplace.world.WorldTaskBudget;
import io.github.fastformer.fastplace.world.WorldTaskContext;
import io.github.fastformer.fastplace.world.WorldWriteCoordinator;
import io.github.fastformer.fastplace.world.WorldWriteSideEffectGuard;
import io.github.fastformer.fastplace.geometry.generation.BlockGenerationResult;
import io.github.fastformer.fastplace.geometry.generation.BlockPositionSource;
import io.github.fastformer.fastplace.geometry.generation.ProgressiveBlockGeneration;
import java.util.ArrayDeque;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
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
   private CompletableFuture<BlockGenerationResult> future;
   private DeferredGeneration deferredGeneration;
   private MemoryReservation generationReservation;
   private final ProgressiveBlockGeneration generationProgress;
   private final PlacementTaskPlan plan;
   private final WorldOperationMetrics metrics = new WorldOperationMetrics();
   private final WorldBatchFeedback batchFeedback = new WorldBatchFeedback(this.metrics);
   private final WorldChangeTransaction transaction = new WorldChangeTransaction();
   private WorldOperationCommit operationCommit;
   private Set<BlockPos> targets;
   private BlockPositionSource generatedPositions;
   private Map<BlockPos, BlockState> resolvedStates = Map.of();
   private boolean resolvedStatesPrepared;
   private final ArrayDeque<BlockPos> writable = new ArrayDeque<>();
   private final Iterator<BlockPos> blocks = new Iterator<>() {
      @Override
      public boolean hasNext() {
         return !PlacementTask.this.writable.isEmpty();
      }

      @Override
      public BlockPos next() {
         return PlacementTask.this.writable.removeFirst();
      }
   };
   private Iterator<BlockPos> validationIterator;
   private int validationRemaining;
   private boolean snapshotsValidated;
   private int journaledCount;
   private List<ReversibleBlockSnapshot> pendingJournalSlice = List.of();
   private int remaining;
   private int processed;
   private int total;
   private int placed;
   private boolean exceededLimit;
   private boolean generationConstraintsFailed;
   private boolean memoryChecked;
   private boolean memoryUnsafe;
   private boolean memoryThrottled;
   private long blockEntityReserve;
   private MemoryReservation memoryReservation;
   private boolean failed;
   private boolean recoveryTaskCreated;
   private String failureReason;
   private WorldOperationPhase failurePhase;
   private final WorldJournalPreparation journalPreparation = new WorldJournalPreparation();
   private Iterator<BlockPos> finalizationIterator;

   private PlacementTask(
      CompletableFuture<BlockGenerationResult> future,
      ProgressiveBlockGeneration generationProgress,
      Set<BlockPos> targets,
      PlacementTaskPlan plan,
      MemoryReservation generationReservation
   ) {
      this.future = future;
      this.generationProgress = generationProgress;
      this.targets = targets;
      this.remaining = targets == null ? 0 : targets.size();
      this.total = this.remaining;
      this.validationRemaining = this.remaining;
      this.plan = plan;
      this.generationReservation = generationReservation;
      this.metrics.phase(WorldOperationPhase.GENERATION);
      this.metrics.targetCount(this.total);
      this.metrics.queued();
      if (targets != null) {
         this.metrics.generationReady();
      }
   }

   public static PlacementTask generating(
      CompletableFuture<Set<BlockPos>> future,
      ProgressiveBlockGeneration progress,
      PlacementTaskPlan plan
   ) {
      return new PlacementTask(future.thenApply(BlockGenerationResult::fromLegacy), progress, null, plan, null);
   }

   public static PlacementTask generating(
      CompletableFuture<Set<BlockPos>> future,
      ProgressiveBlockGeneration progress,
      PlacementTaskPlan plan,
      MemoryReservation generationReservation
   ) {
      return new PlacementTask(
         future.thenApply(BlockGenerationResult::fromLegacy), progress, null, plan, generationReservation
      );
   }

   public static PlacementTask generatingResult(
      CompletableFuture<BlockGenerationResult> future,
      ProgressiveBlockGeneration progress,
      PlacementTaskPlan plan,
      MemoryReservation generationReservation
   ) {
      return new PlacementTask(future, progress, null, plan, generationReservation);
   }

   public static PlacementTask generatingResult(
      CompletableFuture<BlockGenerationResult> future,
      PlacementTaskPlan plan,
      MemoryReservation generationReservation
   ) {
      return generatingResult(future, null, plan, generationReservation);
   }

   public static PlacementTask generating(CompletableFuture<Set<BlockPos>> future, PlacementTaskPlan plan) {
      return generating(future, null, plan);
   }

   public static PlacementTask generating(
      CompletableFuture<Set<BlockPos>> future,
      PlacementTaskPlan plan,
      MemoryReservation generationReservation
   ) {
      return generating(future, null, plan, generationReservation);
   }

   public static PlacementTask ready(Set<BlockPos> blocks, PlacementTaskPlan plan) {
      return new PlacementTask(null, null, blocks, plan, null);
   }

   /** Retains frozen inputs while another operation temporarily owns the generation budget. */
   public static PlacementTask waitingForGeneration(
      Supplier<BlockGenerationResult> generator, ProgressiveBlockGeneration progress, PlacementTaskPlan plan,
      long estimatedBlocks, long additionalBlockSets
   ) {
      PlacementTask task = new PlacementTask(null, progress, null, plan, null);
      task.deferredGeneration = new DeferredGeneration(
         java.util.Objects.requireNonNull(generator), estimatedBlocks, additionalBlockSets);
      return task;
   }

   public boolean waitingForGenerationMemory() {
      return this.deferredGeneration != null;
   }

   private boolean startDeferredGeneration() {
      DeferredGeneration pending = this.deferredGeneration;
      MemoryAdmission admission = WorldOperationMemory.generationAdmission(pending.estimatedBlocks(), pending.additionalBlockSets());
      this.metrics.phase(WorldOperationPhase.MEMORY_ADMISSION);
      this.memoryThrottled = admission.throttled();
      if (!admission.allowed()) {
         this.memoryUnsafe = true;
         this.targets = Set.of();
         this.deferredGeneration = null;
         return true;
      }
      this.generationReservation = WorldOperationMemory.reserve(admission).orElse(null);
      if (this.generationReservation == null) {
         return false;
      }
      this.deferredGeneration = null;
      this.metrics.phase(WorldOperationPhase.GENERATION);
      try {
         this.future = CompletableFuture.supplyAsync(pending.generator());
      } catch (RuntimeException | OutOfMemoryError exception) {
         releaseGenerationReservation();
         this.targets = Set.of();
         fail(WorldOperationPhase.GENERATION, "generation scheduling: " + exception.getClass().getSimpleName());
      }
      return true;
   }

   private record DeferredGeneration(Supplier<BlockGenerationResult> generator, long estimatedBlocks, long additionalBlockSets) {}

   public boolean prepare() {
      if (this.failed || this.memoryUnsafe) {
         return true;
      }
      if (this.deferredGeneration != null) {
         if (!startDeferredGeneration()) {
            return false;
         }
         if (this.targets != null) {
            return true;
         }
      }
      if (this.targets != null) {
         this.metrics.phase(WorldOperationPhase.MEMORY_ADMISSION);
         checkMemory();
         if (!this.memoryUnsafe) {
            prepareResolvedStates();
         }
         return true;
      }
      if (!this.future.isDone()) {
         return false;
      }
      BlockGenerationResult generatedResult;
      try {
         generatedResult = this.future.join();
      } catch (RuntimeException | OutOfMemoryError exception) {
         this.future = null;
         releaseGenerationReservation();
         if (this.generationProgress != null) {
            this.generationProgress.releasePublished();
         }
         fail(WorldOperationPhase.GENERATION, "generation future: " + exception.getClass().getSimpleName());
         this.targets = Set.of();
         return true;
      }
      this.future = null;
      transferGenerationReservation();
      this.metrics.generationReady();
      if (this.generationProgress != null) {
         this.generationProgress.releasePublished();
      }
      if (generatedResult.status() == BlockGenerationResult.Status.CONSTRAINTS_FAILED) {
         this.generationConstraintsFailed = true;
         releaseMemoryReservation();
         this.targets = Set.of();
         this.remaining = 0;
         this.total = 0;
         this.validationRemaining = 0;
         return true;
      }
      if (generatedResult.status() == BlockGenerationResult.Status.LIMIT_EXCEEDED) {
         this.exceededLimit = true;
         releaseMemoryReservation();
         this.targets = Set.of();
         this.remaining = 0;
         this.total = 0;
         this.validationRemaining = 0;
         return true;
      }
      Set<BlockPos> generated = generatedResult.blocks();
      this.generatedPositions = generatedResult.positionSource();
      this.exceededLimit = generated.size() > this.plan.maxPlacement();
      this.targets = this.exceededLimit ? Set.of() : generated;
      if (this.exceededLimit) {
         releaseMemoryReservation();
      }
      this.remaining = this.exceededLimit ? 0 : generated.size();
      this.total = this.remaining;
      this.validationRemaining = this.remaining;
      this.metrics.targetCount(this.total);
      this.metrics.phase(WorldOperationPhase.MEMORY_ADMISSION);
      checkMemory();
      if (!this.memoryUnsafe) {
         prepareResolvedStates();
      }
      return true;
   }

   private void prepareResolvedStates() {
      if (this.resolvedStatesPrepared || this.plan.stateResolver() == null || this.targets == null) {
         return;
      }
      this.resolvedStatesPrepared = true;
      // The target set is already task-owned and is not exposed for mutation.
      // Retain the resolver's immutable/lazy map instead of copying every
      // entry into a second full-size HashMap.  State lookup is only performed
      // for task-owned target positions, so unrelated resolver entries do not
      // affect placement correctness.
      try {
         Map<BlockPos, BlockState> overrides = this.plan.stateResolver().apply(
            Collections.unmodifiableSet(this.targets)
         );
         if (overrides == null || overrides.isEmpty()) {
            this.resolvedStates = Map.of();
         } else {
            this.resolvedStates = Collections.unmodifiableMap(overrides);
         }
      } catch (RuntimeException | OutOfMemoryError exception) {
         this.resolvedStates = Map.of();
         this.targets = Set.of();
         this.remaining = 0;
         this.total = 0;
         this.validationRemaining = 0;
         releaseMemoryReservation();
         fail(
            WorldOperationPhase.GENERATION,
            "state resolution: " + exception.getClass().getSimpleName()
         );
      }
   }

   private BlockState stateAt(BlockPos pos) {
      return this.resolvedStates.getOrDefault(pos, this.plan.defaultState());
   }

   private void checkMemory() {
      if (this.memoryChecked || this.exceededLimit || this.targets == null) {
         return;
      }
      this.memoryChecked = true;
      var admission = WorldOperationMemory.snapshotAdmission(this.targets.size(), this.blockEntityReserve);
      this.memoryThrottled = admission.throttled();
      this.memoryUnsafe = !admission.allowed();
      if (this.memoryUnsafe) {
         this.targets = Set.of();
         this.remaining = 0;
         this.validationRemaining = 0;
         return;
      }
      boolean reserved = this.memoryReservation != null
         ? this.memoryReservation.resize(admission.requestedBytes(), admission.usableBytes())
         : (this.memoryReservation = WorldOperationMemory.reserve(admission).orElse(null)) != null;
      if (!reserved) {
         this.memoryChecked = false;
         this.memoryThrottled = true;
      }
   }

   public boolean validateSnapshots(ServerLevel level, WorldTaskBudget budget) {
      if (this.snapshotsValidated || this.failed || this.exceededLimit) {
         return true;
      }
      if (this.validationIterator == null) {
         this.metrics.phase(WorldOperationPhase.SNAPSHOT);
         this.validationIterator = this.generatedPositions != null && this.generatedPositions.supportsDraining()
            ? this.generatedPositions.drainingIterator()
            : this.targets.iterator();
      }
      while (this.validationIterator.hasNext() && budget.tryConsume()) {
         MemoryReservationAttempt existingReservation = reserveSnapshotMemory();
         if (existingReservation != MemoryReservationAttempt.ACQUIRED) {
            this.memoryUnsafe = existingReservation == MemoryReservationAttempt.REJECTED;
            return this.memoryUnsafe;
         }
         BlockPos pos = this.validationIterator.next();
         this.validationRemaining--;
         Optional<ReversibleBlockSnapshot> snapshot = ReversibleBlockSnapshot.capture(level, pos);
         if (snapshot.isEmpty()) {
            fail(WorldOperationPhase.SNAPSHOT, "snapshot validation at " + pos.toShortString());
            return true;
         }
         this.metrics.snapshotCaptured();
         ReversibleBlockSnapshot captured = snapshot.orElseThrow();
         BlockState previous = captured.state();
         this.blockEntityReserve = WorldOperationMemory.saturatingAdd(
            this.blockEntityReserve,
            WorldOperationMemory.snapshotNbtReserve(captured)
         );
         this.transaction.recordExpected(pos, captured);
         MemoryReservationAttempt capturedReservation = reserveSnapshotMemory();
         if (capturedReservation != MemoryReservationAttempt.ACQUIRED) {
            this.memoryUnsafe = capturedReservation == MemoryReservationAttempt.REJECTED;
            return this.memoryUnsafe;
         }
         if (!this.journalPreparation.started()) {
            int unjournaled = this.transaction.expectedCount() - this.journaledCount;
            if (unjournaled >= PersistentRecoveryJournal.segmentCapacity(this.journaledCount)) {
               break;
            }
         }
         if (previous.equals(stateAt(pos))
            || this.plan.conflictMode() == OperationConflictMode.KEEP_EXISTING && !previous.canBeReplaced()) {
            continue;
         }
      }
      if (!this.validationIterator.hasNext()) {
         MemoryReservationAttempt journalReservation = reserveJournalMemory();
         if (journalReservation != MemoryReservationAttempt.ACQUIRED) {
            this.memoryUnsafe = journalReservation == MemoryReservationAttempt.REJECTED;
            return this.memoryUnsafe;
         }
         this.snapshotsValidated = true;
         // The validation map owns the snapshots from this point onward. A
         // packed position stream avoids retaining the generator's hash table
         // for the full duration of world writes.
         this.targets = Set.of();
         this.generatedPositions = null;
         this.validationIterator = null;
      }
      return journalBatchReady(budget);
   }

   private boolean journalBatchReady(WorldTaskBudget budget) {
      int unjournaled = this.transaction.expectedCount() - this.journaledCount - this.pendingJournalSlice.size();
      if (unjournaled <= 0) {
         return this.snapshotsValidated;
      }
      if (this.snapshotsValidated) {
         return true;
      }
      int batch = PersistentRecoveryJournal.segmentCapacity(this.journaledCount);
      return unjournaled >= batch || !budget.hasRemaining();
   }

   public JournalPreparation prepareJournal(WorldTaskContext context) {
      if (this.transaction.expectedCount() == 0) {
         return JournalPreparation.READY;
      }
      if (this.pendingJournalSlice.isEmpty()) {
         if (!this.hasUnjournaledSnapshots()) {
            return JournalPreparation.READY;
         }
         MemoryReservationAttempt journalReservation = reserveJournalMemory();
         if (journalReservation == MemoryReservationAttempt.RETRY) {
            return JournalPreparation.PENDING;
         }
         if (journalReservation == MemoryReservationAttempt.REJECTED) {
            this.memoryUnsafe = true;
            fail(WorldOperationPhase.JOURNAL, "journal memory admission failed");
            return JournalPreparation.FAILED;
         }
         int from = this.journaledCount;
         int size = PersistentRecoveryJournal.segmentBatchSize(
            from, this.transaction.expectedCount() - from
         );
         this.pendingJournalSlice = this.transaction.expectedRange(from, from + size);
      }
      if (!this.journalPreparation.started()) {
         this.metrics.phase(WorldOperationPhase.JOURNAL);
      }
      ServerLevel level = context.level(this.plan.dimension());
      if (level == null) {
         fail(WorldOperationPhase.WORLD_UNLOADED, "journal dimension is not loaded");
         return JournalPreparation.FAILED;
      }
      RegistryAccess registryAccess = level.registryAccess();
      List<ReversibleBlockSnapshot> slice = this.pendingJournalSlice;
      JournalPreparation preparation = this.journalPreparation.journal() == null
         ? this.journalPreparation.poll(() -> beginJournal(context, registryAccess, slice), context)
         : this.journalPreparation.pollAppend(() -> appendJournal(slice, registryAccess));
      if (preparation == JournalPreparation.FAILED) {
         fail(WorldOperationPhase.JOURNAL, this.journalPreparation.failureReason());
      } else if (preparation == JournalPreparation.READY && !slice.isEmpty()) {
         if (this.journaledCount == 0) {
            this.metrics.journalReady();
         }
         for (ReversibleBlockSnapshot snapshot : slice) {
            this.writable.addLast(snapshot.pos());
         }
         this.journaledCount += slice.size();
         this.pendingJournalSlice = List.of();
      }
      return preparation;
   }

   private Optional<PersistentRecoveryJournal> beginJournal(
      WorldTaskContext context,
      RegistryAccess registryAccess,
      Collection<ReversibleBlockSnapshot> journalBefore
   ) {
      Collection<ReversibleBlockSnapshot> journalAfter = predictedPlacementAfter(
         registryAccess, journalBefore, this::stateAt, this.plan.conflictMode()
      );
      return PersistentRecoveryJournal.begin(
         context.server(),
         context.owner(),
         this.plan.dimension(),
         journalBefore,
         journalAfter,
         this.operationId()
      );
   }

   private boolean appendJournal(
      Collection<ReversibleBlockSnapshot> journalBefore,
      RegistryAccess registryAccess
   ) {
      PersistentRecoveryJournal journal = this.journalPreparation.journal();
      if (journal == null) {
         return false;
      }
      Collection<ReversibleBlockSnapshot> journalAfter = predictedPlacementAfter(
         registryAccess, journalBefore, this::stateAt, this.plan.conflictMode()
      );
      return journal.appendSegment(journalBefore, journalAfter);
   }

   private MemoryReservationAttempt reserveSnapshotMemory() {
      return reserveMemory(WorldOperationMemory.snapshotAdmission(this.total, this.blockEntityReserve));
   }

   private MemoryReservationAttempt reserveJournalMemory() {
      var journalAdmission = WorldOperationMemory.journalAdmission(this.total, this.blockEntityReserve);
      return reserveMemory(journalAdmission);
   }

   private MemoryReservationAttempt reserveMemory(MemoryAdmission admission) {
      this.memoryThrottled = admission.throttled();
      if (!admission.allowed()) {
         return MemoryReservationAttempt.REJECTED;
      }
      boolean reserved = this.memoryReservation != null
         ? this.memoryReservation.resize(admission.requestedBytes(), admission.usableBytes())
         : (this.memoryReservation = WorldOperationMemory.reserve(admission).orElse(null)) != null;
      return reserved ? MemoryReservationAttempt.ACQUIRED : MemoryReservationAttempt.RETRY;
   }

   private boolean hasUnjournaledSnapshots() {
      return this.transaction.expectedCount() > this.journaledCount + this.pendingJournalSlice.size();
   }

   public boolean snapshotsComplete() {
      return this.snapshotsValidated;
   }

   public boolean readyToFinalize() {
      return this.snapshotsValidated
         && this.pendingJournalSlice.isEmpty()
         && this.writable.isEmpty()
         && this.remaining == 0;
   }

   public boolean finalizeSnapshots(ServerLevel level, WorldTaskBudget budget) {
      if (this.transaction.afterCount() == 0) {
         return true;
      }
      if (this.finalizationIterator == null) {
         this.finalizationIterator = this.transaction.afterPositions();
      }
      while (this.finalizationIterator.hasNext() && budget.tryConsume()) {
         BlockPos position = this.finalizationIterator.next();
         Optional<ReversibleBlockSnapshot> actual = ReversibleBlockSnapshot.capture(level, position);
         if (actual.isEmpty()) {
            fail(WorldOperationPhase.FINALIZE, "final snapshot at " + position.toShortString());
            return true;
         }
         this.transaction.recordAfter(position, actual.orElseThrow());
      }
      return !this.finalizationIterator.hasNext();
   }

   public JournalPreparation prepareCommit() {
      this.metrics.phase(WorldOperationPhase.COMMIT);
      if (this.operationCommit == null) {
         MemoryReservationAttempt commitReservation = reserveCommitMemory();
         if (commitReservation == MemoryReservationAttempt.RETRY) {
            return JournalPreparation.PENDING;
         }
         if (commitReservation == MemoryReservationAttempt.REJECTED) {
            this.memoryUnsafe = true;
            fail(WorldOperationPhase.COMMIT, "commit memory admission failed");
            return JournalPreparation.FAILED;
         }
         this.operationCommit = WorldOperationCommit.begin(
            this.plan.dimension(), this.transaction, this.journalPreparation.journal()
         );
      }
      JournalPreparation preparation = this.operationCommit.poll();
      if (preparation == JournalPreparation.FAILED) {
         fail(WorldOperationPhase.COMMIT, this.operationCommit.failureReason());
      }
      return preparation;
   }

   public Optional<WorldChangeBatch> preparedBatch() {
      return this.operationCommit == null
         ? Optional.empty()
         : this.operationCommit.batch().map(batch -> batch.withOperationId(this.operationId()));
   }

   public PersistentRecoveryJournal journal() {
      return this.journalPreparation.journal();
   }

   public boolean acquireLease(WorldTaskContext context) {
      boolean acquired = WorldWriteCoordinator.tryAcquire(context.server(), this.plan.dimension(), context.owner());
      if (acquired) {
         this.metrics.leaseAcquired();
      }
      return acquired;
   }

   public void releaseLease(WorldTaskContext context) {
      WorldWriteCoordinator.release(context.server(), this.plan.dimension(), context.owner());
   }

   public void releaseAfterCancelledJournal(WorldTaskContext context) {
      if (this.operationCommit != null) {
         this.operationCommit.cancel();
      }
      this.journalPreparation.releaseAfterCancellation(context, this.plan.dimension());
   }

   public boolean hasWrites() {
      return this.transaction.hasWrites();
   }

   public int validationRemaining() {
      return Math.max(0, this.validationRemaining);
   }

   public void cancel() {
      cancelForRecovery();
      releaseMemoryReservation();
   }

   /** Stops task activity while retaining its reservation for recovery capture. */
   public void cancelForRecovery() {
      if (this.deferredGeneration != null) {
         this.deferredGeneration = null;
         this.targets = Set.of();
      }
      this.journalPreparation.cancel();
      if (this.generationProgress != null) {
         this.generationProgress.cancel();
         this.generationProgress.releasePublished();
      }
      releaseGenerationReservation();
   }

   /** Releases the task working-set budget after ownership transfers or completion. */
   public void releaseMemoryReservation() {
      if (this.memoryReservation != null) {
         MemoryReservation reservation = this.memoryReservation;
         this.memoryReservation = null;
         CompletableFuture<?> publicationCompletion = this.operationCommit == null
            ? CompletableFuture.completedFuture(null)
            : this.operationCommit.completion();
         this.journalPreparation.releaseWhenIdle(reservation, publicationCompletion);
      }
   }

   /** Reacquires the current phase budget after an unloaded dimension releases it. */
   public boolean ensureMemoryReservation() {
      if (this.memoryReservation != null) {
         return true;
      }
      if (this.operationCommit != null) {
         MemoryReservationAttempt commitReservation = reserveCommitMemory();
         this.memoryUnsafe = commitReservation == MemoryReservationAttempt.REJECTED;
         return commitReservation == MemoryReservationAttempt.ACQUIRED;
      }
      MemoryAdmission admission;
      if (this.transaction.expectedCount() > 0) {
         admission = WorldOperationMemory.journalAdmission(this.transaction.expectedCount(), this.blockEntityReserve);
      } else if (this.targets != null && !this.targets.isEmpty()) {
         admission = WorldOperationMemory.snapshotAdmission(this.total, this.blockEntityReserve);
      } else {
         admission = transactionAdmission();
      }
      this.memoryThrottled = admission.throttled();
      if (!admission.allowed()) {
         this.memoryUnsafe = true;
         this.metrics.phase(WorldOperationPhase.MEMORY_ADMISSION);
         return false;
      }
      this.memoryReservation = WorldOperationMemory.reserve(admission).orElse(null);
      if (this.memoryReservation == null) {
         this.memoryThrottled = true;
         this.metrics.phase(WorldOperationPhase.MEMORY_ADMISSION);
         return false;
      }
      return true;
   }

   private void releaseGenerationReservation() {
      if (this.generationReservation != null) {
         MemoryReservation reservation = this.generationReservation;
         this.generationReservation = null;
         CompletableFuture<?> generationCompletion = this.future;
         if (generationCompletion == null || generationCompletion.isDone()) {
            reservation.close();
         } else {
            generationCompletion.whenComplete((ignored, exception) -> reservation.close());
         }
      }
   }

   private void transferGenerationReservation() {
      if (this.generationReservation != null) {
         this.memoryReservation = this.generationReservation;
         this.generationReservation = null;
      }
   }

   /**
    * Drops mutable transaction staging after the compressed history commit has
    * succeeded.  Recovery paths deliberately retain these structures until
    * their outcome is known.
    */
   public void releaseCommittedTransactionState() {
      this.transaction.releaseWriteState();
      this.operationCommit = null;
      this.finalizationIterator = null;
   }

   public Iterator<BlockPos> blocks() {
      return this.blocks;
   }

   /** Drops generation-only structures once all world writes have been attempted. */
   public void releaseGenerationState() {
      this.targets = Set.of();
      this.generatedPositions = null;
      this.resolvedStates = Map.of();
      this.resolvedStatesPrepared = false;
      this.transaction.clearExpected();
      this.writable.clear();
      this.pendingJournalSlice = List.of();
      this.validationIterator = null;
   }

   /** Shrinks the reservation after generation-only structures are released. */
   public void resizeMemoryReservationForTransaction() {
      if (this.memoryReservation == null) {
         return;
      }
      var admission = transactionAdmission();
      if (admission.allowed()) {
         this.memoryReservation.resize(admission.requestedBytes(), admission.usableBytes());
      }
   }

   private MemoryAdmission transactionAdmission() {
      return WorldOperationMemory.transactionAdmission(this.transaction.snapshotCount(), this.blockEntityReserve);
   }

   private MemoryReservationAttempt reserveCommitMemory() {
      MemoryAdmission admission = WorldOperationMemory.commitAdmission(
         this.transaction.snapshotCount(),
         this.transaction.largestSideCount(),
         this.transaction.commitBlockEntityReserve()
      );
      return reserveMemory(admission);
   }

   public void consumed() {
      this.remaining--;
      this.processed++;
   }

   public int total() {
      return this.total;
   }

   public int processed() {
      return this.processed;
   }

   public ProgressiveBlockGeneration.Snapshot generationProgress() {
      return this.generationProgress == null ? null : this.generationProgress.snapshot();
   }

   public void place(WorldTaskContext context, ServerLevel level, BlockPos pos) {
      this.metrics.phase(WorldOperationPhase.WRITE);
      BlockWriteResult result = setBlockWithUndo(
         context,
         level,
         pos,
         stateAt(pos),
         this.plan.conflictMode(),
         this.plan.updateMode(),
         this.transaction
      );
      if (result == BlockWriteResult.PLACED) {
         this.placed++;
         this.metrics.writeAttempt(true);
      } else if (result == BlockWriteResult.FAILED) {
         this.metrics.writeAttempt(false);
         fail(WorldOperationPhase.WRITE, "world write at " + pos.toShortString());
      } else {
         this.metrics.writeAttempt(false);
      }
   }

   public int placed() {
      return this.placed;
   }

   public int maxPlacement() {
      return this.plan.maxPlacement();
   }

   public ResourceKey<Level> dimension() {
      return this.plan.dimension();
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

   public boolean memoryThrottled() {
      return this.memoryThrottled;
   }

   public int previousBatchCells() {
      return this.batchFeedback.cells();
   }

   public long previousBatchNanos() {
      return this.batchFeedback.elapsedNanos();
   }

   public void recordBatch(int cells, long elapsedNanos) {
      this.batchFeedback.record(cells, elapsedNanos);
   }

   public boolean failed() {
      return this.failed;
   }

   public boolean recoveryTaskCreated() {
      return this.recoveryTaskCreated;
   }

   public void markRecoveryTaskCreated() {
      this.recoveryTaskCreated = true;
   }

   public String failureReason() {
      return this.failureReason == null ? "unknown" : this.failureReason;
   }

   public WorldOperationPhase failurePhase() {
      return this.failurePhase == null ? this.metrics.phase() : this.failurePhase;
   }

   public UUID operationId() {
      return this.metrics.operationId();
   }

   public String metricsSummary() {
      return this.metrics.summary();
   }

   public void markComplete() {
      this.metrics.complete();
   }

   public void markWorldUnloaded() {
      this.metrics.phase(WorldOperationPhase.WORLD_UNLOADED);
   }

   /** Stops task-owned work and transfers recovery storage once. */
   public WorldRecoverySnapshot stopAndTransferRecovery() {
      cancelForRecovery();
      CompletableFuture<Void> ready = this.operationCommit == null
         ? CompletableFuture.completedFuture(null)
         : this.operationCommit.stopForRecovery();
      return this.transaction.transferRecoverySnapshot(CompletableFuture.allOf(ready, this.journalPreparation.completion()));
   }

   private void fail(WorldOperationPhase phase, String reason) {
      this.failed = true;
      this.failureReason = reason;
      this.failurePhase = phase;
      this.metrics.phase(phase);
   }

   private static BlockWriteResult setBlockWithUndo(
      WorldTaskContext context,
      ServerLevel level,
      BlockPos pos,
      BlockState state,
      OperationConflictMode conflictMode,
      PlacementUpdateMode updateMode,
      WorldChangeTransaction transaction
   ) {
      BlockState previous = level.getBlockState(pos);
      ReversibleBlockSnapshot expectedSnapshot = transaction.expectedAt(pos);
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
      transaction.recordBefore(before);
      if (WorldWriteSideEffectGuard.setBlock(level, pos, state, updateMode.flags())) {
         return captureWrittenState(level, pos, updateMode, transaction);
      }
      // A failed callback can still leave a partial world mutation.
      captureFailedWriteState(level, pos, updateMode, transaction);
      return BlockWriteResult.FAILED;
   }

   private static BlockWriteResult captureWrittenState(
      ServerLevel level,
      BlockPos pos,
      PlacementUpdateMode updateMode,
      WorldChangeTransaction transaction
   ) {
      Optional<ReversibleBlockSnapshot> written = ReversibleBlockSnapshot.capture(level, pos);
      if (written.isEmpty()) {
         transaction.recordAfter(
            pos.immutable(),
            new ReversibleBlockSnapshot(pos, level.getBlockState(pos), level.getFluidState(pos), null)
         );
         return BlockWriteResult.FAILED;
      }
      transaction.recordAfter(pos, written.orElseThrow());
      if (updateMode == PlacementUpdateMode.NORMAL
         && !ReversibleBlockSnapshot.refreshTaskOwnedNeighbors(level, pos, transaction)) {
         // The primary write already succeeded. A busy block entity or a
         // transient neighbor update must not turn it into an immediate
         // rollback; final snapshots still validate the durable result.
      }
      return BlockWriteResult.PLACED;
   }

   private static void captureFailedWriteState(
      ServerLevel level,
      BlockPos pos,
      PlacementUpdateMode updateMode,
      WorldChangeTransaction transaction
   ) {
      Optional<ReversibleBlockSnapshot> written = ReversibleBlockSnapshot.capture(level, pos);
      transaction.recordAfter(
         pos.immutable(),
         written.orElseGet(() -> new ReversibleBlockSnapshot(
            pos, level.getBlockState(pos), level.getFluidState(pos), null
         ))
      );
      if (updateMode == PlacementUpdateMode.NORMAL) {
         ReversibleBlockSnapshot.refreshTaskOwnedNeighbors(level, pos, transaction);
      }
   }

   private static Collection<ReversibleBlockSnapshot> predictedPlacementAfter(
      RegistryAccess registryAccess,
      Collection<ReversibleBlockSnapshot> before,
      Function<BlockPos, BlockState> placedStateAt,
      OperationConflictMode conflictMode
   ) {
      return lazyMappedCollection(before, snapshot -> predictedSnapshot(
         registryAccess, snapshot, placedStateAt.apply(snapshot.pos()), conflictMode
      ));
   }

   static <T, R> Collection<R> lazyMappedCollection(Collection<T> source, Function<T, R> mapper) {
      return new AbstractCollection<>() {
         @Override
         public Iterator<R> iterator() {
            Iterator<T> sourceIterator = source.iterator();
            return new Iterator<>() {
               @Override
               public boolean hasNext() {
                  return sourceIterator.hasNext();
               }

               @Override
               public R next() {
                  return mapper.apply(sourceIterator.next());
               }
            };
         }

         @Override
         public int size() {
            return source.size();
         }
      };
   }

   private static ReversibleBlockSnapshot predictedSnapshot(
      RegistryAccess registryAccess,
      ReversibleBlockSnapshot snapshot,
      BlockState placedState,
      OperationConflictMode conflictMode
   ) {
      if (snapshot.state().equals(placedState)
         || conflictMode == OperationConflictMode.KEEP_EXISTING && !snapshot.state().canBeReplaced()) {
         return snapshot;
      }
      BlockEntitySnapshot blockEntityData = predictedBlockEntity(registryAccess, snapshot, placedState);
      return new ReversibleBlockSnapshot(
         snapshot.pos(), placedState, placedState.getFluidState(), blockEntityData
      );
   }

   private static BlockEntitySnapshot predictedBlockEntity(
      RegistryAccess registryAccess,
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
         : new BlockEntitySnapshot(blockEntity.saveWithFullMetadata(registryAccess));
   }

   private enum BlockWriteResult {
      PLACED,
      SKIPPED,
      FAILED
   }
}
