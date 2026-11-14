package io.github.fastformer.client.preview.geometry;

import io.github.fastformer.client.render.model.GeometryRenderLayers;
import io.github.fastformer.common.geometry.gizmo.AxisGizmo;
import io.github.fastformer.common.geometry.preview.GeometryPreviewPlan;
import java.util.Objects;

/** The cached geometry plan and its block membership, with per-frame gizmo presentation. */
public record GeometryPreviewModel(GeometryPreviewPlan plan, GeometryRenderLayers blocks) {
   public GeometryPreviewModel {
      Objects.requireNonNull(plan, "plan");
      Objects.requireNonNull(blocks, "blocks");
   }

   public GeometryPreviewModel withGizmo(AxisGizmo gizmo) {
      return gizmo == plan.gizmo() ? this : new GeometryPreviewModel(plan.withGizmo(gizmo), blocks);
   }
}
