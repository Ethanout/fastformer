package io.github.fastformer.server.settings;

import io.github.fastformer.core.command.IntentResult;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.fastplace.text.FastPlaceMessages;
import io.github.fastformer.protocol.settings.SettingKey;
import io.github.fastformer.protocol.settings.SettingsKeys;
import net.minecraft.server.level.ServerPlayer;

/** Command-only setting queries and feedback; writes use the shared setting bindings. */
final class SettingsCommandFeedback {
   private SettingsCommandFeedback() {}

   static IntentResult show(ServerPlayer player, SettingKey<?> key, boolean changed) {
      var settings = FastPlaceSettings.load(player);
      String message;
      Object value;
      if (key == SettingsKeys.WORLD_HISTORY_LIMIT) {
         message = changed ? "history_limit_set" : "history_limit_status";
         value = settings.worldUndoHistoryLimit();
      } else if (key == SettingsKeys.SESSION_HISTORY_LIMIT) {
         message = changed ? "session_history_limit_set" : "session_history_limit_status";
         value = settings.sessionUndoHistoryLimit();
      } else if (key == SettingsKeys.MIDDLE_CONFIRM) {
         message = changed ? "middle_confirm_set" : "middle_confirm_status";
         value = FastPlaceMessages.text(settings.middleConfirmEnabled() ? "fastformer.message.enabled" : "fastformer.message.disabled");
      } else if (changed && key == SettingsKeys.PLACEMENT_CONFLICT) {
         message = "placement_conflict_value";
         value = FastPlaceMessages.text(settings.placementConflictMode());
      } else if (changed && key == SettingsKeys.PLACEMENT_UPDATE) {
         message = "placement_update_value";
         value = FastPlaceMessages.text(settings.placementUpdateMode());
      } else return IntentResult.pass();
      FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message." + message, value));
      return IntentResult.success();
   }
}
