package io.github.fastformer.client.render.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.fastformer.client.render.model.GhostMesh;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class GhostMeshBuilderTest {
   @Test
   void singleBlockProducesAllBoundaryFacesAndEdges() {
      GhostMesh mesh = GhostMeshBuilder.build(Set.of(BlockPos.ZERO), true, true, true);

      assertEquals(6, mesh.faces().size());
      assertEquals(12, mesh.edges().size());
   }

   @Test
   void adjacentConfirmedAndPendingVoxelsCullTheirSharedFaceWhenComposed() {
      GhostMesh mesh = GhostMeshBuilder.build(
         Set.of(BlockPos.ZERO, new BlockPos(1, 0, 0)), true, true, true
      );

      // The two layers are composed before meshing, so the shared plane is
      // absent and the remaining coplanar faces merge into a box shell.
      assertEquals(6, mesh.faces().size());
   }
}
