package io.github.fastformer.client.render.cache;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.fastformer.client.render.ShapeShellMesh;
import io.github.fastformer.client.render.model.BuildingSpecialBlock;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class BuildingShellCacheTest {
   @Test
   void failedRebuildDoesNotPublishItsKeyOrReplaceThePreviousMesh() {
      BuildingShellCache cache = new BuildingShellCache(false);
      Set<BlockPos> blocks = Set.of(BlockPos.ZERO);
      ShapeShellMesh.Mesh original = cache.mesh(null, null, Map.of(), null, blocks, blocks, Map.of(), false);
      Map<BlockPos, BuildingSpecialBlock> invalidStyle = Map.of(BlockPos.ZERO, new BuildingSpecialBlock(null, true));

      for (int attempt = 0; attempt < 2; attempt++) {
         assertThrows(NullPointerException.class,
            () -> cache.mesh(null, null, Map.of(), null, blocks, blocks, invalidStyle, false));
      }
      assertSame(original, cache.mesh(null, null, Map.of(), null, blocks, blocks, Map.of(), false));
   }
}
