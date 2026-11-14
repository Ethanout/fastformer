package io.github.fastformer.server.mode.tool;

import io.github.fastformer.common.mode.BuiltinModes;
import io.github.fastformer.server.mode.ModeInput;
import io.github.fastformer.server.mode.ModeInputResult;
import io.github.fastformer.core.command.IntentResult;
import io.github.fastformer.server.fill.SmartFillSystem;
import net.minecraft.server.level.ServerPlayer;

public final class SmartFillServerMode extends TemporaryServerMode<SmartFillSystem.Session> {
   public SmartFillServerMode() { super(BuiltinModes.SMART_FILL, SmartFillSystem.repository()); }
   @Override protected void onPreview(ServerPlayer player, SmartFillSystem.Session session) { SmartFillSystem.sync(player); }
   @Override protected IntentResult onQuit(ServerPlayer player, SmartFillSystem.Session session) { cancel(player); return IntentResult.success(); }
   @Override protected IntentResult onConfirm(ServerPlayer player, SmartFillSystem.Session session) { return IntentResult.handled(SmartFillSystem.confirmCurrent(player)); }
   @Override protected ModeInputResult onInput(ServerPlayer player, SmartFillSystem.Session session, ModeInput input) { return ModeInputResult.CONSUMED; }
}
