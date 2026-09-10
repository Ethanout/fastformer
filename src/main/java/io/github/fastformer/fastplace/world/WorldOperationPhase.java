package io.github.fastformer.fastplace.world;

/** Stable lifecycle phases shared by placement, history, and recovery logs. */
public enum WorldOperationPhase {
   GENERATION,
   MEMORY_ADMISSION,
   SNAPSHOT,
   JOURNAL,
   WRITE,
   FINALIZE,
   ROLLBACK,
   COMMIT,
   WORLD_UNLOADED,
   COMPLETE,
   FAILED
}
