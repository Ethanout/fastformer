package io.github.fastformer.server.mode.tool;

import io.github.fastformer.common.mode.BuiltinModes;
import io.github.fastformer.server.mode.ModeInput;
import io.github.fastformer.server.mode.ModeInputResult;
import io.github.fastformer.common.placement.context.PlaceableItems;
import io.github.fastformer.server.replace.QuickReplaceSystem;
import net.minecraft.server.level.ServerPlayer;

public final class QuickReplaceServerMode extends TemporaryServerMode {
   public QuickReplaceServerMode() { super(BuiltinModes.QUICK_REPLACE); }
   @Override public boolean active(ServerPlayer player) { return QuickReplaceSystem.active(player); }
   @Override public void sync(ServerPlayer player) { QuickReplaceSystem.sync(player); }
   @Override public void cancel(ServerPlayer player) { QuickReplaceSystem.forget(player); }
   @Override public void remove(ServerPlayer player) { QuickReplaceSystem.forget(player); }
   @Override public ModeInputResult input(ServerPlayer player, ModeInput input) {
      return input == ModeInput.RIGHT_BLOCK && PlaceableItems.isPlaceable(player.getMainHandItem())
         ? ModeInputResult.CONSUMED : ModeInputResult.VANILLA;
   }
}
