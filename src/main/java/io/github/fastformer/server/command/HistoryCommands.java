package io.github.fastformer.server.command;

import io.github.fastformer.core.command.IntentSource;
import io.github.fastformer.server.intent.PlayerIntentSystem;
import io.github.fastformer.server.intent.HistoryIntents;
import io.github.fastformer.server.history.HistorySystem;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.fastformer.server.history.HistoryService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import static io.github.fastformer.server.gate.CommandAdmission.available;

/** Feature-owned command declarations and execution adapters. */
public final class HistoryCommands {
   private HistoryCommands() {}

   public static LiteralArgumentBuilder<CommandSourceStack> undoCommand() {
      return Commands.literal("undo")
         .executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            return available(player) && PlayerIntentSystem.dispatch(player, IntentSource.COMMAND, new HistoryIntents.Undo(1, HistoryIntents.UndoScope.COMMITTED_WORLD)).handled() ? 1 : 0;
         })
         .then(Commands.argument("count", IntegerArgumentType.integer(1, HistoryService.MAX_LIMIT)).executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            return available(player)
               && PlayerIntentSystem.dispatch(player, IntentSource.COMMAND, new HistoryIntents.Undo(IntegerArgumentType.getInteger(context, "count"), HistoryIntents.UndoScope.COMMITTED_WORLD)).handled() ? 1 : 0;
         }));
   }

   public static LiteralArgumentBuilder<CommandSourceStack> redoCommand() {
      return Commands.literal("redo")
         .executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            return available(player) && PlayerIntentSystem.dispatch(player, IntentSource.COMMAND, new HistoryIntents.Redo(1)).handled() ? 1 : 0;
         })
         .then(Commands.argument("count", IntegerArgumentType.integer(1, HistoryService.MAX_LIMIT)).executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            return available(player)
               && PlayerIntentSystem.dispatch(player, IntentSource.COMMAND, new HistoryIntents.Redo(IntegerArgumentType.getInteger(context, "count"))).handled() ? 1 : 0;
         }));
   }

   public static LiteralArgumentBuilder<CommandSourceStack> historyCleanupCommand() {
      return Commands.literal("history_cleanup")
         .requires(source -> source.hasPermission(2))
         .executes(context -> {
            HistorySystem.of(context.getSource().getServer()).resumeDiskCleanup(context.getSource().getServer());
            context.getSource().sendSuccess(() -> Component.literal("FastFormer history cleanup started."), true);
            return 1;
         });
   }
}
