package io.github.fastformer.client.feedback;

import io.github.fastformer.common.geometry.gizmo.AxisGizmo;
import io.github.fastformer.core.math.Axis;

/** One immutable observation of all transient adjustment timers. */
public record FeedbackFrame(boolean persistentFreeScroll, ScrollFeedbackData scroll, int scrollAlpha,
   int gizmoAlpha, Axis lastAxis, AxisGizmo.Operation lastOperation, int lastSteps, double lastBaseValue) {
   public static final FeedbackFrame EMPTY = new FeedbackFrame(false, null, 0, 0, null, null, 0, 0);
}
