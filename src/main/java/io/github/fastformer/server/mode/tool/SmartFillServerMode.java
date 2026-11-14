package io.github.fastformer.server.mode.tool;

import io.github.fastformer.common.mode.BuiltinModes;
import io.github.fastformer.server.mode.ModeInput;
import io.github.fastformer.server.mode.ModeInputResult;
import io.github.fastformer.core.command.IntentResult;
import io.github.fastformer.server.fill.SmartFillSystem;
import net.minecraft.server.level.ServerPlayer;

public final class SmartFillServerMode extends TemporaryServerMode {
   public SmartFillServerMode() { super(BuiltinModes.SMART_FILL); }
   @Override public boolean active(ServerPlayer player) { return SmartFillSystem.active(player); }
   @Override public void sync(ServerPlayer player) { SmartFillSystem.sync(player); }
   @Override public void cancel(ServerPlayer player) { SmartFillSystem.cancel(player); }
   @Override public void remove(ServerPlayer player) { SmartFillSystem.forget(player); }
   @Override public IntentResult quit(ServerPlayer player) { cancel(player); return IntentResult.success(); }
   @Override public IntentResult confirm(ServerPlayer player) { return IntentResult.handled(SmartFillSystem.confirmCurrent(player)); }
   @Override public ModeInputResult input(ServerPlayer player, ModeInput input) { return ModeInputResult.CONSUMED; }
}
