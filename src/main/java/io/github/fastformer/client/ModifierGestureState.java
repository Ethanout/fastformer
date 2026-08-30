package io.github.fastformer.client;

/** Single lifecycle for the Alt modifier and the gesture that consumes it. */
public final class ModifierGestureState {
   private Phase phase = Phase.IDLE;
   private long pressedAt;
   private boolean routed;
   private boolean cycleEligible;

   public boolean held() {
      return this.phase != Phase.IDLE;
   }

   public boolean consumed() {
      return this.phase == Phase.CONSUMED_BY_GESTURE;
   }

   public void press(long now, boolean cycleEligible, boolean routed) {
      this.phase = Phase.HELD_UNCONSUMED;
      this.pressedAt = now;
      this.cycleEligible = cycleEligible;
      this.routed = routed;
   }

   public void consume() {
      if (this.phase == Phase.HELD_UNCONSUMED) {
         this.phase = Phase.CONSUMED_BY_GESTURE;
      }
   }

   public Release release(long now, long shortPressNanos) {
      Release result = new Release(
         this.routed,
         this.cycleEligible,
         !this.consumed() && now - this.pressedAt <= shortPressNanos
      );
      this.reset();
      return result;
   }

   public void reset() {
      this.phase = Phase.IDLE;
      this.pressedAt = 0L;
      this.routed = false;
      this.cycleEligible = false;
   }

   public boolean routed() {
      return this.routed;
   }

   public enum Phase {
      IDLE,
      HELD_UNCONSUMED,
      CONSUMED_BY_GESTURE
   }

   public record Release(boolean routed, boolean cycleEligible, boolean shortPress) {
   }
}
