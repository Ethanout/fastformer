package io.github.fastformer.fastplace.world;

/** Conservative heap preflight for snapshot-heavy reversible operations. */
public final class WorldOperationMemory {
   private static final long MINIMUM_RESERVE = 256L * 1024L * 1024L;
   private static final long SNAPSHOT_BYTES_PER_BLOCK = 224L;
   private static final long PACKED_GENERATED_BYTES_PER_BLOCK = 32L;
   private static final long OBJECT_SET_BYTES_PER_BLOCK = 64L;
   private static final long JOURNAL_BUFFER_BYTES_PER_BLOCK = Long.BYTES + 2L * Integer.BYTES;
   private static final long COMMIT_BUFFER_BYTES_PER_BLOCK = 96L;
   private static final long RECOVERY_TRACKING_BYTES_PER_BLOCK = 1L;

   private WorldOperationMemory() {
   }

   /** Classifies memory pressure without collapsing throttling into rejection. */
   public static MemoryAdmission admission(long blocks, long additionalBytes) {
      return snapshotAdmission(blocks, additionalBytes);
   }

   /** Classifies the live target and before-snapshot working set. */
   public static MemoryAdmission snapshotAdmission(long blocks, long blockEntityBytes) {
      Runtime runtime = Runtime.getRuntime();
      return snapshotAdmission(
         blocks, blockEntityBytes, runtime.maxMemory(), runtime.totalMemory(), runtime.freeMemory()
      );
   }

   /** Includes the temporary packed positions and palette IDs created by journal encoding. */
   public static MemoryAdmission journalAdmission(long blocks, long blockEntityBytes) {
      Runtime runtime = Runtime.getRuntime();
      return journalAdmission(
         blocks, blockEntityBytes, runtime.maxMemory(), runtime.totalMemory(), runtime.freeMemory()
      );
   }

   /** Classifies mutable before/after snapshots held while a batch is compressed. */
   public static MemoryAdmission transactionAdmission(long snapshotCount, long blockEntityBytes) {
      return snapshotAdmission(snapshotCount, blockEntityBytes);
   }

   /** Includes the primitive map, sorted positions and palette ID arrays used while sealing a batch. */
   public static MemoryAdmission commitAdmission(long snapshotCount, long changedCells, long blockEntityBytes) {
      Runtime runtime = Runtime.getRuntime();
      return commitAdmission(
         snapshotCount,
         changedCells,
         blockEntityBytes,
         runtime.maxMemory(),
         runtime.totalMemory(),
         runtime.freeMemory()
      );
   }

   /** Uses an already-compressed batch estimate without charging snapshot objects again. */
   public static MemoryAdmission historyAdmission(long batchBytes) {
      Runtime runtime = Runtime.getRuntime();
      return bytesAdmission(batchBytes, runtime.maxMemory(), runtime.totalMemory(), runtime.freeMemory());
   }

   /** Adds active recovery tracking and, when needed, journal encoding buffers to a compressed batch. */
   public static MemoryAdmission recoveryAdmission(long batchBytes, long cells, boolean createsJournal) {
      Runtime runtime = Runtime.getRuntime();
      return recoveryAdmission(
         batchBytes, cells, createsJournal, runtime.maxMemory(), runtime.totalMemory(), runtime.freeMemory()
      );
   }

   /** Classifies only temporary recovery structures, not the compressed batch itself. */
   public static MemoryAdmission recoveryWorkingSetAdmission(long cells, boolean createsJournal) {
      Runtime runtime = Runtime.getRuntime();
      return recoveryWorkingSetAdmission(
         cells, createsJournal, runtime.maxMemory(), runtime.totalMemory(), runtime.freeMemory()
      );
   }

   public static java.util.Optional<MemoryReservation> reserve(long blocks, long additionalBytes) {
      MemoryAdmission admission = admission(blocks, additionalBytes);
      return reserve(admission);
   }

   /** Acquires exactly the bytes and threshold from one already-classified phase. */
   public static java.util.Optional<MemoryReservation> reserve(MemoryAdmission admission) {
      if (admission == null || !admission.allowed()) {
         return java.util.Optional.empty();
      }
      return MemoryReservation.tryAcquire(admission.requestedBytes(), admission.usableBytes());
   }

