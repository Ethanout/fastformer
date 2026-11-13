package io.github.fastformer.server.recovery.gate;

/** One server's write admission. A stale recovery or a discarded runtime cannot reopen it. */
public final class RecoveryWriteGate implements AutoCloseable {
   private volatile boolean open = true;
   private boolean closed;
   private long revision;

   public boolean writesAllowed() { return open; }

   public synchronized void blockNewWrites() {
      open = false;
      revision++;
   }

   public synchronized long beginRecovery() {
      open = false;
      return ++revision;
   }

   public synchronized void finishRecovery(long startedRevision, boolean successful) {
      if (!closed && successful && startedRevision == revision) open = true;
   }

   @Override public synchronized void close() {
      closed = true;
      open = false;
      revision++;
   }
}
