package io.github.fastformer.fastplace.geometry;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.client.render.model.BuildingBlockResult;
import io.github.fastformer.client.render.model.BuildingRenderLayers;
import io.github.fastformer.client.render.model.GeometryRenderLayers;
import io.github.fastformer.fastplace.geometry.generation.GenerationFailed;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class BlockPositionSetsTest {
   @Test
   void renderSnapshotsFreezeInputCollectionsAndMutableCoordinates() {
      BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos(1, 2, 3);
      Set<BlockPos> source = new LinkedHashSet<>();
      source.add(position);
      BuildingRenderLayers building = new BuildingRenderLayers(source, source, source);
      GeometryRenderLayers geometry = new GeometryRenderLayers(source, source);
      BuildingBlockResult result = new BuildingBlockResult(null, source);
      position.set(7, 8, 9);
      source.clear();

      for (Set<BlockPos> frozen : java.util.List.of(building.confirmedRenderBlocks(),
         building.pendingRenderBlocks(), building.allBlocks(), geometry.confirmed(), geometry.pending(), result.blocks())) {
         assertEquals(Set.of(new BlockPos(1, 2, 3)), frozen);
         assertThrows(UnsupportedOperationException.class, frozen::clear);
         var iterator = frozen.iterator();
         iterator.next();
         assertThrows(UnsupportedOperationException.class, iterator::remove);
      }
   }

   @Test
   void typedGenerationFailureDoesNotBecomeOrdinaryEmptyContent() {
      Set<BlockPos> failure = GenerationFailed.faceConstraints();
      assertSame(failure, new BuildingBlockResult(null, failure).blocks());
   }

   @Test
   void nullCoordinatesRemainInvalid() {
      Set<BlockPos> source = new LinkedHashSet<>();
      source.add(null);
      assertThrows(NullPointerException.class, () -> BlockPositionSets.copyOf(source));
   }
}
