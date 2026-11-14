package io.github.fastformer.client.render.core;

import io.github.fastformer.client.render.cache.*;
import io.github.fastformer.client.render.mesh.GhostMeshBuilder;
import io.github.fastformer.client.render.model.PlacementModelPreview;
import io.github.fastformer.client.lifecycle.ClientBackgroundTasks;
import java.util.concurrent.ThreadPoolExecutor;

/** Owns GPU caches and worker lifetimes for the preview renderer. */
final class PreviewRenderResources {
   private PreviewRenderResources() { }
   static final PlacementModelPreview INITIAL_MODEL = new PlacementModelPreview();
   static final GhostMeshCache CONFIRMED_GHOST_CACHE = new GhostMeshCache(blocks -> GhostMeshBuilder.build(blocks, true, true, true));
   static final GhostMeshCache CONFIRMED_OUTLINE_CACHE = new GhostMeshCache(blocks -> GhostMeshBuilder.build(blocks, false, true, true));
   static final ThreadPoolExecutor PREVIEW_MESH_EXECUTOR = ClientBackgroundTasks.serial("preview-mesh", 16);
   static final PendingGhostMeshCache PENDING_GHOST_CACHE = new PendingGhostMeshCache(PREVIEW_MESH_EXECUTOR,
      GhostMeshBuilder::buildPending, failure -> FastPlaceClientPreviewCore.logPreviewFailure("Unable to build FastFormer preview mesh", failure));
   static final PendingGhostBufferCache PENDING_GHOST_BUFFER_CACHE = new PendingGhostBufferCache(FastPlaceClientPreviewCore::uploadPendingGrid);

   static void clearShells() {
      INITIAL_MODEL.clear();
      io.github.fastformer.client.render.PreviewMaterialRenderer.clear();
      SmartSelectionRenderer.clear();
      SmartSelectionIsolationRenderer.clear();
   }

   static void clearMeshes() {
      CONFIRMED_GHOST_CACHE.clear(); CONFIRMED_OUTLINE_CACHE.clear(); PENDING_GHOST_CACHE.clear(); PENDING_GHOST_BUFFER_CACHE.clear();
   }
}
