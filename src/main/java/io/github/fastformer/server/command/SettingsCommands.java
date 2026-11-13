package io.github.fastformer.server.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.fastformer.core.command.Intent;
import io.github.fastformer.core.command.IntentSource;
import io.github.fastformer.fastplace.geometry.FillMode;
import io.github.fastformer.fastplace.placement.PlacementUpdateMode;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.fastplace.text.FastPlaceMessages;
import io.github.fastformer.protocol.settings.SettingsKeys;
import io.github.fastformer.server.history.HistoryService;
import io.github.fastformer.server.intent.PlayerIntentSystem;
import io.github.fastformer.server.settings.PlayerSettingsIntents;
import io.github.fastformer.workspace.submission.OperationConflictMode;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** Setting syntax constructs intents; handlers own mutation, queries and preview synchronization. */
public final class SettingsCommands {
   private SettingsCommands() {}

   public static LiteralArgumentBuilder<CommandSourceStack> forcePlacementCommand() {
      return Commands.literal("forceplace").then(Commands.argument("enabled", BoolArgumentType.bool()).executes(context -> {
         boolean enabled = BoolArgumentType.getBool(context, "enabled");
         int result = dispatch(context, new PlayerSettingsIntents.ForcePlacement(enabled));
         if (result != 0) context.getSource().sendSuccess(() -> Component.translatable("fastformer.message.force_placement", enabled), false);
         return result;
      }));
   }

   public static LiteralArgumentBuilder<CommandSourceStack> placementCommand() {
      var conflicts = Map.of("replace", OperationConflictMode.REPLACE, "keep_existing", OperationConflictMode.KEEP_EXISTING);
      return Commands.literal("placement")
         .then(Commands.literal("conflict").then(Commands.argument("mode", StringArgumentType.word()).executes(context -> {
            var mode = conflicts.get(StringArgumentType.getString(context, "mode"));
            return mode == null ? usage(context, "placement_conflict_usage") : dispatch(context, SettingsKeys.PLACEMENT_CONFLICT.set(mode));
         })))
         .then(Commands.literal("updates").then(Commands.argument("mode", StringArgumentType.word()).executes(context -> {
            var mode = PlacementUpdateMode.parse(StringArgumentType.getString(context, "mode"));
            return mode == null ? usage(context, "placement_update_usage") : dispatch(context, SettingsKeys.PLACEMENT_UPDATE.set(mode));
         })));
   }

   public static LiteralArgumentBuilder<CommandSourceStack> undoHistoryLimitCommand() {
      return Commands.literal("undo_history").executes(context -> dispatch(context, SettingsKeys.WORLD_HISTORY_LIMIT.query()))
         .then(Commands.argument("count", IntegerArgumentType.integer(1, HistoryService.MAX_LIMIT)).executes(context ->
            dispatch(context, SettingsKeys.WORLD_HISTORY_LIMIT.set(IntegerArgumentType.getInteger(context, "count")))));
   }

   public static LiteralArgumentBuilder<CommandSourceStack> sessionUndoHistoryLimitCommand() {
      return Commands.literal("session_undo_history").executes(context -> dispatch(context, SettingsKeys.SESSION_HISTORY_LIMIT.query()))
         .then(Commands.argument("count", IntegerArgumentType.integer(1, FastPlaceSettings.MAX_UNDO_HISTORY_LIMIT)).executes(context ->
            dispatch(context, SettingsKeys.SESSION_HISTORY_LIMIT.set(IntegerArgumentType.getInteger(context, "count")))));
   }

   public static LiteralArgumentBuilder<CommandSourceStack> middleConfirmCommand() {
      return Commands.literal("middle_confirm").executes(context -> dispatch(context, SettingsKeys.MIDDLE_CONFIRM.query()))
         .then(Commands.argument("enabled", BoolArgumentType.bool()).executes(context ->
            dispatch(context, SettingsKeys.MIDDLE_CONFIRM.set(BoolArgumentType.getBool(context, "enabled")))));
   }

   public static LiteralArgumentBuilder<CommandSourceStack> settingsCommand() {
      return Commands.literal("settings").executes(context -> dispatch(context, new PlayerSettingsIntents.OpenScreen()));
   }

   public static LiteralArgumentBuilder<CommandSourceStack> fillModeCommand() {
      return Commands.literal("fillmode").executes(context -> dispatch(context, new PlayerSettingsIntents.Fill(Optional.empty())))
         .then(Commands.argument("value", StringArgumentType.word()).executes(context -> {
            var mode = FillMode.parse(StringArgumentType.getString(context, "value"));
            return mode == null ? usage(context, "fill_mode_usage") : dispatch(context, new PlayerSettingsIntents.Fill(Optional.of(mode)));
         }));
   }

   public static LiteralArgumentBuilder<CommandSourceStack> maxPlacementCommand() {
      return Commands.literal("max_placement").executes(context -> dispatch(context, new PlayerSettingsIntents.MaxPlacement(OptionalInt.empty())))
         .then(Commands.argument("blocks", IntegerArgumentType.integer(1, FastPlaceSettings.MAX_PLACEMENT)).executes(context ->
            dispatch(context, new PlayerSettingsIntents.MaxPlacement(OptionalInt.of(IntegerArgumentType.getInteger(context, "blocks"))))));
   }

   public static LiteralArgumentBuilder<CommandSourceStack> toggleCommand() {
      return Commands.literal("toggle").executes(context -> dispatch(context, new PlayerSettingsIntents.Toggle()));
   }

   public static LiteralArgumentBuilder<CommandSourceStack> statusCommand() {
      return Commands.literal("status").executes(context -> dispatch(context, new PlayerSettingsIntents.Status()));
   }

   private static int dispatch(CommandContext<CommandSourceStack> context, Intent intent) throws CommandSyntaxException {
      return PlayerIntentSystem.dispatch(context.getSource().getPlayerOrException(), IntentSource.COMMAND, intent).handled() ? 1 : 0;
   }

   private static int usage(CommandContext<CommandSourceStack> context, String key) throws CommandSyntaxException {
      FastPlaceMessages.chat(context.getSource().getPlayerOrException(), FastPlaceMessages.text("fastformer.message." + key));
      return 0;
   }
}