   /** Reserves only the generation working set; transaction memory is acquired later. */
   public static java.util.Optional<MemoryReservation> reserveGeneration(long estimatedBlocks, long additionalBlockSets) {
      MemoryAdmission admission = generationAdmission(estimatedBlocks, additionalBlockSets);
      return reserve(admission);
   }

   static MemoryAdmission admission(long blocks, long additionalBytes, long maxMemory, long totalMemory, long freeMemory) {
      return snapshotAdmission(blocks, additionalBytes, maxMemory, totalMemory, freeMemory);
   }

   static MemoryAdmission snapshotAdmission(
      long blocks,
      long blockEntityBytes,
      long maxMemory,
      long totalMemory,
      long freeMemory
   ) {
      return bytesAdmission(snapshotBytes(blocks, blockEntityBytes), maxMemory, totalMemory, freeMemory);
   }

   static MemoryAdmission journalAdmission(
      long blocks,
      long blockEntityBytes,
      long maxMemory,
      long totalMemory,
      long freeMemory
   ) {
      long snapshotBytes = snapshotBytes(blocks, blockEntityBytes);
      long journalBytes = multiplySaturated(blocks, JOURNAL_BUFFER_BYTES_PER_BLOCK);
      return bytesAdmission(saturatingAdd(snapshotBytes, journalBytes), maxMemory, totalMemory, freeMemory);
   }

   static MemoryAdmission commitAdmission(
      long snapshotCount,
      long changedCells,
      long blockEntityBytes,
      long maxMemory,
      long totalMemory,
      long freeMemory
   ) {
      if (changedCells < 0L) {
         return new MemoryAdmission(MemoryAdmissionStatus.HARD_REJECTED, Long.MAX_VALUE, 0L);
      }
      long snapshots = snapshotBytes(snapshotCount, blockEntityBytes);
      long buffers = multiplySaturated(changedCells, COMMIT_BUFFER_BYTES_PER_BLOCK);
      return bytesAdmission(saturatingAdd(snapshots, buffers), maxMemory, totalMemory, freeMemory);
   }

   static MemoryAdmission historyAdmission(
      long batchBytes,
      long maxMemory,
      long totalMemory,
      long freeMemory
   ) {
      return bytesAdmission(batchBytes, maxMemory, totalMemory, freeMemory);
   }

   static MemoryAdmission recoveryAdmission(
      long batchBytes,
      long cells,
      boolean createsJournal,
      long maxMemory,
      long totalMemory,
      long freeMemory
   ) {
      if (batchBytes < 0L || cells < 0L) {
         return new MemoryAdmission(MemoryAdmissionStatus.HARD_REJECTED, Long.MAX_VALUE, 0L);
      }
      long requested = saturatingAdd(
         batchBytes,
         multiplySaturated(cells, RECOVERY_TRACKING_BYTES_PER_BLOCK)
      );
      if (createsJournal) {
         requested = saturatingAdd(requested, multiplySaturated(cells, JOURNAL_BUFFER_BYTES_PER_BLOCK));
      }
      return bytesAdmission(requested, maxMemory, totalMemory, freeMemory);
   }

   static MemoryAdmission recoveryWorkingSetAdmission(
      long cells,
      boolean createsJournal,
      long maxMemory,
      long totalMemory,
      long freeMemory
   ) {
      if (cells < 0L) {
         return new MemoryAdmission(MemoryAdmissionStatus.HARD_REJECTED, Long.MAX_VALUE, 0L);
      }
      long requested = multiplySaturated(cells, RECOVERY_TRACKING_BYTES_PER_BLOCK);
      if (createsJournal) {
         requested = saturatingAdd(requested, multiplySaturated(cells, JOURNAL_BUFFER_BYTES_PER_BLOCK));
      }
      return bytesAdmission(requested, maxMemory, totalMemory, freeMemory);
   }

