package io.github.fastformer.fastplace.settings;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReachSettingsTest {
   @Test
   void ignoresRemovedDistanceSettings() {
      var tag = new CompoundTag();
      tag.putInt("reachClose", 8);
      tag.putInt("reachFar", 32);
      tag.putInt("tinkerReach", 32);
      var settings = FastPlaceSettings.fromTag(tag);
      assertFalse(settings.toTag().contains("reachClose"));
      assertFalse(settings.toTag().contains("reachFar"));
      assertFalse(settings.toTag().contains("tinkerReach"));
   }
}
