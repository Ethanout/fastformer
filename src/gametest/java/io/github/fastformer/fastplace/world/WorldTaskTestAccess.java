package io.github.fastformer.fastplace.world;

public final class WorldTaskTestAccess {
   private WorldTaskTestAccess() {}

   public static boolean tickSafely(String name, Runnable tick) {
      return WorldTaskFeature.tickSafely(name, tick);
   }

   public static WorldTaskBudget singleCellBudget() {
      return WorldTaskBudget.testing(1, 1, 0L, () -> 0L);
   }
}
