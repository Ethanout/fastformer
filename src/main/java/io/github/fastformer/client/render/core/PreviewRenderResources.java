package io.github.fastformer.client.render.core;

import io.github.fastformer.client.render.cache.*;
import io.github.fastformer.client.render.mesh.GhostMeshBuilder;
import java.util.concurrent.*;

/** Owns GPU caches and worker lifetimes for the preview renderer. */
final class PreviewRenderResources {
   private PreviewRenderResources() { }
   static final GhostMeshCache CONFIRMED_GHOST_CACHE = new GhostMeshCache(blocks -> GhostMeshBuilder.build(blocks, true, true, true));
   static final GhostMeshCache CONFIRMED_OUTLINE_CACHE = new GhostMeshCache(blocks -> GhostMeshBuilder.build(blocks, false, true, true));
   static final BuildingShellCache CONFIRMED_BUILDING_SHELL_CACHE = BuildingShellCache.forImmutableSnapshots(false);
   static final BuildingShellCache PENDING_BUILDING_SHELL_CACHE = BuildingShellCache.forImmutableSnapshots(true);
   static final BuildingShellEdgeBuffer CONFIRMED_SHELL_EDGES = new BuildingShellEdgeBuffer();
   static final BuildingShellEdgeBuffer PENDING_SHELL_EDGES = new BuildingShellEdgeBuffer();
   static final BuildingShellFaceBuffer CONFIRMED_SHELL_FACES = new BuildingShellFaceBuffer();
   static final BuildingShellFaceBuffer PENDING_SHELL_FACES = new BuildingShellFaceBuffer();
   static final BuildingShellBlocksCache BUILDING_SHELL_BLOCKS_CACHE = new BuildingShellBlocksCache();
   static final ThreadPoolExecutor PREVIEW_MESH_EXECUTOR = executor("FastFormer preview mesh");
   static final ThreadPoolExecutor PREVIEW_GENERATION_EXECUTOR = executor("FastFormer preview generation");
   static final PendingGhostMeshCache PENDING_GHOST_CACHE = new PendingGhostMeshCache(PREVIEW_MESH_EXECUTOR,
      GhostMeshBuilder::buildPending, failure -> FastPlaceClientPreviewCore.logPreviewFailure("Unable to build FastFormer preview mesh", failure));
   static final PendingGhostBufferCache PENDING_GHOST_BUFFER_CACHE = new PendingGhostBufferCache(FastPlaceClientPreviewCore::uploadPendingGrid);

   private static ThreadPoolExecutor executor(String name) {
      return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), runnable -> {
         var thread = new Thread(runnable, name); thread.setDaemon(true); return thread;
      });
   }

   static void clearShells() {
      CONFIRMED_BUILDING_SHELL_CACHE.clear(); PENDING_BUILDING_SHELL_CACHE.clear(); BUILDING_SHELL_BLOCKS_CACHE.clear();
      CONFIRMED_SHELL_FACES.clear(); PENDING_SHELL_FACES.clear(); CONFIRMED_SHELL_EDGES.clear(); PENDING_SHELL_EDGES.clear();
   }

   static void clearMeshes() {
      CONFIRMED_GHOST_CACHE.clear(); CONFIRMED_OUTLINE_CACHE.clear(); PENDING_GHOST_CACHE.clear(); PENDING_GHOST_BUFFER_CACHE.clear();
   }
}
