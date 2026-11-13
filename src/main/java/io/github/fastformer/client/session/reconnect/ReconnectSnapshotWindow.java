package io.github.fastformer.client.session.reconnect;

/** One connection's bounded snapshot wait. Business drafts and restore decisions stay with its owner. */
public final class ReconnectSnapshotWindow {
   private static final int WAIT_TICKS = 40;
   private boolean armed;
   private boolean received;
   private int idleTicks;

   public enum Result { WAITING, SNAPSHOT_RECEIVED, TIMED_OUT, INACTIVE }

   public void arm() {
      armed = true;
      restartWait();
   }

   public boolean armed() { return armed; }

   public void observeSnapshot() {
      if (armed) received = true;
   }

   /** Restarts the same boundary without arming an inactive one. */
   public void restartWait() {
      received = false;
      idleTicks = 0;
   }

   public Result tick() {
      if (!armed) return Result.INACTIVE;
      if (received) {
         clear();
         return Result.SNAPSHOT_RECEIVED;
      }
      if (++idleTicks >= WAIT_TICKS) {
         clear();
         return Result.TIMED_OUT;
      }
      return Result.WAITING;
   }

   public void clear() {
      armed = false;
      restartWait();
   }
}
