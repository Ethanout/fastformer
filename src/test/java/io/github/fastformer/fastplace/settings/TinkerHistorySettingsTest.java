package io.github.fastformer.fastplace.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import net.minecraft.nbt.CompoundTag;
import io.github.fastformer.fastplace.history.TinkerHistoryMergeLimits;
import org.junit.jupiter.api.Test;

class TinkerHistorySettingsTest {
   @Test
   void defaultsToDistanceBandsAndMigratesThePreviousDefault() {
      assertEquals(TinkerHistoryMergeLimits.DEFAULTS, FastPlaceSettings.fromTag(new CompoundTag()).tinkerHistoryMergeLimits());
      CompoundTag oldDefault = new CompoundTag();
      oldDefault.putInt("tinkerHistoryMergeLimit", 32);
      assertEquals(TinkerHistoryMergeLimits.DEFAULTS, FastPlaceSettings.fromTag(oldDefault).tinkerHistoryMergeLimits());
   }

   @Test
   void migratesCustomLegacyLimitsAndPersistsAllFiveValues() {
      CompoundTag tag = new CompoundTag();
      tag.putInt("tinkerHistoryMergeLimit", 48);
      var settings = FastPlaceSettings.fromTag(tag);
      assertEquals(new TinkerHistoryMergeLimits(48, 48, 48), FastPlaceSettings.fromTag(settings.toTag()).tinkerHistoryMergeLimits());
      tag.putInt("tinkerHistoryMergeLimit", 0);
      assertEquals(new TinkerHistoryMergeLimits(1, 1, 1), FastPlaceSettings.fromTag(tag).tinkerHistoryMergeLimits());
      tag.putInt("tinkerHistoryMergeLimit", Integer.MAX_VALUE);
      assertEquals(new TinkerHistoryMergeLimits(256, 256, 256), FastPlaceSettings.fromTag(tag).tinkerHistoryMergeLimits());
      CompoundTag custom = new CompoundTag();
      custom.putInt("near", 4);
      custom.putInt("middle", 12);
      custom.putInt("far", 64);
      custom.putInt("nearDistance", 7);
      custom.putInt("farDistance", 25);
      tag.put("tinkerHistoryMerge", custom);
      settings = FastPlaceSettings.fromTag(tag);
      assertEquals(new TinkerHistoryMergeLimits(4, 12, 64, 7, 25), FastPlaceSettings.fromTag(settings.toTag()).tinkerHistoryMergeLimits());
   }
}
