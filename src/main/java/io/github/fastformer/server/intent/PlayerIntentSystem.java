package io.github.fastformer.server.intent;

import io.github.fastformer.core.command.Intent;
import io.github.fastformer.core.command.IntentBus;
import io.github.fastformer.core.command.IntentContext;
import io.github.fastformer.core.command.IntentResult;
import io.github.fastformer.core.command.IntentSource;
import io.github.fastformer.server.settings.SettingsIntentHandlers;
import io.github.fastformer.server.intent.quickshape.QuickShapeIntentHandlers;
import io.github.fastformer.server.intent.selection.SelectionIntentHandlers;
import net.minecraft.server.level.ServerPlayer;

/** Assembles the shared player command bus; handlers own their admission and business rules. */
public final class PlayerIntentSystem {
   private static final IntentBus<ServerPlayer> BUS = IntentBus.create(bus -> {
      SessionIntentHandlers.register(bus);
      GeometryIntentHandlers.register(bus);
      QuickShapeIntentHandlers.register(bus);
      SelectionIntentHandlers.register(bus);
      HistoryIntentHandlers.register(bus);
      SettingsIntentHandlers.register(bus);
   });

   private PlayerIntentSystem() {}

   public static IntentResult dispatch(ServerPlayer player, IntentSource source, Intent intent) {
      return dispatch(player, source, 0, intent);
   }

   public static IntentResult dispatch(ServerPlayer player, IntentSource source, long requestId, Intent intent) {
      return BUS.dispatch(new IntentContext<>(player, source, requestId), intent);
   }
}
