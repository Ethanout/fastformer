package io.github.fastformer.server.task;

/** Recovery keeps the working set until handover; shutdown leaves durable journals for restart. */
public enum CancelReason {
   RECOVERY,
   SHUTDOWN
}
