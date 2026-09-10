package io.github.fastformer.fastplace.world;

/** Immutable memory preflight result used by schedulers and task owners. */
public record MemoryAdmission(
   MemoryAdmissionStatus status,
   long requestedBytes,
   long usableBytes
) {
   public boolean allowed() {
      return this.status != MemoryAdmissionStatus.HARD_REJECTED;
   }

   public boolean throttled() {
      return this.status == MemoryAdmissionStatus.SOFT_PRESSURE;
   }
}
