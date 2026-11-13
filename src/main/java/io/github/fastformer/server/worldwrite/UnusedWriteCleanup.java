package io.github.fastformer.server.worldwrite;

/** Durable preparation that must be discarded before its write lease can be reused. */
@FunctionalInterface
public interface UnusedWriteCleanup {
   boolean discardUnused();
}
