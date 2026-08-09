package io.github.fastformer.fastplace;

import java.util.EnumSet;
import java.util.function.Supplier;

public final class SessionValue<T> {
   private final Supplier<T> initial;
   private final EnumSet<ResetOn> resetOn;
   private T value;

   public SessionValue(Supplier<T> initial, ResetOn... resetOn) {
      this.initial = initial;
      this.resetOn = resetOn.length == 0 ? EnumSet.noneOf(ResetOn.class) : EnumSet.of(resetOn[0], resetOn);
      this.value = initial.get();
   }

   public T get() {
      return this.value;
   }

   public void set(T value) {
      this.value = value;
   }

   public void reset(ResetOn moment) {
      if (this.resetOn.contains(moment)) {
         this.value = this.initial.get();
      }
   }

   public enum ResetOn {
      SUBMODE_CHANGE,
      MODE_CHANGE,
      STAGE_CHANGE,
      DESTROY
   }
}
