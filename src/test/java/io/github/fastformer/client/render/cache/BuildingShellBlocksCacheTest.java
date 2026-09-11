package io.github.fastformer.client.render.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.fastformer.client.render.model.BuildingRenderLayers;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class BuildingShellBlocksCacheTest {
   @Test
   void stableFramesReuseMembershipButMovingMarkersReplaceTheOldPosition() {
      BuildingShellBlocksCache cache = new BuildingShellBlocksCache();
      BlockPos base = BlockPos.ZERO;
      BlockPos oldPoint = new BlockPos(1, 0, 0);
      BlockPos newPoint = new BlockPos(2, 0, 0);
      BuildingRenderLayers layers = new BuildingRenderLayers(Set.of(base), Set.of(), Set.of(base));
      HashSet<BlockPos> markers = new HashSet<>(Set.of(oldPoint));
      BuildingRenderLayers first = cache.resolve(layers, Set.of(), markers);
      assertSame(first, cache.resolve(layers, Set.of(), Set.of(oldPoint)));

      markers.clear();
      markers.add(newPoint);
      BuildingRenderLayers moved = cache.resolve(layers, Set.of(), markers);
      assertNotSame(first, moved);
      assertEquals(Set.of(oldPoint), first.pendingRenderBlocks());
      assertEquals(Set.of(newPoint), moved.pendingRenderBlocks());
      assertEquals(Set.of(base, newPoint), moved.allBlocks());
      assertThrows(UnsupportedOperationException.class, () -> moved.allBlocks().clear());
   }

   @Test
   void newLayersAndSessionClearInvalidateMembership() {
      BuildingShellBlocksCache cache = new BuildingShellBlocksCache();
      BuildingRenderLayers oldLayers = new BuildingRenderLayers(Set.of(BlockPos.ZERO), Set.of(), Set.of(BlockPos.ZERO));
      BuildingRenderLayers first = cache.resolve(oldLayers, Set.of(), Set.of());
      BuildingRenderLayers emptyLayers = BuildingRenderLayers.empty();
      BuildingRenderLayers next = cache.resolve(emptyLayers, Set.of(), Set.of());
      assertNotSame(first, next);
      assertEquals(Set.of(), next.allBlocks());
      cache.clear();
      assertNotSame(next, cache.resolve(emptyLayers, Set.of(), Set.of()));
   }
}
