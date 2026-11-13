package io.github.fastformer.server.command;

import io.github.fastformer.server.session.SessionSystem;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.fastformer.fastplace.geometry.FillMode;
import io.github.fastformer.server.history.HistoryService;
import io.github.fastformer.fastplace.placement.PlacementUpdateMode;
import io.github.fastformer.fastplace.settings.FastPlaceSettings;
import io.github.fastformer.fastplace.text.FastPlaceMessages;
import io.github.fastformer.network.FastPlaceNetwork;
import io.github.fastformer.protocol.s2c.settings.OpenSettingsPayload;
import io.github.fastformer.server.input.ServerInputDispatcher;
import io.github.fastformer.server.session.QuickShapeSystem;
import io.github.fastformer.workspace.submission.OperationConflictMode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import static io.github.fastformer.server.command.CommandChecks.commandCanOperate;

/** Feature-owned command declarations and execution adapters. */
public final class SettingsCommands {
   private SettingsCommands() {}

   public static LiteralArgumentBuilder<CommandSourceStack> forcePlacementCommand() {
      return Commands.literal("forceplace").then(Commands.argument("enabled", BoolArgumentType.bool()).executes(context -> {
         var player = context.getSource().getPlayerOrException();
         boolean value = BoolArgumentType.getBool(context, "enabled");
         FastPlaceSettings.load(player).setForcePlacement(player, value);
         io.github.fastformer.server.sync.PreviewSync.syncInteractionUpdates(player);
         context.getSource().sendSuccess(() -> Component.translatable("fastformer.message.force_placement", value), false);
         return 1;
      }));
   }

