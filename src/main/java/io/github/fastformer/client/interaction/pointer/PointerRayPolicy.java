package io.github.fastformer.client.interaction.pointer;

/** Ray purpose during overlapping previews and idle selection transitions. */
public enum PointerRayPolicy {
   OUTLINE, PLACEMENT, SELECTION;

   public static PointerRayPolicy select(boolean geometry, boolean building, boolean workspace,
      boolean selectionEditing, boolean emptyHand, boolean placeable) {
      if (selectionEditing || !geometry && !building && (workspace || emptyHand)) return SELECTION;
      if (!geometry && (building || !workspace && placeable)) return PLACEMENT;
      return OUTLINE;
   }
}
