package io.github.fastformer.client.render.cache;

import io.github.fastformer.client.render.model.BuildingRenderLayers;
import io.github.fastformer.fastplace.geometry.BlockPositionSets;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Reuses immutable shell membership while the render layers and markers stay unchanged. */
public final class BuildingShellBlocksCache {
   private BuildingRenderLayers layers;
   private Set<BlockPos> confirmedMarkers = Set.of();
   private Set<BlockPos> pendingMarkers = Set.of();
   private BuildingRenderLayers result = BuildingRenderLayers.empty();

   public BuildingRenderLayers resolve(
      BuildingRenderLayers layers, Set<BlockPos> confirmedMarkers, Set<BlockPos> pendingMarkers
   ) {
      if (this.layers == layers && this.confirmedMarkers.equals(confirmedMarkers)
         && this.pendingMarkers.equals(pendingMarkers)) {
         return this.result;
      }
      HashSet<BlockPos> confirmed = new HashSet<>(layers.confirmedRenderBlocks());
      confirmed.addAll(confirmedMarkers);
      HashSet<BlockPos> pending = new HashSet<>(layers.pendingRenderBlocks());
      pending.addAll(pendingMarkers);
      HashSet<BlockPos> environment = new HashSet<>(confirmed);
      environment.addAll(pending);
      BuildingRenderLayers next = new BuildingRenderLayers(confirmed, pending, environment);
      this.layers = layers;
      this.confirmedMarkers = BlockPositionSets.copyOf(confirmedMarkers);
      this.pendingMarkers = BlockPositionSets.copyOf(pendingMarkers);
      this.result = next;
      return next;
   }

   public void clear() {
      this.layers = null;
      this.confirmedMarkers = Set.of();
      this.pendingMarkers = Set.of();
      this.result = BuildingRenderLayers.empty();
   }
}
