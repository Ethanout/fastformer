package io.github.fastformer.client.input.policy;

import io.github.fastformer.client.input.ModifierReticleMode;
import io.github.fastformer.client.quickshape.pointer.QuickShapeModes;
import io.github.fastformer.common.preview.quickshape.BuildingPreviewSnapshot;
import io.github.fastformer.fastplace.geometry.GeometryMode;
import io.github.fastformer.fastplace.geometry.cone.ConePrismStage;
import io.github.fastformer.protocol.s2c.geometry.GeometryPreviewPayload;

/** Pointer hint semantics; drawing only consumes the resulting reticle mode. */
public final class ModifierReticlePolicy {
   private ModifierReticlePolicy() {}

   public static ModifierReticleMode resolve(BuildingPreviewSnapshot building, GeometryPreviewPayload geometry,
      boolean selectionPreview, boolean idle, boolean creative, boolean replacement, boolean ownCamera, boolean placeable) {
      boolean embedded = idle ? creative && building.enabled() && !replacement && ownCamera && placeable
         : geometry.active() ? geometry.mode() == GeometryMode.WALL
         : !selectionPreview && QuickShapeModes.raycastSubmode(building);
      if (embedded) return ModifierReticleMode.EMBEDDED;
      if (!geometry.active()) return ModifierReticleMode.NONE;
      boolean half = switch (geometry.mode()) {
         case POLYHEDRON -> !geometry.closed();
         case CONE_PRISM -> geometry.conePlaneMode().stageFor(geometry.points().size()) != ConePrismStage.ADJUST;
         case CONVEX_POLYHEDRON -> true;
         default -> false;
      };
      return half ? ModifierReticleMode.HALF_GRID : ModifierReticleMode.NONE;
   }
}
