package io.github.fastformer.client.preview.geometry;

import io.github.fastformer.common.geometry.preview.GeometryPreviewBlocks;

import static org.junit.jupiter.api.Assertions.*;

import io.github.fastformer.common.geometry.preview.GeometryPreviewPlan;
import io.github.fastformer.protocol.s2c.geometry.GeometryPreviewPayload;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class GeometryPreviewModelsTest {
   @Test void excludesMarkerCellsAndReusesUnchangedDrawingLayers() {
      var marker = BlockPos.ZERO;
      var confirmed = new BlockPos(1,0,0);
      var pending = new BlockPos(2,0,0);
      var plan = GeometryPreviewPlan.builder(List.of(marker), null).blocks(
         new io.github.fastformer.common.geometry.preview.GeometryPreviewBlocks.Layers(Set.of(marker, confirmed), Set.of(marker, pending))).build();
      var cache = new GeometryPreviewModels();
      var input = new GeometryPreviewInput(GeometryPreviewPayload.inactive(), false, plan);
      var model = cache.build(input);
      assertEquals(Set.of(confirmed), model.blocks().confirmed());
      assertEquals(Set.of(pending), model.blocks().pending());
      assertSame(plan, model.plan());
      assertSame(model, cache.build(input));
   }

   @Test void ownersClearIndependentlyAndChangedPlansInvalidateLayers() {
      var firstPlan = GeometryPreviewPlan.builder(List.of(), null).ghost(Set.of(BlockPos.ZERO)).build();
      var secondPlan = GeometryPreviewPlan.builder(List.of(), null).ghost(Set.of(new BlockPos(5,0,0))).build();
      var first = new GeometryPreviewModels();
      var second = new GeometryPreviewModels();
      var snapshot = GeometryPreviewPayload.inactive();
      var input = new GeometryPreviewInput(snapshot, false, firstPlan);
      var original = first.build(input);
      var other = second.build(input);
      first.clear();
      assertNotSame(original, first.build(input));
      assertSame(other, second.build(input));
      assertEquals(Set.of(new BlockPos(5,0,0)), first.build(new GeometryPreviewInput(snapshot, false, secondPlan)).blocks().confirmed());
      assertNotSame(other, second.build(new GeometryPreviewInput(snapshot.withRevision(2), false, firstPlan)));
   }
}
