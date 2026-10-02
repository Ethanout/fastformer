package io.github.fastformer.client.render.mask;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SourceMaskLightingTest {
   private final SourceMaskRenderFilter filter = SourceMaskRenderFilter.instance();
   private final BlockPos bottom = new BlockPos(4, 60, 7);

   @AfterEach void clear() { filter.clear(); }

   private SourceMaskLighting column() {
      filter.publish(List.of(bottom, bottom.above(), bottom.above(2)));
      return new SourceMaskLighting(filter.snapshot());
   }

   @Test void exposedGroundReceivesSkyThroughTheHiddenColumn() {
      var lighting = column();
      for (int y = 0; y < 3; y++) {
         assertEquals(15, lighting.skyLight(bottom.above(y), pos -> pos.getY() >= 63 ? 15 : 0));
      }
   }

   @Test void coveredColumnsStayDarkAndIndirectSkyAttenuates() {
      var lighting = column();
      assertEquals(0, lighting.skyLight(bottom, pos -> 0));
      assertEquals(9, lighting.skyLight(bottom, pos -> pos.getY() >= 63 ? 12 : 0));
      assertEquals(11, lighting.skyLight(bottom.above(2), pos -> pos.getY() >= 63 ? 12 : 0));
   }

   @Test void visibleRoofBetweenMaskedCellsStillBlocksSky() {
      filter.publish(List.of(bottom, bottom.above(2)));
      var lighting = new SourceMaskLighting(filter.snapshot());
      assertEquals(0, lighting.skyLight(bottom, pos -> pos.getY() >= 63 ? 15 : 0));
      assertEquals(15, lighting.skyLight(bottom.above(2), pos -> pos.getY() >= 63 ? 15 : 0));
   }

   @Test void unmaskedPositionsAndBrighterExistingLightRemainUnchanged() {
      var lighting = column();
      assertEquals(3, lighting.skyLight(bottom.east(), pos -> 3));
      assertEquals(14, lighting.skyLight(bottom, pos -> pos.equals(bottom) ? 14 : 4));
   }

   @Test void meshKeepsItsMaskButReadsCurrentSkyAfterTheMaskIsCleared() {
      var lighting = column();
      assertEquals(15, lighting.skyLight(bottom, pos -> pos.getY() >= 63 ? 15 : 0));
      filter.clear();
      assertEquals(9, lighting.skyLight(bottom, pos -> pos.getY() >= 63 ? 12 : 0));
      assertEquals(0, new SourceMaskLighting(filter.snapshot()).skyLight(bottom, pos -> pos.getY() >= 63 ? 15 : 0));
   }
}
