package io.github.fastformer.fastplace.world;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** A bounded, releasable reservation for one operation's working set. */
public final class MemoryReservation implements AutoCloseable {
   private static final AtomicLong RESERVED_BYTES = new AtomicLong();

   private long bytes;
   private boolean closed;

   private MemoryReservation(long bytes) {
      this.bytes = bytes;
   }

   static Optional<MemoryReservation> tryAcquire(long bytes, long usableBytes) {
      if (bytes < 0L || usableBytes < 0L || bytes > usableBytes) {
         return Optional.empty();
      }
      while (true) {
         long current = RESERVED_BYTES.get();
         if (current > usableBytes - bytes) {
            return Optional.empty();
         }
         if (RESERVED_BYTES.compareAndSet(current, current + bytes)) {
            return Optional.of(new MemoryReservation(bytes));
         }
      }
   }

   /** Returns the aggregate bytes reserved by active operations. */
   public static long reservedBytes() {
      return RESERVED_BYTES.get();
   }

   public synchronized boolean resize(long newBytes, long usableBytes) {
      if (this.closed || newBytes < 0L || usableBytes < 0L) {
         return false;
      }
      long delta = newBytes - this.bytes;
      if (delta > 0L) {
         if (delta > usableBytes) {
            return false;
         }
         while (true) {
            long current = RESERVED_BYTES.get();
            if (current > usableBytes - delta) {
               return false;
            }
            if (RESERVED_BYTES.compareAndSet(current, current + delta)) {
               this.bytes = newBytes;
               return true;
            }
         }
      }
      while (true) {
         long current = RESERVED_BYTES.get();
         long next = Math.max(0L, current + delta);
         if (RESERVED_BYTES.compareAndSet(current, next)) {
            break;
         }
      }
      this.bytes = newBytes;
      return true;
   }

   public synchronized long bytes() {
      return this.bytes;
   }

   @Override
   public synchronized void close() {
      if (this.closed) {
         return;
      }
      RESERVED_BYTES.addAndGet(-this.bytes);
      this.bytes = 0L;
      this.closed = true;
   }
}
