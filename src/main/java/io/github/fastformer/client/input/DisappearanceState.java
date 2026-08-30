package io.github.fastformer.client.input;

/**
 * Tick-based visibility gate for the near-vanilla transition. Zero is fully
 * visible; capacity is fully disappeared. The state deliberately has no
 * gameplay knowledge so its condition and applicable sessions can evolve.
 */
public final class DisappearanceState {
   private final int capacity;
   private int count;
   private int previousCount;
   private boolean disappeared;

   public DisappearanceState(int capacity) {
      this.capacity = Math.max(1, capacity);
   }

   public void tick(boolean eligible, boolean condition) {
      previousCount = count;
      if (!eligible) {
         count = Math.max(0, count - 1);
      } else if (condition) {
         count = Math.min(capacity, count + 1);
      } else {
         count = Math.max(0, count - 1);
      }
      if (count == capacity) {
         disappeared = true;
      } else if (count == 0) {
         disappeared = false;
      }
   }

   public boolean disappeared() {
      return disappeared;
   }

   public float visibility() {
      return 1.0F - (float) count / (float) capacity;
   }

   /** Returns visibility interpolated from the previous client tick. */
   public float visibility(float partialTick) {
      float amount = Math.clamp(partialTick, 0.0F, 1.0F);
      float interpolatedCount = previousCount + (count - previousCount) * amount;
      return 1.0F - interpolatedCount / (float) capacity;
   }

   public void reset() {
      count = 0;
      previousCount = 0;
      disappeared = false;
   }

   public int count() {
      return count;
   }
}
