package io.github.fastformer.common.placement;

import java.util.function.Supplier;
import net.minecraft.world.level.Level;

/** Carries the player's update policy through a block interaction and its scheduled ticks. */
public final class InteractionUpdateScope {
   private static final ThreadLocal<Level> ACTIVE = new ThreadLocal<>();
   private static final ThreadLocal<Boolean> SCHEDULED = new ThreadLocal<>();

   private InteractionUpdateScope() {}

   public static Level currentLevel() { return ACTIVE.get(); }
   public static boolean suppresses(Level level) { return ACTIVE.get() == level || Boolean.TRUE.equals(SCHEDULED.get()); }
   public static boolean protectsScheduledTicks() { return ACTIVE.get() != null || Boolean.TRUE.equals(SCHEDULED.get()); }

   public static <T> T runScheduled(Supplier<T> action) {
      Boolean previous = SCHEDULED.get();
      SCHEDULED.set(true);
      try { return action.get(); }
      finally {
         if (previous == null) SCHEDULED.remove();
         else SCHEDULED.set(previous);
      }
   }

   public static <T> T interact(Level level, boolean suppressNeighbors, Supplier<T> action) {
      return suppressNeighbors ? run(level, action) : action.get();
   }

   public static <T> T run(Level level, Supplier<T> action) {
      Level previous = ACTIVE.get();
      ACTIVE.set(level);
      try {
         return action.get();
      } finally {
         if (previous == null) ACTIVE.remove();
         else ACTIVE.set(previous);
      }
   }
}
