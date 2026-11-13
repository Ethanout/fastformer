package io.github.fastformer.server.interaction.profile;

import io.github.fastformer.fastplace.quickshape.LineMode;
import io.github.fastformer.fastplace.quickshape.QuickShapeDraft;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import java.util.Locale;

/** Reads a draft's immutable constraints without changing the player's saved settings. */
public final class ProfileConstraints {
   private ProfileConstraints() {}

   public static LineMode effectiveLineMode(QuickShapeDraft draft, FastPlaceSettings settings) {
      if (draft == null) return settings.lineMode();
      var locked = draft.profile().lockedModes().get("line");
      return locked == null ? settings.lineMode() : LineMode.valueOf(locked.getPath().toUpperCase(Locale.ROOT));
   }

   public static boolean allowsModeSwitch(QuickShapeDraft draft) {
      return draft == null || draft.profile().allowModeSwitch();
   }

   public static boolean middleConfirm(QuickShapeDraft draft, FastPlaceSettings settings) {
      return draft != null && draft.profile().middleConfirm().equals("always") || settings.middleConfirmEnabled();
   }
}
