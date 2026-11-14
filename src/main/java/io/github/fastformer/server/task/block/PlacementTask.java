package io.github.fastformer.server.task.block;

import io.github.fastformer.server.worldwrite.snapshot.SnapshotMemoryRules;
import io.github.fastformer.server.worldwrite.snapshot.BlockSnapshotReader;

import io.github.fastformer.server.task.MemoryReservationAttempt;

import io.github.fastformer.common.geometry.generation.source.BlockGenerationResult;
import io.github.fastformer.common.geometry.generation.source.BlockPositionSource;
import io.github.fastformer.common.geometry.generation.source.ProgressiveBlockGeneration;
import io.github.fastformer.server.recovery.JournalPreparation;
import io.github.fastformer.server.recovery.PersistentRecoveryJournal;
import io.github.fastformer.server.recovery.WorldJournalPreparation;
import io.github.fastformer.server.worldwrite.transaction.WorldOperationCommit;
import io.github.fastformer.server.task.metrics.WorldOperationPhase;
import io.github.fastformer.server.task.WorldTaskBudget;
import io.github.fastformer.server.task.WorldTaskContext;
import io.github.fastformer.server.task.runtime.LeasedJournaledTask;
import io.github.fastformer.server.task.TaskStep;
import io.github.fastformer.common.world.memory.MemoryAdmission;
import io.github.fastformer.common.world.memory.MemoryReservation;
import io.github.fastformer.common.world.memory.WorldOperationMemory;
import io.github.fastformer.server.worldwrite.snapshot.ReversibleBlockSnapshot;
import io.github.fastformer.common.workspace.model.OperationConflictMode;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Player-owned placement transaction from asynchronous shape generation through
 * validated world writes and durable history commit.
 */
public final class PlacementTask extends LeasedJournaledTask {
   private final PlacementGeneration generation;
   private final PlacementTaskPlan plan;
   private final BlockSnapshotReader snapshots;
   private final BlockTaskMemoryPolicy memoryPolicy;
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
   private int remaining;
   private int processed;
   private int total;
   private int placed;
   private boolean exceededLimit;
   private boolean generationConstraintsFailed;
   private boolean memoryChecked;
   private boolean memoryUnsafe;
   private long blockEntityReserve;
   private boolean failed;
   private boolean recoveryTaskCreated;
   private String failureReason;
   private WorldOperationPhase failurePhase;
   private Iterator<BlockPos> finalizationIterator;

   private PlacementTask(
      CompletableFuture<BlockGenerationResult> future,
      ProgressiveBlockGeneration generationProgress,
      Set<BlockPos> targets,
      PlacementTaskPlan plan,
      MemoryReservation generationReservation
   ) {
      this(future, generationProgress, targets, plan, generationReservation, new WorldJournalPreparation());
   }

   PlacementTask(
      CompletableFuture<BlockGenerationResult> future, ProgressiveBlockGeneration generationProgress,
      Set<BlockPos> targets, PlacementTaskPlan plan, MemoryReservation generationReservation,
      WorldJournalPreparation journalPreparation
   ) {
      this(future, generationProgress, targets, plan, generationReservation, journalPreparation,
         BlockSnapshotReader.live(), BlockTaskMemoryPolicy.standard());
   }

   PlacementTask(CompletableFuture<BlockGenerationResult> future, ProgressiveBlockGeneration generationProgress,
      Set<BlockPos> targets, PlacementTaskPlan plan, MemoryReservation generationReservation,
      WorldJournalPreparation journalPreparation, BlockSnapshotReader snapshots, BlockTaskMemoryPolicy memoryPolicy) {
      super(journalPreparation);
      this.snapshots = java.util.Objects.requireNonNull(snapshots);
      this.memoryPolicy = java.util.Objects.requireNonNull(memoryPolicy);
      this.generation = new PlacementGeneration(future, generationProgress, generationReservation);
      this.targets = targets;
      this.remaining = targets == null ? 0 : targets.size();
      this.total = this.remaining;
      this.validationRemaining = this.remaining;
      this.plan = plan;
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
      task.generation.defer(generator, estimatedBlocks, additionalBlockSets);
      return task;
   }

