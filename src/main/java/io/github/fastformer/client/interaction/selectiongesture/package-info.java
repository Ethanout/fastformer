/**
 * Local selection transform gestures. The capability owns the immutable face or
 * gizmo state, workspace edit lease, and borrowed environment pointer token.
 * Input supplies frozen presses and sampled rays; rendering receives snapshots.
 * Cancellation and late releases always identify the original capture. A resize
 * still awaiting blocks transfers its edit lease to the transform completion.
 */
package io.github.fastformer.client.interaction.selectiongesture;