   public static LiteralArgumentBuilder<CommandSourceStack> placementCommand() {
      return Commands.literal("placement")
         .then(Commands.literal("conflict").then(Commands.argument("mode", StringArgumentType.word()).executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            OperationConflictMode mode = switch (StringArgumentType.getString(context, "mode")) {
               case "replace" -> OperationConflictMode.REPLACE;
               case "keep_existing" -> OperationConflictMode.KEEP_EXISTING;
               default -> null;
            };
            if (mode == null) {
               FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.placement_conflict_usage"));
               return 0;
            }
            FastPlaceSettings settings = FastPlaceSettings.load(player);
            settings.setPlacementConflictMode(player, mode);
            FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.placement_conflict_value", FastPlaceMessages.text(mode)));
            return 1;
         })))
         .then(Commands.literal("updates").then(Commands.argument("mode", StringArgumentType.word()).executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            PlacementUpdateMode mode = PlacementUpdateMode.parse(StringArgumentType.getString(context, "mode"));
            if (mode == null) {
               FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.placement_update_usage"));
               return 0;
            }
            FastPlaceSettings settings = FastPlaceSettings.load(player);
            settings.setPlacementUpdateMode(player, mode);
            FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.placement_update_value", FastPlaceMessages.text(mode)));
            return 1;
         })));
   }

   public static LiteralArgumentBuilder<CommandSourceStack> undoHistoryLimitCommand() {
      return Commands.literal("undo_history")
         .executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            if (!commandCanOperate(player)) {
               return 0;
            }
            FastPlaceMessages.chat(
               player,
               FastPlaceMessages.text("fastformer.message.history_limit_status", FastPlaceSettings.load(player).worldUndoHistoryLimit())
            );
            return 1;
         })
         .then(Commands.argument("count", IntegerArgumentType.integer(1, HistoryService.MAX_LIMIT)).executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            if (!commandCanOperate(player)) {
               return 0;
            }
            FastPlaceSettings settings = FastPlaceSettings.load(player);
            settings.setWorldUndoHistoryLimit(player, IntegerArgumentType.getInteger(context, "count"));
            FastPlaceMessages.chat(
               player,
               FastPlaceMessages.text("fastformer.message.history_limit_set", settings.worldUndoHistoryLimit())
            );
            return 1;
         }));
   }

   public static LiteralArgumentBuilder<CommandSourceStack> sessionUndoHistoryLimitCommand() {
      return Commands.literal("session_undo_history")
         .executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            if (!commandCanOperate(player)) {
               return 0;
            }
            FastPlaceMessages.chat(
               player,
               FastPlaceMessages.text(
                  "fastformer.message.session_history_limit_status",
                  FastPlaceSettings.load(player).sessionUndoHistoryLimit()
               )
            );
            return 1;
         })
         .then(Commands.argument("count", IntegerArgumentType.integer(1, FastPlaceSettings.MAX_UNDO_HISTORY_LIMIT)).executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            if (!commandCanOperate(player)) {
               return 0;
            }
            FastPlaceSettings settings = FastPlaceSettings.load(player);
            settings.setSessionUndoHistoryLimit(player, IntegerArgumentType.getInteger(context, "count"));
            FastPlaceMessages.chat(
               player,
               FastPlaceMessages.text("fastformer.message.session_history_limit_set", settings.sessionUndoHistoryLimit())
            );
            return 1;
         }));
   }

   public static LiteralArgumentBuilder<CommandSourceStack> middleConfirmCommand() {
      return Commands.literal("middle_confirm")
         .executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            FastPlaceMessages.chat(
               player,
               FastPlaceMessages.text(
                  "fastformer.message.middle_confirm_status",
                  FastPlaceMessages.text(
                     FastPlaceSettings.load(player).middleConfirmEnabled()
                        ? "fastformer.message.enabled"
                        : "fastformer.message.disabled"
                  )
               )
            );
            return 1;
         })
         .then(Commands.argument("enabled", BoolArgumentType.bool()).executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            FastPlaceSettings settings = FastPlaceSettings.load(player);
            settings.setMiddleConfirmEnabled(player, BoolArgumentType.getBool(context, "enabled"));
            SessionSystem.of(player).syncCurrentPreview(player);
            FastPlaceMessages.chat(
               player,
               FastPlaceMessages.text(
                  "fastformer.message.middle_confirm_set",
                  FastPlaceMessages.text(settings.middleConfirmEnabled() ? "fastformer.message.enabled" : "fastformer.message.disabled")
               )
            );
            return 1;
         }));
   }

   public static LiteralArgumentBuilder<CommandSourceStack> settingsCommand() {
      return Commands.literal("settings").executes(context -> {
         ServerPlayer player = context.getSource().getPlayerOrException();
         if (!player.connection.hasChannel(OpenSettingsPayload.TYPE)) {
            FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.settings_client_unavailable"));
            return 0;
         }
         FastPlaceSettings settings = FastPlaceSettings.load(player);
         PacketDistributor.sendToPlayer(
            player,
            new OpenSettingsPayload(
               settings.middleConfirmEnabled(),
               settings.placementConflictMode(),
               settings.placementUpdateMode(),
               settings.enabledPlacementEffects().stream().sorted().toList(),
               settings.emptyHandWrench(),
               io.github.fastformer.fastplace.world.BlockActivityRules.get(player.getServer()).fallingDisabled(),
               settings.worldUndoHistoryLimit(),
               settings.sessionUndoHistoryLimit(),
               settings.tinkerHistoryMergeLimits(),
               settings.skipRepeatedCropStages(),
               settings.vanillaBlockHistory()
            ),
            new net.minecraft.network.protocol.common.custom.CustomPacketPayload[0]
         );
         return 1;
      });
   }

   public static LiteralArgumentBuilder<CommandSourceStack> fillModeCommand() {
      return Commands.literal("fillmode").executes(context -> {
         ServerPlayer player = context.getSource().getPlayerOrException();
         FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.fill_mode_status", FastPlaceMessages.text(FastPlaceSettings.load(player).fillMode())));
         return 1;
      }).then(Commands.argument("value", StringArgumentType.word()).executes(context -> {
         ServerPlayer player = context.getSource().getPlayerOrException();
         FillMode fillMode = FillMode.parse(StringArgumentType.getString(context, "value"));
         if (fillMode == null) {
            FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.fill_mode_usage"));
            return 0;
         }
         FastPlaceSettings settings = FastPlaceSettings.load(player);
         settings.setFillMode(player, fillMode);
         SessionSystem.of(player).syncCurrentPreview(player);
         FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.fill_mode_status", FastPlaceMessages.text(fillMode)));
         return 1;
      }));
   }

   public static LiteralArgumentBuilder<CommandSourceStack> maxPlacementCommand() {
      return Commands.literal("max_placement").executes(context -> {
         ServerPlayer player = context.getSource().getPlayerOrException();
         FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.max_placement_status", FastPlaceSettings.load(player).maxPlacement()));
         return 1;
      }).then(Commands.argument("blocks", IntegerArgumentType.integer(1, 20972152)).executes(context -> {
         QuickShapeSystem.of(context.getSource().getPlayerOrException()).setMaxPlacement(context.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(context, "blocks"));
         return 1;
      }));
   }

   public static LiteralArgumentBuilder<CommandSourceStack> toggleCommand() {
      return Commands.literal("toggle").executes(context -> {
         ServerPlayer player = context.getSource().getPlayerOrException();
         FastPlaceSettings settings = FastPlaceSettings.load(player);
         settings.toggleEnabled(player);
         if (settings.enabled()) {
            SessionSystem.of(player).cancelEditing(player);
         } else {
            ServerInputDispatcher.stopBecauseUnavailable(player);
         }
         FastPlaceNetwork.syncSettings(player);
         FastPlaceMessages.chat(player, FastPlaceMessages.text(settings.enabled() ? "fastformer.message.enabled" : "fastformer.message.disabled"));
         return 1;
      });
   }

   public static LiteralArgumentBuilder<CommandSourceStack> statusCommand() {
      return Commands.literal("status").executes(context -> {
         ServerPlayer player = context.getSource().getPlayerOrException();
         FastPlaceSettings settings = FastPlaceSettings.load(player);
         FastPlaceMessages.chat(
            player,
            FastPlaceMessages.text("fastformer.message.status", FastPlaceMessages.text(settings.enabled() ? "fastformer.message.enabled" : "fastformer.message.disabled"), FastPlaceMessages.text(settings.fillMode()), settings.maxPlacement())
         );
         return 1;
      });
   }
}
