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

   /** For synchronous callers that cannot queue a reservation retry. */
   public boolean fitsCurrentHeap() {
      return allowed() && requestedBytes <= usableBytes;
   }
}
