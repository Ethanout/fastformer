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
   private Object target;
   private int dwellTicks;
   private boolean accelerated;

   public DisappearanceState(int capacity) {
      this.capacity = Math.max(1, capacity);
   }

   public void tick(boolean eligible, boolean condition) {
      tick(eligible, condition, null);
   }

   /** Advances visibility and accelerates only while the same target remains under the crosshair. */
   public void tick(boolean eligible, boolean condition, Object currentTarget) {
      previousCount = count;
      if (currentTarget == null || !currentTarget.equals(target)) {
         target = currentTarget;
         dwellTicks = 0;
         accelerated = false;
      } else {
         dwellTicks++;
         accelerated = dwellTicks >= 2;
      }
      int rate = accelerated ? 3 : 1;
      if (!eligible) {
         count = Math.max(0, count - rate);
      } else if (condition) {
         count = Math.min(capacity, count + rate);
      } else {
         count = Math.max(0, count - rate);
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
      target = null;
      dwellTicks = 0;
      accelerated = false;
   }

   public int count() {
      return count;
   }

   public boolean accelerated() {
      return accelerated;
   }
}
