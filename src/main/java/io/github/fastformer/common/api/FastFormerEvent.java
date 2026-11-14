package io.github.fastformer.common.api;

import net.neoforged.bus.api.Event;

/** Immutable, non-cancellable notification of an already accepted server-side change. */
public abstract sealed class FastFormerEvent extends Event permits ShapeCommitted, HistoryRecorded, ModeChanged {
}
