package io.github.fastformer.server.task;

public final class WorldTaskSchedulerTestAccess {
   private WorldTaskSchedulerTestAccess() {}

   public static boolean tickSafely(String name, Runnable tick) {
      return WorldTaskScheduler.tickSafely(name, tick);
   }
}