   public boolean waitingForGenerationMemory() {
      return this.generation.waitingForMemory();
   }

   public boolean prepare() {
      if (this.failed || this.memoryUnsafe) {
         return true;
      }
      if (this.targets != null) {
         this.metrics.phase(WorldOperationPhase.MEMORY_ADMISSION);
         checkMemory();
         if (!this.memoryUnsafe) {
            prepareResolvedStates();
         }
         return true;
      }
      this.metrics.phase(generation.waitingForMemory() ? WorldOperationPhase.MEMORY_ADMISSION : WorldOperationPhase.GENERATION);
      PlacementGeneration.Outcome outcome = generation.poll();
      this.memoryThrottled = generation.throttled();
      if (outcome == PlacementGeneration.Outcome.PENDING) return false;
      if (outcome == PlacementGeneration.Outcome.MEMORY_REJECTED) {
         this.memoryUnsafe = true;
         this.targets = Set.of();
         return true;
      }
      if (outcome == PlacementGeneration.Outcome.FAILED) {
         fail(WorldOperationPhase.GENERATION, generation.failure());
         this.targets = Set.of();
         return true;
      }
      BlockGenerationResult generatedResult = generation.takeResult();
      MemoryReservation generatedReservation = generation.takeReservation();
      if (generatedReservation != null) this.memoryReservation = generatedReservation;
      this.metrics.generationReady();
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
      var admission = memoryPolicy.admit(WorldOperationMemory.snapshotAdmission(this.targets.size(), this.blockEntityReserve));
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
         Optional<ReversibleBlockSnapshot> snapshot = snapshots.capture(level, pos);
         if (snapshot.isEmpty()) {
            fail(WorldOperationPhase.SNAPSHOT, "snapshot validation at " + pos.toShortString());
            return true;
         }
         this.metrics.snapshotCaptured();
         ReversibleBlockSnapshot captured = snapshot.orElseThrow();
         BlockState previous = captured.state();
         if (this.plan.airOnly() && !previous.isAir()) {
            consumed();
            continue;
         }
         this.blockEntityReserve = WorldOperationMemory.saturatingAdd(
            this.blockEntityReserve,
            SnapshotMemoryRules.nbtReserve(captured)
         );
         this.transaction.recordExpected(pos, captured);
         MemoryReservationAttempt capturedReservation = reserveSnapshotMemory();
         if (capturedReservation != MemoryReservationAttempt.ACQUIRED) {
            this.memoryUnsafe = capturedReservation == MemoryReservationAttempt.REJECTED;
            return this.memoryUnsafe;
         }
         if (!this.journalPreparation.started()) {
            int unjournaled = this.transaction.expectedCount() - this.journalSegments.count();
            if (unjournaled >= PersistentRecoveryJournal.segmentCapacity(this.journalSegments.count())) {
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
      int unjournaled = this.transaction.expectedCount() - this.journalSegments.count() - this.journalSegments.pending().size();
      if (unjournaled <= 0) {
         return this.snapshotsValidated;
      }
      if (this.snapshotsValidated) {
         return true;
      }
      int batch = PersistentRecoveryJournal.segmentCapacity(this.journalSegments.count());
      return unjournaled >= batch || !budget.hasRemaining();
   }

   public JournalPreparation prepareJournal(WorldTaskContext context) {
      if (this.transaction.expectedCount() == 0) {
         return JournalPreparation.READY;
      }
      if (this.journalSegments.pending().isEmpty()) {
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
         this.journalSegments.prepare(this.transaction);
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
      List<ReversibleBlockSnapshot> slice = this.journalSegments.pending();
      JournalPreparation preparation = this.journalPreparation.journal() == null
         ? this.journalPreparation.poll(() -> beginJournal(context, registryAccess, slice), context)
         : this.journalPreparation.pollAppend(() -> appendJournal(slice, registryAccess));
      if (preparation == JournalPreparation.FAILED) {
         fail(WorldOperationPhase.JOURNAL, this.journalPreparation.failureReason());
      } else if (preparation == JournalPreparation.READY && !slice.isEmpty()) {
         if (this.journalSegments.count() == 0) {
            this.metrics.journalReady();
         }
         for (ReversibleBlockSnapshot snapshot : slice) {
            this.writable.addLast(snapshot.pos());
         }
         this.journalSegments.acknowledge(slice);
      }
      return preparation;
   }

   private Optional<PersistentRecoveryJournal> beginJournal(
      WorldTaskContext context,
      RegistryAccess registryAccess,
      Collection<ReversibleBlockSnapshot> journalBefore
   ) {
      Collection<ReversibleBlockSnapshot> journalAfter = PlacementJournalPrediction.after(
         registryAccess, journalBefore, this::stateAt, this.plan.conflictMode()
      );
      return context.recovery().begin(
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
      Collection<ReversibleBlockSnapshot> journalAfter = PlacementJournalPrediction.after(
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
      admission = memoryPolicy.admit(admission);
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
      return this.transaction.expectedCount() > this.journalSegments.count() + this.journalSegments.pending().size();
   }

   public boolean snapshotsComplete() {
      return this.snapshotsValidated;
   }

   public boolean readyToFinalize() {
      return this.snapshotsValidated
         && this.journalSegments.pending().isEmpty()
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
         Optional<ReversibleBlockSnapshot> actual = snapshots.capture(level, position);
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
            this.plan.dimension(), this.transaction, this.journalPreparation.journal(), this.journalPreparation.executor()
         );
      }
      JournalPreparation preparation = this.operationCommit.poll();
      if (preparation == JournalPreparation.FAILED) {
         fail(WorldOperationPhase.COMMIT, this.operationCommit.failureReason());
      }
      return preparation;
   }

   public int validationRemaining() {
      return Math.max(0, this.validationRemaining);
   }

   @Override
   protected TaskStep prepareTick(WorldTaskContext context) {
      return PlacementTaskSteps.prepare(this);
   }

   @Override
   public MemoryReservationAttempt reserveWorkingSet() {
      if (ensureMemoryReservation()) return MemoryReservationAttempt.ACQUIRED;
      return memoryUnsafe ? MemoryReservationAttempt.REJECTED : MemoryReservationAttempt.RETRY;
   }

   @Override
   protected WorldTaskBudget createBudget() {
      return WorldTaskBudget.forSmallOperation(memoryThrottled(), total(), previousBatchCells(), previousBatchNanos());
   }

   @Override
   protected TaskStep tickBody(WorldTaskContext context, ServerLevel level, WorldTaskBudget budget) {
      return PlacementTaskSteps.advance(this, context, level, budget);
   }

   @Override
   protected void stopTaskActivity() {
      if (generation.waitingForMemory()) this.targets = Set.of();
      generation.cancel();
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
      admission = memoryPolicy.admit(admission);
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

   /**
    * Drops mutable transaction staging after the compressed history commit has
    * succeeded.  Recovery paths deliberately retain these structures until
    * their outcome is known.
    */
   @Override
   protected void clearCommittedStaging() {
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
      this.journalSegments.clearPending();
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
      return generation.snapshot();
   }

   public void place(WorldTaskContext context, ServerLevel level, BlockPos pos) {
      this.metrics.phase(WorldOperationPhase.WRITE);
      if (this.plan.airOnly() && !level.getBlockState(pos).isAir()) {
         this.metrics.writeAttempt(false);
         return;
      }
      PlacementBlockWriter.Result result = PlacementBlockWriter.write(
         context,
         level,
         pos,
         stateAt(pos),
         this.plan.conflictMode(),
         this.plan.updateMode(),
         this.transaction,
         this.snapshots
      );
      if (result == PlacementBlockWriter.Result.PLACED) {
         this.placed++;
         this.metrics.writeAttempt(true);
      } else if (result == PlacementBlockWriter.Result.FAILED) {
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

   private void fail(WorldOperationPhase phase, String reason) {
      this.failed = true;
      this.failureReason = reason;
      this.failurePhase = phase;
      this.metrics.phase(phase);
   }

}