   private static MemoryAdmission bytesAdmission(
      long requested,
      long maxMemory,
      long totalMemory,
      long freeMemory
   ) {
      if (requested < 0L || requested == Long.MAX_VALUE) {
         return new MemoryAdmission(MemoryAdmissionStatus.HARD_REJECTED, Long.MAX_VALUE, 0L);
      }
      long used = Math.max(0L, totalMemory - freeMemory);
      long available = Math.max(0L, maxMemory - used);
      long hardReserve = Math.max(MINIMUM_RESERVE, maxMemory / 8L);
      long softReserve = Math.max(64L * 1024L * 1024L, hardReserve / 2L);
      long hardUsable = Math.max(0L, available - hardReserve);
      long softUsable = Math.max(0L, available - softReserve);
      MemoryAdmissionStatus status = requested <= hardUsable
         ? MemoryAdmissionStatus.ALLOWED
         : requested <= softUsable ? MemoryAdmissionStatus.SOFT_PRESSURE : MemoryAdmissionStatus.HARD_REJECTED;
      long reservationLimit = status == MemoryAdmissionStatus.SOFT_PRESSURE ? softUsable : hardUsable;
      return new MemoryAdmission(status, requested, reservationLimit);
   }

   /** Classifies the combined generation and transaction working set. */
   public static MemoryAdmission generationAdmission(long estimatedBlocks, long additionalBlockSets) {
      Runtime runtime = Runtime.getRuntime();
      return generationAdmission(
         estimatedBlocks,
         additionalBlockSets,
         runtime.maxMemory(),
         runtime.totalMemory(),
         runtime.freeMemory()
      );
   }

   static MemoryAdmission generationAdmission(
      long estimatedBlocks,
      long additionalBlockSets,
      long maxMemory,
      long totalMemory,
      long freeMemory
   ) {
      if (estimatedBlocks < 0L || additionalBlockSets < 0L) {
         return new MemoryAdmission(MemoryAdmissionStatus.HARD_REJECTED, Long.MAX_VALUE, 0L);
      }
      long generatedBytes = estimatedGenerationBytes(estimatedBlocks, additionalBlockSets);
      // Generation admission covers only the representations that are alive
      // while geometry/effects are produced. Snapshot and journal memory are
      // acquired later, after generation state is released; charging both
      // phases here causes false hard rejections for large but batchable work.
      long total = generatedBytes;
      if (total == Long.MAX_VALUE) {
         return new MemoryAdmission(MemoryAdmissionStatus.HARD_REJECTED, total, 0L);
      }
      return bytesAdmission(total, maxMemory, totalMemory, freeMemory);
   }

   public static long saturatingAdd(long first, long second) {
      if (first < 0L || second < 0L || first > Long.MAX_VALUE - second) {
         return Long.MAX_VALUE;
      }
      return first + second;
   }

   private static long multiplySaturated(long first, long second) {
      if (first < 0L || second < 0L || (first != 0L && second > Long.MAX_VALUE / first)) {
         return Long.MAX_VALUE;
      }
      return first * second;
   }

   private static long snapshotBytes(long blocks, long blockEntityBytes) {
      if (blocks < 0L || blockEntityBytes < 0L) {
         return Long.MAX_VALUE;
      }
      return saturatingAdd(multiplySaturated(blocks, SNAPSHOT_BYTES_PER_BLOCK), blockEntityBytes);
   }

   static long estimatedGenerationBytes(long estimatedBlocks, long additionalBlockSets) {
      if (estimatedBlocks < 0L || additionalBlockSets < 0L) {
         return Long.MAX_VALUE;
      }
      long packedOutput = multiplySaturated(estimatedBlocks, PACKED_GENERATED_BYTES_PER_BLOCK);
      long additionalSets = multiplySaturated(estimatedBlocks, additionalBlockSets);
      additionalSets = multiplySaturated(additionalSets, OBJECT_SET_BYTES_PER_BLOCK);
      return saturatingAdd(packedOutput, additionalSets);
   }

   public static long snapshotNbtReserve(ReversibleBlockSnapshot snapshot) {
      if (snapshot == null || snapshot.blockEntity() == null) {
         return 0L;
      }
      long bytes = snapshot.blockEntity().estimatedBytes();
      return bytes > Long.MAX_VALUE / 3L ? Long.MAX_VALUE : bytes * 3L;
   }

   /** Measures block-entity payloads actually retained by a snapshot collection. */
   public static long snapshotNbtReserve(Iterable<ReversibleBlockSnapshot> snapshots) {
      if (snapshots == null) {
         return 0L;
      }
      long bytes = 0L;
      for (ReversibleBlockSnapshot snapshot : snapshots) {
         bytes = saturatingAdd(bytes, snapshotNbtReserve(snapshot));
      }
      return bytes;
   }
}
