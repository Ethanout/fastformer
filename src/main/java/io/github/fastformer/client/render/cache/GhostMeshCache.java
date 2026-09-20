package io.github.fastformer.client.render.cache;

import io.github.fastformer.client.render.model.GhostMesh;
import io.github.fastformer.fastplace.geometry.BlockPositionSets;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.core.BlockPos;

/** Memoizes one immutable ghost mesh configuration by its block set. */
public final class GhostMeshCache {
   private final Function<Set<BlockPos>, GhostMesh> generator;
   private Set<BlockPos> blocks = Set.of();
   private GhostMesh mesh = GhostMesh.empty();

   public GhostMeshCache(Function<Set<BlockPos>, GhostMesh> generator) {
      this.generator = java.util.Objects.requireNonNull(generator, "generator");
   }

   public GhostMesh mesh(Set<BlockPos> blocks) {
      if (!this.blocks.equals(blocks)) {
         Set<BlockPos> nextBlocks = BlockPositionSets.copyOf(blocks);
         GhostMesh nextMesh = java.util.Objects.requireNonNull(generator.apply(nextBlocks), "mesh");
         this.blocks = nextBlocks;
         this.mesh = nextMesh;
      }
      return this.mesh;
   }

   public void clear() {
      this.blocks = Set.of();
      this.mesh = GhostMesh.empty();
   }
}
