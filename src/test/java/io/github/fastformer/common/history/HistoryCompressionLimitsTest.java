package io.github.fastformer.common.history;

import static org.junit.jupiter.api.Assertions.*;
import io.github.fastformer.common.history.CompressionSetting;
import io.github.fastformer.common.history.CompressionTier;
import org.junit.jupiter.api.Test;

class HistoryCompressionLimitsTest {
   @Test
   void eachTierHasAnIndependentThresholdAndRecordCount() {
      var limits = new HistoryCompressionLimits(6, 8, 40, 3, 200, 5);
      assertEquals(6, limits.after(CompressionTier.RECENT));
      assertEquals(3, limits.groupSize(CompressionTier.OLDER));
      assertEquals(200, limits.after(CompressionTier.OLDEST));
      assertEquals(5, limits.groupSize(CompressionTier.OLDEST));
      for (var setting : CompressionSetting.values()) assertEquals(limits, limits.with(setting, limits.value(setting)));
      assertEquals(9, limits.with(CompressionSetting.RECENT_GROUP, 9).recentGroup());
      assertEquals(3, limits.with(CompressionSetting.RECENT_GROUP, 9).olderGroup());
   }

   @Test
   void thresholdsStayOrderedAndGroupsAreBounded() {
      var limits = HistoryCompressionLimits.DEFAULTS;
      assertEquals(31, limits.with(CompressionSetting.RECENT_AFTER, 100).recentAfter());
      assertEquals(127, limits.with(CompressionSetting.OLDER_AFTER, 1000).olderAfter());
      assertEquals(6, limits.with(CompressionSetting.OLDER_AFTER, -1).olderAfter());
      assertEquals(33, limits.with(CompressionSetting.OLDEST_AFTER, 0).oldestAfter());
      assertEquals(1, limits.with(CompressionSetting.RECENT_GROUP, -1).recentGroup());
      assertEquals(256, limits.with(CompressionSetting.OLDEST_GROUP, 1000).oldestGroup());
      assertEquals(new HistoryCompressionLimits(65534, 1, 65535, 256, 65536, 1),
         new HistoryCompressionLimits(Integer.MAX_VALUE, 0, 0, 1000, 0, 1));
   }

   @Test
   void disablingOneTierDoesNotDisableTheOthers() {
      assertTrue(HistoryCompressionLimits.DEFAULTS.with(CompressionSetting.RECENT_GROUP, 1).enabled());
      assertFalse(new HistoryCompressionLimits(5, 1, 32, 1, 128, 1).enabled());
   }
}
