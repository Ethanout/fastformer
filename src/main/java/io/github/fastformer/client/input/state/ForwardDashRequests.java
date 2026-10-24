package io.github.fastformer.client.input.state;

/** Keeps one authorization in flight and remembers another tap without interrupting movement. */
public final class ForwardDashRequests {
   private static final long TIMEOUT_NANOS = 1_000_000_000L;
   private long nextId;
   private long pendingId;
   private long sentAt;
   private boolean queued;

   public long request(long now) {
      expire(now);
      if (pending()) {
         queued = true;
         return 0;
      }
      sentAt = now;
      pendingId = ++nextId;
      return pendingId;
   }

   public boolean accept(long id) {
      if (pendingId == 0 || id != pendingId) return false;
      pendingId = 0;
      return true;
   }

   public long nextQueued(long now) {
      if (!queued || pending()) return 0;
      queued = false;
      return request(now);
   }

   public boolean pending() { return pendingId != 0; }

   public void expire(long now) {
      if (pending() && (now < sentAt || now - sentAt > TIMEOUT_NANOS)) reset();
   }

   public void reset() {
      pendingId = 0;
      queued = false;
   }
}
