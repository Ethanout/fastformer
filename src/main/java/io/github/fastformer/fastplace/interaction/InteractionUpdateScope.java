package io.github.fastformer.fastplace.interaction;

import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/** Carries the player's update policy through a block interaction and its scheduled ticks. */
public final class InteractionUpdateScope {
   private static final ThreadLocal<Level> ACTIVE = new ThreadLocal<>();
   private static final ThreadLocal<Boolean> SCHEDULED = new ThreadLocal<>();
   private static final java.util.Map<Player, Boolean> CLIENT_POLICIES = new java.util.WeakHashMap<>();

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

   public static void setClientPolicy(Player player, boolean suppressNeighbors) {
      CLIENT_POLICIES.put(player, suppressNeighbors);
   }

   public static <T> T interact(Level level, Player player, Supplier<T> action) {
      if (player == null || !player.isCreative()) return action.get();
      if (level.isClientSide) {
         return Boolean.TRUE.equals(CLIENT_POLICIES.get(player)) ? run(level, action) : action.get();
      }
      if (!(player instanceof ServerPlayer serverPlayer)) return action.get();
      FastPlaceSettings settings = FastPlaceSettings.load(serverPlayer);
      if (!settings.enabled() || !settings.placementUpdateMode().suppressesNeighborUpdates()) return action.get();
      return run(level, action);
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
