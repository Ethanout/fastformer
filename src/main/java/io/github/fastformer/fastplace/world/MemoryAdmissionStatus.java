package io.github.fastformer.fastplace.world;

/** Result of a memory preflight, separating throttling from hard rejection. */
public enum MemoryAdmissionStatus {
   ALLOWED,
   SOFT_PRESSURE,
   HARD_REJECTED
}
