package io.github.fastformer.server.command.edit;

import io.github.fastformer.core.command.IntentSource;
import io.github.fastformer.server.intent.PlayerIntentSystem;
import io.github.fastformer.server.intent.HistoryIntents;
import io.github.fastformer.server.intent.selection.SelectionIntents;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.fastformer.fastplace.selection.OperationSelectionMode;
import io.github.fastformer.workspace.submission.OperationConflictMode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import static io.github.fastformer.server.command.CommandChecks.commandCanOperate;
import io.github.fastformer.server.command.syntax.CommandChoices;
import java.util.Map;

/** Feature-owned command declarations and execution adapters. */
public final class SelectionCommands {
   private SelectionCommands() {}

   public static LiteralArgumentBuilder<CommandSourceStack> operationCommand() {
      return Commands.literal("operation")
         .then(Commands.literal("apply").executes(context -> commandCanOperate(context.getSource().getPlayerOrException()) && PlayerIntentSystem.dispatch(context.getSource().getPlayerOrException(), IntentSource.COMMAND, new SelectionIntents.Apply(false)).handled() ? 1 : 0))
         .then(Commands.literal("copy").executes(context -> commandCanOperate(context.getSource().getPlayerOrException()) && PlayerIntentSystem.dispatch(context.getSource().getPlayerOrException(), IntentSource.COMMAND, new SelectionIntents.Apply(true)).handled() ? 1 : 0))
         .then(Commands.literal("undo").executes(context -> commandCanOperate(context.getSource().getPlayerOrException()) && PlayerIntentSystem.dispatch(context.getSource().getPlayerOrException(), IntentSource.COMMAND, new HistoryIntents.Undo(1, HistoryIntents.UndoScope.COMMITTED_WORLD)).handled() ? 1 : 0))
         .then(Commands.literal("mode").executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            if (!commandCanOperate(player)) {
               return 0;
            }
            return PlayerIntentSystem.dispatch(player, IntentSource.COMMAND, new SelectionIntents.CycleMode()).handled() ? 1 : 0;
         }))
         .then(operationSelectionCommand())
         .then(operationConflictCommand());
   }

   private static LiteralArgumentBuilder<CommandSourceStack> operationSelectionCommand() {
      return new CommandChoices<>(Map.of(
         "cuboid", OperationSelectionMode.CUBOID),
         "fastformer.message.operation_selection_usage").validateFirst("selection", SelectionIntents.SelectMode::new);
   }

   private static LiteralArgumentBuilder<CommandSourceStack> operationConflictCommand() {
      return new CommandChoices<>(Map.of(
         "replace", OperationConflictMode.REPLACE,
         "keep_existing", OperationConflictMode.KEEP_EXISTING),
         "fastformer.message.operation_conflict_usage").validateFirst("conflict", SelectionIntents.ConflictMode::new);
   }
}
