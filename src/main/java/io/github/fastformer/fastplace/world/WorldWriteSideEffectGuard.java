package io.github.fastformer.fastplace.world;

import java.util.ArrayDeque;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/** Prevents item-entity duplication caused by container onRemove callbacks. */
public final class WorldWriteSideEffectGuard {
   private static final ThreadLocal<ArrayDeque<Target>> SUPPRESSED_DROPS = new ThreadLocal<>();

   private WorldWriteSideEffectGuard() {
   }

   public static boolean setBlock(ServerLevel level, BlockPos pos, BlockState state, int flags) {
      try (Suppression ignored = suppress(level, pos)) {
         return level.setBlock(pos, state, flags);
      }
   }

   public static void onEntityJoin(EntityJoinLevelEvent event) {
      if (!event.loadedFromDisk()
         && (event.getEntity() instanceof ItemEntity || event.getEntity() instanceof ExperienceOrb)
         && suppresses(event.getLevel(), event.getEntity().blockPosition())) {
         event.setCanceled(true);
      }
   }

   static Suppression suppress(Object level, BlockPos pos) {
      ArrayDeque<Target> stack = SUPPRESSED_DROPS.get();
      if (stack == null) {
         stack = new ArrayDeque<>();
         SUPPRESSED_DROPS.set(stack);
      }
      Target target = new Target(level, pos.immutable());
      stack.addLast(target);
      return () -> {
         ArrayDeque<Target> current = SUPPRESSED_DROPS.get();
         if (current != null) {
            current.removeLastOccurrence(target);
         }
         if (current == null || current.isEmpty()) {
            SUPPRESSED_DROPS.remove();
         }
      };
   }

   static boolean suppresses(Object level, BlockPos pos) {
      if (level == null || pos == null) {
         return false;
      }
      ArrayDeque<Target> targets = SUPPRESSED_DROPS.get();
      if (targets == null) {
         return false;
      }
      for (Target target : targets) {
         if (target.level() == level && target.pos().equals(pos)) {
            return true;
         }
      }
      return false;
   }

   @FunctionalInterface
   interface Suppression extends AutoCloseable {
      @Override
      void close();
   }

   private record Target(Object level, BlockPos pos) {
   }
}
