package io.github.fastformer.client.render.model;

/** Selects the preview that owns world rendering during snapshot transitions. */
public enum PreviewRenderOwner {
   NONE,
   BUILDING,
   OPERATION,
   GEOMETRY;

   public static PreviewRenderOwner select(
      boolean buildingActive,
      boolean operationActive,
      boolean workspaceActive,
      boolean geometryActive
   ) {
      if (geometryActive) {
         return GEOMETRY;
      }
      if (buildingActive) {
         return BUILDING;
      }
      return operationActive || workspaceActive ? OPERATION : NONE;
   }
}
