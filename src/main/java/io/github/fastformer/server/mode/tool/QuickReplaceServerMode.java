package io.github.fastformer.server.mode.tool;

import io.github.fastformer.common.mode.BuiltinModes;
import io.github.fastformer.server.mode.ModeInput;
import io.github.fastformer.server.mode.ModeInputResult;
import io.github.fastformer.common.placement.context.PlaceableItems;
import io.github.fastformer.server.replace.QuickReplaceSystem;
import net.minecraft.server.level.ServerPlayer;

public final class QuickReplaceServerMode extends TemporaryServerMode<QuickReplaceSystem.State> {
   public QuickReplaceServerMode() { super(BuiltinModes.QUICK_REPLACE, QuickReplaceSystem.repository()); }
   @Override protected void onPreview(ServerPlayer player, QuickReplaceSystem.State session) { QuickReplaceSystem.sync(player); }
   @Override protected ModeInputResult onInput(ServerPlayer player, QuickReplaceSystem.State session, ModeInput input) {
      return input == ModeInput.RIGHT_BLOCK && PlaceableItems.isPlaceable(player.getMainHandItem())
         ? ModeInputResult.CONSUMED : ModeInputResult.VANILLA;
   }
}
