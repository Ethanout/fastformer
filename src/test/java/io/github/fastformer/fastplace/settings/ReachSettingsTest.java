package io.github.fastformer.fastplace.settings;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReachSettingsTest {
   @Test
   void persistsPairAndFallsBackForOldOrInvalidSettings() {
      assertEquals(ReachThresholds.DEFAULT, FastPlaceSettings.fromTag(new CompoundTag()).reachThresholds());
      var tag = new CompoundTag();
      tag.putInt("reachClose", 8);
      tag.putInt("reachFar", 32);
      var settings = FastPlaceSettings.fromTag(tag);
      assertEquals(new ReachThresholds(8, 32), FastPlaceSettings.fromTag(settings.toTag()).reachThresholds());
      tag.putInt("reachFar", 4);
      assertEquals(ReachThresholds.DEFAULT, FastPlaceSettings.fromTag(tag).reachThresholds());
   }
}
