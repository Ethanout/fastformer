package io.github.fastformer.client.mode;

/** Frozen visibility facts; visual priority may differ from input ownership during snapshot transitions. */
public record PreviewObservation(
   boolean fill, boolean replacement, boolean idle, boolean geometry, boolean quickShape,
   boolean selection, boolean selectionCandidate
) {}
