package io.github.fastformer.client.session.preview;

import io.github.fastformer.common.geometry.ConePrismStage;
import io.github.fastformer.protocol.s2c.geometry.GeometryPreviewPayload;

/** Geometry workflow continuity, independent of whether a frame was drawn between updates. */
public final class GeometryPreviewRules {
   private GeometryPreviewRules() {}

   public static boolean continuesTransform(GeometryPreviewPayload previous, GeometryPreviewPayload next) {
      return previous != null && next != null && previous.mode() == next.mode()
         && adjusting(previous) && adjusting(next);
   }

   public static boolean adjusting(GeometryPreviewPayload payload) {
      if (payload == null || !payload.active()) return false;
      return switch (payload.mode()) {
         case POLYHEDRON -> payload.closed();
         case CONE_PRISM -> payload.conePlaneMode().stageFor(payload.points().size()) == ConePrismStage.ADJUST;
         default -> false;
      };
   }
}
