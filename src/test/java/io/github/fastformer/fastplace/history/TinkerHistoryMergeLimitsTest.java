package io.github.fastformer.fastplace.history;

import static org.junit.jupiter.api.Assertions.*;
import io.github.fastformer.fastplace.history.TinkerHistoryMergeLimits.DistanceBand;
import io.github.fastformer.fastplace.history.TinkerHistoryMergeLimits.Setting;
import org.junit.jupiter.api.Test;

class TinkerHistoryMergeLimitsTest {
   @Test
   void classifiesExactBoundariesAndAppliesCustomDistances() {
      assertEquals(DistanceBand.NEAR, DistanceBand.at(5));
      assertEquals(DistanceBand.MIDDLE, DistanceBand.at(5.001));
      assertEquals(DistanceBand.MIDDLE, DistanceBand.at(20));
      assertEquals(DistanceBand.FAR, DistanceBand.at(20.001));
      var custom = new TinkerHistoryMergeLimits(4, 12, 24, 8, 30);
      assertEquals(DistanceBand.NEAR, DistanceBand.at(8, custom));
      assertEquals(DistanceBand.MIDDLE, DistanceBand.at(30, custom));
      assertEquals(DistanceBand.FAR, DistanceBand.at(30.001, custom));
   }

   @Test
   void editsOneSettingAndKeepsDistanceBoundariesOrdered() {
      var defaults = TinkerHistoryMergeLimits.DEFAULTS;
      assertEquals(new TinkerHistoryMergeLimits(9, 16, 32), defaults.with(Setting.NEAR_LIMIT, 9));
      assertEquals(new TinkerHistoryMergeLimits(8, 17, 32), defaults.with(Setting.MIDDLE_LIMIT, 17));
      assertEquals(new TinkerHistoryMergeLimits(8, 16, 33), defaults.with(Setting.FAR_LIMIT, 33));
      assertEquals(new TinkerHistoryMergeLimits(8, 16, 32, 19, 20), defaults.with(Setting.NEAR_DISTANCE, 100));
      assertEquals(new TinkerHistoryMergeLimits(8, 16, 32, 5, 6), defaults.with(Setting.FAR_DISTANCE, 1));
      assertEquals(1, defaults.with(Setting.NEAR_LIMIT, -1).near());
      assertEquals(256, defaults.with(Setting.FAR_LIMIT, 1000).far());
   }
}
