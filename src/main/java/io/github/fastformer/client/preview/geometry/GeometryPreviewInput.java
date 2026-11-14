package io.github.fastformer.client.preview.geometry;

import io.github.fastformer.common.geometry.preview.GeometryPreviewPlan;
import io.github.fastformer.protocol.s2c.geometry.GeometryPreviewPayload;
import java.util.Objects;

/** Geometry and pointer facts captured before plan generation; no live client lookup. */
public record GeometryPreviewInput(GeometryPreviewPayload snapshot, boolean modifierHeld, GeometryPreviewPlan plan) {
   public GeometryPreviewInput {
      Objects.requireNonNull(snapshot, "snapshot");
      Objects.requireNonNull(plan, "plan");
   }
}
