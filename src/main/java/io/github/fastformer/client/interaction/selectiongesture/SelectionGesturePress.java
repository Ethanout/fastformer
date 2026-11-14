package io.github.fastformer.client.interaction.selectiongesture;

import io.github.fastformer.common.geometry.gizmo.AxisGizmo;

/** Input facts already validated against the owning interaction scene at the boundary. */
public sealed interface SelectionGesturePress {
   record Part(int partId, boolean control) implements SelectionGesturePress { }
   record Face(int partId, SelectionFaceHit hit, boolean adjustable, int button, int shortPressSteps,
      long pressedAtNanos) implements SelectionGesturePress { }
   record Gizmo(int partId, boolean common, AxisGizmo gizmo, AxisGizmo.Hit hit, int button,
      boolean control) implements SelectionGesturePress { }
}
