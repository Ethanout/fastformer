package io.github.fastformer.server.mode.tool;

import io.github.fastformer.common.interaction.InteractionState;
import io.github.fastformer.server.mode.ServerMode;
import io.github.fastformer.server.mode.ModeInput;
import io.github.fastformer.server.mode.ModeInputResult;
import io.github.fastformer.common.mode.ModeId;
import io.github.fastformer.core.command.IntentResult;
import net.minecraft.server.level.ServerPlayer;

/** Connection-bound tools never park durable drafts in another dimension. */
public abstract class TemporaryServerMode extends ServerMode {
   protected TemporaryServerMode(ModeId id) { super(id, InteractionState.VANILLA); }
   public abstract ModeInputResult input(ServerPlayer player, ModeInput input);
   public abstract void cancel(ServerPlayer player);
   public abstract void remove(ServerPlayer player);
   public IntentResult quit(ServerPlayer player) { return IntentResult.pass(); }
}
