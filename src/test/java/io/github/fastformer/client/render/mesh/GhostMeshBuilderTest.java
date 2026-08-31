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
}
