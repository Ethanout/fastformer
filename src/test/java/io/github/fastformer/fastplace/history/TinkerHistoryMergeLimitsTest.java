package io.github.fastformer.fastplace.history;

import static org.junit.jupiter.api.Assertions.*;
import io.github.fastformer.fastplace.history.TinkerHistoryMergeLimits.Setting;
import io.github.fastformer.fastplace.history.TinkerHistoryMergeLimits.Tier;
import org.junit.jupiter.api.Test;

class TinkerHistoryMergeLimitsTest {
   @Test
   void eachTierHasAnIndependentThresholdAndRecordCount() {
      var limits = new TinkerHistoryMergeLimits(6, 8, 40, 3, 200, 5);
      assertEquals(6, limits.after(Tier.RECENT));
      assertEquals(3, limits.groupSize(Tier.OLDER));
      assertEquals(200, limits.after(Tier.OLDEST));
      assertEquals(5, limits.groupSize(Tier.OLDEST));
      for (var setting : Setting.values()) assertEquals(limits, limits.with(setting, limits.value(setting)));
      assertEquals(9, limits.with(Setting.RECENT_GROUP, 9).recentGroup());
      assertEquals(3, limits.with(Setting.RECENT_GROUP, 9).olderGroup());
   }

   @Test
   void thresholdsStayOrderedAndGroupsAreBounded() {
      var limits = TinkerHistoryMergeLimits.DEFAULTS;
      assertEquals(31, limits.with(Setting.RECENT_AFTER, 100).recentAfter());
      assertEquals(127, limits.with(Setting.OLDER_AFTER, 1000).olderAfter());
      assertEquals(6, limits.with(Setting.OLDER_AFTER, -1).olderAfter());
      assertEquals(33, limits.with(Setting.OLDEST_AFTER, 0).oldestAfter());
      assertEquals(1, limits.with(Setting.RECENT_GROUP, -1).recentGroup());
      assertEquals(256, limits.with(Setting.OLDEST_GROUP, 1000).oldestGroup());
      assertEquals(new TinkerHistoryMergeLimits(65534, 1, 65535, 256, 65536, 1),
         new TinkerHistoryMergeLimits(Integer.MAX_VALUE, 0, 0, 1000, 0, 1));
   }

   @Test
   void disablingOneTierDoesNotDisableTheOthers() {
      assertTrue(TinkerHistoryMergeLimits.DEFAULTS.with(Setting.RECENT_GROUP, 1).enabled());
      assertFalse(new TinkerHistoryMergeLimits(5, 1, 32, 1, 128, 1).enabled());
   }
}
