package io.github.fastformer.server.command;

import io.github.fastformer.server.session.SessionSystem;
import io.github.fastformer.core.command.IntentSource;
import io.github.fastformer.server.intent.PlayerIntentSystem;
import io.github.fastformer.server.intent.SessionIntents;
import io.github.fastformer.server.intent.HistoryIntents;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import static io.github.fastformer.server.gate.CommandAdmission.available;

/** Feature-owned command declarations and execution adapters. */
public final class ActionCommands {
   private ActionCommands() {}

   public static LiteralArgumentBuilder<CommandSourceStack> actionCommand() {
      return Commands.literal("action")
         .then(Commands.literal("cycle").executes(context ->
            PlayerIntentSystem.dispatch(context.getSource().getPlayerOrException(), IntentSource.COMMAND, new SessionIntents.CycleMode(null)).handled() ? 1 : 0
         ))
         .then(Commands.literal("adjust")
            .then(Commands.argument("steps", IntegerArgumentType.integer(-128, 128)).executes(context ->
               PlayerIntentSystem.dispatch(context.getSource().getPlayerOrException(), IntentSource.COMMAND,
                  new SessionIntents.Adjust(IntegerArgumentType.getInteger(context, "steps"))).handled() ? 1 : 0
            )))
         .then(Commands.literal("confirm").executes(context ->
            PlayerIntentSystem.dispatch(context.getSource().getPlayerOrException(), IntentSource.COMMAND, new SessionIntents.Confirm()).handled() ? 1 : 0
         ))
         .then(Commands.literal("back").executes(context ->
            PlayerIntentSystem.dispatch(context.getSource().getPlayerOrException(), IntentSource.COMMAND, new HistoryIntents.Back()).handled() ? 1 : 0
         ))
         .then(Commands.literal("cancel").executes(context -> {
            PlayerIntentSystem.dispatch(context.getSource().getPlayerOrException(), IntentSource.COMMAND, new HistoryIntents.Quit());
            return 1;
         }))
         .then(Commands.literal("submode")
            .then(Commands.literal("on").executes(context ->
               PlayerIntentSystem.dispatch(context.getSource().getPlayerOrException(), IntentSource.COMMAND, new SessionIntents.Submode(true)).handled() ? 1 : 0
            ))
            .then(Commands.literal("off").executes(context ->
               PlayerIntentSystem.dispatch(context.getSource().getPlayerOrException(), IntentSource.COMMAND, new SessionIntents.Submode(false)).handled() ? 1 : 0
            )));
   }

   public static LiteralArgumentBuilder<CommandSourceStack> cancelCommand() {
      return Commands.literal("cancel").executes(context -> {
         SessionSystem.of(context.getSource().getPlayerOrException()).quit(context.getSource().getPlayerOrException());
         return 1;
      });
   }

   public static LiteralArgumentBuilder<CommandSourceStack> modeCommand() {
      return Commands.literal("mode").executes(context -> {
         ServerPlayer player = context.getSource().getPlayerOrException();
         if (!available(player)) {
            return 0;
         }
         PlayerIntentSystem.dispatch(player, IntentSource.COMMAND, new SessionIntents.CycleMode(null));
         return 1;
      });
   }
}
