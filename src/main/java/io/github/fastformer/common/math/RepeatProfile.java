package io.github.fastformer.common.math;

/** Frozen repeat rule for one gizmo gesture, resolved from its immutable baseline. */
public record RepeatProfile(boolean wholeGroup, int stride) { }
