package io.github.fastformer.fastplace.settings;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.nbt.CompoundTag;
import io.github.fastformer.fastplace.history.TinkerHistoryMergeLimits;
import org.junit.jupiter.api.Test;

class TinkerHistorySettingsTest {
   @Test
   void defaultsAndLegacySpatialSettingsUseChronologicalDefaults() {
      assertEquals(TinkerHistoryMergeLimits.DEFAULTS, FastPlaceSettings.fromTag(new CompoundTag()).tinkerHistoryMergeLimits());
      var tag = new CompoundTag();
      tag.putInt("tinkerHistoryMergeLimit", 32);
      assertEquals(TinkerHistoryMergeLimits.DEFAULTS, FastPlaceSettings.fromTag(tag).tinkerHistoryMergeLimits());
      var spatial = new CompoundTag();
      spatial.putInt("near", 4);
      spatial.putInt("middle", 12);
      spatial.putInt("far", 64);
      spatial.putInt("nearDistance", 7);
      spatial.putInt("farDistance", 25);
      tag.put("tinkerHistoryMerge", spatial);
      assertEquals(TinkerHistoryMergeLimits.DEFAULTS, FastPlaceSettings.fromTag(tag).tinkerHistoryMergeLimits());
      for (var key : java.util.List.of("near", "middle", "far")) spatial.putInt(key, 1);
      assertFalse(FastPlaceSettings.fromTag(tag).tinkerHistoryMergeLimits().enabled());
   }

   @Test
   void legacySingleLimitPreservesCustomSizeAndExplicitOptOut() {
      var tag = new CompoundTag();
      tag.putInt("tinkerHistoryMergeLimit", 48);
      assertEquals(new TinkerHistoryMergeLimits(5, 48, 32, 4, 128, 4), FastPlaceSettings.fromTag(tag).tinkerHistoryMergeLimits());
      tag.putInt("tinkerHistoryMergeLimit", 0);
      assertFalse(FastPlaceSettings.fromTag(tag).tinkerHistoryMergeLimits().enabled());
      tag.putInt("tinkerHistoryMergeLimit", Integer.MAX_VALUE);
      assertEquals(256, FastPlaceSettings.fromTag(tag).tinkerHistoryMergeLimits().recentGroup());
   }

   @Test
   void persistsSixValuesAndRemovesObsoleteDistanceAndMultiplierFields() {
      var tag = new CompoundTag();
      var values = new CompoundTag();
      values.putInt("recentAfter", 8);
      values.putInt("recentGroup", 12);
      values.putInt("olderAfter", 40);
      values.putInt("olderGroup", 3);
      values.putInt("oldestAfter", 200);
      values.putInt("oldestGroup", 5);
      values.putInt("nearDistance", 10);
      values.putInt("olderMultiplier", 99);
      tag.put("tinkerHistoryMerge", values);
      var saved = FastPlaceSettings.fromTag(tag).toTag();
      assertEquals(new TinkerHistoryMergeLimits(8, 12, 40, 3, 200, 5), FastPlaceSettings.fromTag(saved).tinkerHistoryMergeLimits());
      assertEquals(6, saved.getCompound("tinkerHistoryMerge").size());
      assertFalse(saved.getCompound("tinkerHistoryMerge").contains("nearDistance"));
      assertFalse(saved.getCompound("tinkerHistoryMerge").contains("olderMultiplier"));
   }
}
