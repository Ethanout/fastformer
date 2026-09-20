package io.github.fastformer.fastplace.command;

import io.github.fastformer.fastplace.quickshape.LineMode;
import io.github.fastformer.fastplace.quickshape.FaceMode;
import io.github.fastformer.fastplace.quickshape.VolumeMode;

import io.github.fastformer.fastplace.selection.OperationSelectionMode;

import io.github.fastformer.fastplace.world.*;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.fastformer.fastplace.geometry.GeometryNumbers;
import io.github.fastformer.network.FastPlaceNetwork;
import io.github.fastformer.fastplace.*;
import io.github.fastformer.network.payload.settings.OpenSettingsPayload;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;

public final class FastPlaceCommandRegistry {
   private FastPlaceCommandRegistry() {
   }

   public static void register() {
      NeoForge.EVENT_BUS.addListener(FastPlaceCommandRegistry::onRegisterCommands);
   }

   private static void onRegisterCommands(RegisterCommandsEvent event) {
      CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
      registerCommand(dispatcher, "ff");
      registerCommand(dispatcher, "fastformer");
   }

   private static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher, String name) {
      dispatcher.register(
         Commands.literal(name)
            .then(contextValueCommand())
            .then(lineCommand())
            .then(faceCommand())
            .then(volumeCommand())
            .then(operationCommand())
            .then(geometryCommand())
            .then(actionCommand())
            .then(rotateCommand())
            .then(placementCommand())
            .then(reachCommand())
            .then(undoCommand())
            .then(redoCommand())
            .then(fillModeCommand())
            .then(maxPlacementCommand())
            .then(undoHistoryLimitCommand())
            .then(sessionUndoHistoryLimitCommand())
            .then(historyCleanupCommand())
            .then(middleConfirmCommand())
            .then(settingsCommand())
            .then(toggleCommand())
            .then(cancelCommand())
            .then(modeCommand())
            .then(statusCommand())
      );
   }

   private static LiteralArgumentBuilder<CommandSourceStack> lineCommand() {
      return Commands.literal("line")
         .then(lineFreeDistanceCommand())
         .then(lineModeCommand());
   }

   private static LiteralArgumentBuilder<CommandSourceStack> faceCommand() {
      return Commands.literal("face")
         .then(faceAngleValueCommand())
         .then(faceModeSelectCommand());
   }

   private static LiteralArgumentBuilder<CommandSourceStack> volumeCommand() {
      return Commands.literal("volume")
         .then(volumeDistanceCommand())
         .then(volumeModeCommand());
   }

   private static LiteralArgumentBuilder<CommandSourceStack> operationCommand() {
      return Commands.literal("operation")
         .then(Commands.literal("apply").executes(context -> commandCanOperate(context.getSource().getPlayerOrException()) && OperationManager.apply(context.getSource().getPlayerOrException(), false) ? 1 : 0))
         .then(Commands.literal("copy").executes(context -> commandCanOperate(context.getSource().getPlayerOrException()) && OperationManager.apply(context.getSource().getPlayerOrException(), true) ? 1 : 0))
         .then(Commands.literal("undo").executes(context -> commandCanOperate(context.getSource().getPlayerOrException()) && ServerInputDispatcher.commandWorldUndo(context.getSource().getPlayerOrException(), 1) ? 1 : 0))
         .then(Commands.literal("mode").executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            if (!commandCanOperate(player)) {
               return 0;
            }
            OperationManager.cycleMode(player);
            return OperationManager.active(player) ? 1 : 0;
         }))
         .then(operationSelectionCommand())
         .then(operationConflictCommand());
   }

   private static LiteralArgumentBuilder<CommandSourceStack> geometryCommand() {
      return Commands.literal("geometry")
         .then(geometryModeCommand())
         .then(geometryConePlaneCommand())
         .then(geometryPolyhedronSizeCommand())
         .then(geometryEllipseCommand())
         .then(rotateCommand());
   }

   private static LiteralArgumentBuilder<CommandSourceStack> actionCommand() {
      return Commands.literal("action")
         .then(Commands.literal("cycle").executes(context ->
            ServerInputDispatcher.commandCycleMode(context.getSource().getPlayerOrException()) ? 1 : 0
         ))
         .then(Commands.literal("adjust")
            .then(Commands.argument("steps", IntegerArgumentType.integer(-128, 128)).executes(context ->
               ServerInputDispatcher.commandAdjust(
                  context.getSource().getPlayerOrException(),
                  IntegerArgumentType.getInteger(context, "steps")
               ) ? 1 : 0
            )))
         .then(Commands.literal("confirm").executes(context ->
            ServerInputDispatcher.commandConfirm(context.getSource().getPlayerOrException()) ? 1 : 0
         ))
         .then(Commands.literal("back").executes(context ->
            ServerInputDispatcher.commandBack(context.getSource().getPlayerOrException()) ? 1 : 0
         ))
         .then(Commands.literal("cancel").executes(context -> {
            ServerInputDispatcher.quit(context.getSource().getPlayerOrException());
            return 1;
         }))
         .then(Commands.literal("submode")
            .then(Commands.literal("on").executes(context ->
               ServerInputDispatcher.commandSubmode(context.getSource().getPlayerOrException(), true) ? 1 : 0
            ))
            .then(Commands.literal("off").executes(context ->
               ServerInputDispatcher.commandSubmode(context.getSource().getPlayerOrException(), false) ? 1 : 0
            )));
   }

   private static LiteralArgumentBuilder<CommandSourceStack> placementCommand() {
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

   private static LiteralArgumentBuilder<CommandSourceStack> reachCommand() {
      return Commands.literal("reach")
         .executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            FastPlaceMessages.chat(
               player,
               FastPlaceMessages.text("fastformer.message.reach_status",
                  GeometryNumbers.fixed(player.blockInteractionRange(), 2),
                  GeometryNumbers.fixed(player.entityInteractionRange(), 2)
               )
            );
            return 1;
         })
         .then(Commands.literal("set").then(Commands.argument("blocks", DoubleArgumentType.doubleArg(0.0, 64.0)).executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            double value = DoubleArgumentType.getDouble(context, "blocks");
            if (!validNumber(player, value)) {
               return 0;
            }
            setReachBase(player, value, value);
            FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.reach_base", value));
            return 1;
         })))
         .then(Commands.literal("reset").executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            resetReachBase(player, true, true);
            FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.reach_base_reset"));
            return 1;
         }))
         .then(Commands.literal("block")
            .then(Commands.literal("set").then(Commands.argument("blocks", DoubleArgumentType.doubleArg(0.0, 64.0)).executes(context -> {
               ServerPlayer player = context.getSource().getPlayerOrException();
               double value = DoubleArgumentType.getDouble(context, "blocks");
               if (!validNumber(player, value)) {
                  return 0;
               }
               setBlockReachBase(player, value);
               FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.block_reach_base", value));
               return 1;
            })))
            .then(Commands.literal("reset").executes(context -> {
               ServerPlayer player = context.getSource().getPlayerOrException();
               resetReachBase(player, true, false);
               FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.block_reach_base_reset"));
               return 1;
            })))
         .then(Commands.literal("entity")
            .then(Commands.literal("set").then(Commands.argument("blocks", DoubleArgumentType.doubleArg(0.0, 64.0)).executes(context -> {
               ServerPlayer player = context.getSource().getPlayerOrException();
               double value = DoubleArgumentType.getDouble(context, "blocks");
               if (!validNumber(player, value)) {
                  return 0;
               }
               setEntityReachBase(player, value);
               FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.entity_reach_base", value));
               return 1;
            })))
            .then(Commands.literal("reset").executes(context -> {
               ServerPlayer player = context.getSource().getPlayerOrException();
               resetReachBase(player, false, true);
               FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.entity_reach_base_reset"));
               return 1;
            })));
   }

   private static LiteralArgumentBuilder<CommandSourceStack> undoCommand() {
      return Commands.literal("undo")
         .executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            return commandCanOperate(player) && ServerInputDispatcher.commandWorldUndo(player, 1) ? 1 : 0;
         })
         .then(Commands.argument("count", IntegerArgumentType.integer(1, WorldHistoryManager.MAX_LIMIT)).executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            return commandCanOperate(player)
               && ServerInputDispatcher.commandWorldUndo(player, IntegerArgumentType.getInteger(context, "count")) ? 1 : 0;
         }));
   }

   private static LiteralArgumentBuilder<CommandSourceStack> redoCommand() {
      return Commands.literal("redo")
         .executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            return commandCanOperate(player) && WorldHistoryManager.requestRedo(player, 1) ? 1 : 0;
         })
         .then(Commands.argument("count", IntegerArgumentType.integer(1, WorldHistoryManager.MAX_LIMIT)).executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            return commandCanOperate(player)
               && WorldHistoryManager.requestRedo(player, IntegerArgumentType.getInteger(context, "count")) ? 1 : 0;
         }));
   }

   private static LiteralArgumentBuilder<CommandSourceStack> undoHistoryLimitCommand() {
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
         .then(Commands.argument("count", IntegerArgumentType.integer(1, WorldHistoryManager.MAX_LIMIT)).executes(context -> {
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

   private static LiteralArgumentBuilder<CommandSourceStack> sessionUndoHistoryLimitCommand() {
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

   private static LiteralArgumentBuilder<CommandSourceStack> historyCleanupCommand() {
      return Commands.literal("history_cleanup")
         .requires(source -> source.hasPermission(2))
         .executes(context -> {
            WorldHistoryManager.resumeDiskCleanup(context.getSource().getServer());
            context.getSource().sendSuccess(() -> Component.literal("FastFormer history cleanup started."), true);
            return 1;
         });
   }

   private static LiteralArgumentBuilder<CommandSourceStack> middleConfirmCommand() {
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
            FastPlaceManager.syncCurrentPreview(player);
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

   private static LiteralArgumentBuilder<CommandSourceStack> settingsCommand() {
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
               settings.faceRasterizationMode(),
               settings.placementConflictMode(),
               settings.placementUpdateMode(),
               settings.enabledPlacementEffects().stream().sorted().toList(),
               settings.emptyHandWrench(),
               player.getServer().tickRateManager().isFrozen(),
               settings.worldUndoHistoryLimit(),
               settings.sessionUndoHistoryLimit()
            ),
            new net.minecraft.network.protocol.common.custom.CustomPacketPayload[0]
         );
         return 1;
      });
   }

   private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Double> contextValueCommand() {
      return Commands.argument("value", DoubleArgumentType.doubleArg())
         .executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            if (!commandCanOperate(player)) {
               return 0;
            }
            double value = DoubleArgumentType.getDouble(context, "value");
            if (!validNumber(player, value)) {
               return 0;
            }
            if (!FastPlaceManager.setContextValue(player, value)) {
               FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.context_value_unavailable"));
               return 0;
            }
            return 1;
         })
         .then(Commands.argument("x", DoubleArgumentType.doubleArg()).executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            if (!commandCanOperate(player)) {
               return 0;
            }
            double y = DoubleArgumentType.getDouble(context, "value");
            double x = DoubleArgumentType.getDouble(context, "x");
            if (!validNumber(player, y, x)) {
               return 0;
            }
            if (!FastPlaceManager.setFaceAngle(player, Math.toDegrees(Math.atan2(y, x)))) {
               FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.face_angle_unavailable"));
               return 0;
            }
            return 1;
         }));
   }

   private static LiteralArgumentBuilder<CommandSourceStack> lineFreeDistanceCommand() {
      return Commands.literal("free_distance")
         .then(Commands.argument("blocks", IntegerArgumentType.integer(1, 128)).executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            if (!commandCanOperate(player)) {
               return 0;
            }
            if (!FastPlaceManager.setLineAngleDistance(player, IntegerArgumentType.getInteger(context, "blocks"))) {
               FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.line_free_distance_unavailable"));
               return 0;
            }
            return 1;
         }));
   }

   private static LiteralArgumentBuilder<CommandSourceStack> lineModeCommand() {
      return Commands.literal("mode")
         .then(Commands.argument("mode", StringArgumentType.word()).executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            if (!commandCanOperate(player)) {
               return 0;
            }
            LineMode mode = switch (StringArgumentType.getString(context, "mode")) {
               case "axis" -> LineMode.AXIS;
               case "free_scroll" -> LineMode.FREE_SCROLL;
               case "raycast" -> LineMode.RAYCAST;
               default -> null;
            };
            if (mode == null) {
               FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.line_mode_usage"));
               return 0;
            }
            FastPlaceManager.setStageMode(player, mode);
            return 1;
         }));
   }

   private static LiteralArgumentBuilder<CommandSourceStack> faceAngleValueCommand() {
      return Commands.literal("angle")
         .then(
            Commands.argument("degrees", DoubleArgumentType.doubleArg())
               .executes(context -> setFaceAngle(context.getSource().getPlayerOrException(), DoubleArgumentType.getDouble(context, "degrees")))
               .then(Commands.argument("x", DoubleArgumentType.doubleArg()).executes(context -> {
                  ServerPlayer player = context.getSource().getPlayerOrException();
                  double y = DoubleArgumentType.getDouble(context, "degrees");
                  double x = DoubleArgumentType.getDouble(context, "x");
                  return validNumber(player, y, x) ? setFaceAngle(player, Math.toDegrees(Math.atan2(y, x))) : 0;
               }))
         );
   }

   private static LiteralArgumentBuilder<CommandSourceStack> faceModeSelectCommand() {
      return Commands.literal("mode")
         .then(Commands.argument("mode", StringArgumentType.word()).executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            if (!commandCanOperate(player)) {
               return 0;
            }
            FaceMode mode = switch (StringArgumentType.getString(context, "mode")) {
               case "coordinate_plane" -> FaceMode.COORDINATE_PLANE;
               case "base_plane", "parallelogram_base_plane" -> FaceMode.PARALLELOGRAM_BASE_PLANE;
               case "polygon" -> FaceMode.POLYGON;
               default -> null;
            };
            if (mode == null) {
               FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.face_mode_usage"));
               return 0;
            }
            FastPlaceManager.setStageMode(player, mode);
            return 1;
         }));
   }

   private static LiteralArgumentBuilder<CommandSourceStack> volumeDistanceCommand() {
      return Commands.literal("distance")
         .then(Commands.argument("blocks", IntegerArgumentType.integer(-128, 128)).executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            if (!commandCanOperate(player)) {
               return 0;
            }
            if (!FastPlaceManager.setVolumeDistance(player, IntegerArgumentType.getInteger(context, "blocks"))) {
               FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.volume_distance_unavailable"));
               return 0;
            }
            return 1;
         }));
   }

   private static LiteralArgumentBuilder<CommandSourceStack> volumeModeCommand() {
      return Commands.literal("mode")
         .then(Commands.argument("mode", StringArgumentType.word()).executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            if (!commandCanOperate(player)) {
               return 0;
            }
            VolumeMode mode = switch (StringArgumentType.getString(context, "mode")) {
               case "perpendicular_to_face" -> VolumeMode.PERPENDICULAR_TO_FACE;
               case "free" -> VolumeMode.FREE;
               default -> null;
            };
            if (mode == null) {
               FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.volume_mode_usage"));
               return 0;
            }
            FastPlaceManager.setStageMode(player, mode);
            return 1;
         }));
   }

   private static LiteralArgumentBuilder<CommandSourceStack> geometryModeCommand() {
      return Commands.literal("mode")
         .then(Commands.argument("mode", StringArgumentType.word()).executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            if (!commandCanOperate(player)) {
               return 0;
            }
            GeometryMode mode = switch (StringArgumentType.getString(context, "mode")) {
               case "wall" -> GeometryMode.WALL;
               case "polyhedron" -> GeometryMode.POLYHEDRON;
               case "cone_prism" -> GeometryMode.CONE_PRISM;
               case "compound" -> GeometryMode.COMPOUND;
               case "convex_polyhedron" -> GeometryMode.CONVEX_POLYHEDRON;
               default -> null;
            };
            if (mode == null) {
               FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.geometry_mode_usage"));
               return 0;
            }
            return ServerInputDispatcher.selectGeometryMode(player, mode) ? 1 : 0;
         }));
   }

   private static LiteralArgumentBuilder<CommandSourceStack> geometryConePlaneCommand() {
      return Commands.literal("cone_plane").then(Commands.argument("mode", StringArgumentType.word()).executes(context -> {
         ServerPlayer player = context.getSource().getPlayerOrException();
         if (!commandCanOperate(player)) {
            return 0;
         }
         ConePlaneMode mode = switch (StringArgumentType.getString(context, "mode")) {
            case "radius" -> ConePlaneMode.RADIUS;
            case "diameter" -> ConePlaneMode.DIAMETER;
            case "three_point" -> ConePlaneMode.THREE_POINT;
            default -> null;
         };
         if (mode == null) {
            FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.cone_plane_usage"));
            return 0;
         }
         return GeometryManager.setConePlaneMode(player, mode) ? 1 : 0;
      }));
   }

   private static LiteralArgumentBuilder<CommandSourceStack> geometryPolyhedronSizeCommand() {
      return Commands.literal("polyhedron_size").then(Commands.argument("mode", StringArgumentType.word()).executes(context -> {
         ServerPlayer player = context.getSource().getPlayerOrException();
         if (!commandCanOperate(player)) {
            return 0;
         }
         PolyhedronSizeMode mode = switch (StringArgumentType.getString(context, "mode")) {
            case "radius" -> PolyhedronSizeMode.RADIUS;
            case "diameter" -> PolyhedronSizeMode.DIAMETER;
            default -> null;
         };
         if (mode == null) {
            FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.polyhedron_size_usage"));
            return 0;
         }
         return GeometryManager.setPolyhedronSizeMode(player, mode) ? 1 : 0;
      }));
   }

   private static LiteralArgumentBuilder<CommandSourceStack> geometryEllipseCommand() {
      return Commands.literal("ellipse")
         .then(Commands.argument("x", DoubleArgumentType.doubleArg(0.125, 8.0))
            .then(Commands.argument("z", DoubleArgumentType.doubleArg(0.125, 8.0)).executes(context -> {
               ServerPlayer player = context.getSource().getPlayerOrException();
               if (!commandCanOperate(player)) {
                  return 0;
               }
               double x = DoubleArgumentType.getDouble(context, "x");
               double z = DoubleArgumentType.getDouble(context, "z");
               return validNumber(player, x, z) && GeometryManager.setConeEllipse(player, x, z) ? 1 : 0;
            })));
   }

   private static LiteralArgumentBuilder<CommandSourceStack> rotateCommand() {
      LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("rotate");
      root.then(
         Commands.literal("euler")
            .then(
               Commands.argument("x", DoubleArgumentType.doubleArg())
                  .then(
                     Commands.argument("y", DoubleArgumentType.doubleArg())
                        .then(Commands.argument("z", DoubleArgumentType.doubleArg()).executes(context -> {
                           ServerPlayer player = context.getSource().getPlayerOrException();
                           return commandCanOperate(player)
                              && GeometryManager.rotateEuler(
                                 player,
                                 DoubleArgumentType.getDouble(context, "x"),
                                 DoubleArgumentType.getDouble(context, "y"),
                                 DoubleArgumentType.getDouble(context, "z")
                              )
                              ? 1
                              : 0;
                        }))
                  )
            )
      );
      root.then(
         Commands.literal("quat")
            .then(
               Commands.argument("w", DoubleArgumentType.doubleArg())
                  .then(
                     Commands.argument("x", DoubleArgumentType.doubleArg())
                        .then(
                           Commands.argument("y", DoubleArgumentType.doubleArg())
                              .then(Commands.argument("z", DoubleArgumentType.doubleArg()).executes(context -> {
                                 ServerPlayer player = context.getSource().getPlayerOrException();
                                 return commandCanOperate(player)
                                    && GeometryManager.rotateQuaternion(
                                       player,
                                       DoubleArgumentType.getDouble(context, "w"),
                                       DoubleArgumentType.getDouble(context, "x"),
                                       DoubleArgumentType.getDouble(context, "y"),
                                       DoubleArgumentType.getDouble(context, "z")
                                    )
                                    ? 1
                                    : 0;
                              }))
                        )
                  )
            )
      );
      root.then(Commands.literal("mat").then(Commands.argument("values", StringArgumentType.greedyString()).executes(context -> {
         double[] values = parseNumbers(StringArgumentType.getString(context, "values"));
         if (values.length != 9) {
            FastPlaceMessages.chat(context.getSource().getPlayerOrException(), FastPlaceMessages.text("fastformer.message.rotate_matrix_usage"));
            return 0;
         }
         ServerPlayer player = context.getSource().getPlayerOrException();
         return commandCanOperate(player) && GeometryManager.rotateMatrix(player, values) ? 1 : 0;
      })));
      root.then(Commands.literal("snap").then(rotateSnapCommand(false)));
      root.then(Commands.literal("fine").then(rotateSnapCommand(true)));
      root.then(Commands.argument("spec", StringArgumentType.greedyString()).executes(context -> rotateFromSpec(
         context.getSource().getPlayerOrException(), StringArgumentType.getString(context, "spec")
      )));
      return root;
   }

   private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> rotateSnapCommand(boolean fine) {
      return Commands.argument("axis", StringArgumentType.word())
         .then(Commands.argument("steps", IntegerArgumentType.integer()).executes(context -> {
            int axis = parseAxis(StringArgumentType.getString(context, "axis"));
            if (axis < 0) {
               FastPlaceMessages.chat(context.getSource().getPlayerOrException(), FastPlaceMessages.text("fastformer.message.rotate_axis_usage"));
               return 0;
            }
            ServerPlayer player = context.getSource().getPlayerOrException();
            return commandCanOperate(player) && GeometryManager.rotateSnap(player, axis, IntegerArgumentType.getInteger(context, "steps"), fine) ? 1 : 0;
         }));
   }

   private static int rotateFromSpec(ServerPlayer player, String spec) {
      if (!commandCanOperate(player)) {
         return 0;
      }
      String normalized = spec.strip().toLowerCase(java.util.Locale.ROOT);
      double[] values = parseNumbers(normalized);
      if (normalized.startsWith("quat")) {
         if (values.length == 4) {
            return GeometryManager.rotateQuaternion(player, values[0], values[1], values[2], values[3]) ? 1 : 0;
         }
         FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.rotate_quat_usage"));
         return 0;
      }
      if (normalized.startsWith("mat")) {
         if (values.length == 9) {
            return GeometryManager.rotateMatrix(player, values) ? 1 : 0;
         }
         FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.rotate_mat_usage"));
         return 0;
      }
      if (values.length == 3) {
         return GeometryManager.rotateEuler(player, values[0], values[1], values[2]) ? 1 : 0;
      }
      FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.rotate_usage"));
      return 0;
   }

   private static double[] parseNumbers(String value) {
      String cleaned = value.replaceAll("[a-zA-Z_]+|[(),;\\[\\]]", " ").trim();
      if (cleaned.isEmpty()) {
         return new double[0];
      }
      String[] parts = cleaned.split("\\s+");
      double[] values = new double[parts.length];
      for (int i = 0; i < parts.length; i++) {
         try {
            values[i] = Double.parseDouble(parts[i]);
         } catch (NumberFormatException exception) {
            return new double[0];
         }
         if (!Double.isFinite(values[i])) {
            return new double[0];
         }
      }
      return values;
   }

   private static int parseAxis(String value) {
      return switch (value.toLowerCase(java.util.Locale.ROOT)) {
         case "x" -> 0;
         case "y" -> 1;
         case "z" -> 2;
         default -> -1;
      };
   }

   private static LiteralArgumentBuilder<CommandSourceStack> operationSelectionCommand() {
      return Commands.literal("selection").then(Commands.argument("mode", StringArgumentType.word()).executes(context -> {
         ServerPlayer player = context.getSource().getPlayerOrException();
         OperationSelectionMode mode = switch (StringArgumentType.getString(context, "mode")) {
            case "cuboid" -> OperationSelectionMode.CUBOID;
            case "prism" -> OperationSelectionMode.PRISM;
            case "convex_hull" -> OperationSelectionMode.CONVEX_HULL;
            default -> null;
         };
         if (mode == null) {
            FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.operation_selection_usage"));
            return 0;
         }
         return commandCanOperate(player) && OperationManager.setSelectionMode(player, mode) ? 1 : 0;
      }));
   }

   private static LiteralArgumentBuilder<CommandSourceStack> operationConflictCommand() {
      return Commands.literal("conflict").then(Commands.argument("mode", StringArgumentType.word()).executes(context -> {
         ServerPlayer player = context.getSource().getPlayerOrException();
         OperationConflictMode mode = switch (StringArgumentType.getString(context, "mode")) {
            case "replace" -> OperationConflictMode.REPLACE;
            case "keep_existing" -> OperationConflictMode.KEEP_EXISTING;
            default -> null;
         };
         if (mode == null) {
            FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.operation_conflict_usage"));
            return 0;
         }
         return commandCanOperate(player) && OperationManager.setConflictMode(player, mode) ? 1 : 0;
      }));
   }

   private static LiteralArgumentBuilder<CommandSourceStack> fillModeCommand() {
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
         FastPlaceManager.syncCurrentPreview(player);
         FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.fill_mode_status", FastPlaceMessages.text(fillMode)));
         return 1;
      }));
   }

   private static LiteralArgumentBuilder<CommandSourceStack> maxPlacementCommand() {
      return Commands.literal("max_placement").executes(context -> {
         ServerPlayer player = context.getSource().getPlayerOrException();
         FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.max_placement_status", FastPlaceSettings.load(player).maxPlacement()));
         return 1;
      }).then(Commands.argument("blocks", IntegerArgumentType.integer(1, 20972152)).executes(context -> {
         FastPlaceManager.setMaxPlacement(context.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(context, "blocks"));
         return 1;
      }));
   }

   private static LiteralArgumentBuilder<CommandSourceStack> toggleCommand() {
      return Commands.literal("toggle").executes(context -> {
         ServerPlayer player = context.getSource().getPlayerOrException();
         FastPlaceSettings settings = FastPlaceSettings.load(player);
         settings.toggleEnabled(player);
         if (settings.enabled()) {
            FastPlaceManager.cancel(player);
         } else {
            ServerInputDispatcher.stopBecauseUnavailable(player);
         }
         FastPlaceNetwork.syncSettings(player);
         FastPlaceMessages.chat(player, FastPlaceMessages.text(settings.enabled() ? "fastformer.message.enabled" : "fastformer.message.disabled"));
         return 1;
      });
   }

   private static LiteralArgumentBuilder<CommandSourceStack> cancelCommand() {
      return Commands.literal("cancel").executes(context -> {
         FastPlaceManager.quit(context.getSource().getPlayerOrException());
         return 1;
      });
   }

   private static LiteralArgumentBuilder<CommandSourceStack> modeCommand() {
      return Commands.literal("mode").executes(context -> {
         ServerPlayer player = context.getSource().getPlayerOrException();
         if (!commandCanOperate(player)) {
            return 0;
         }
         ServerInputDispatcher.shortModifier(player);
         return 1;
      });
   }

   private static LiteralArgumentBuilder<CommandSourceStack> statusCommand() {
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

   private static int setFaceAngle(ServerPlayer player, double degrees) {
      if (!commandCanOperate(player)) {
         return 0;
      }
      if (!validNumber(player, degrees)) {
         return 0;
      }
      if (!FastPlaceManager.setFaceAngle(player, degrees)) {
         FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.face_angle_unavailable"));
         return 0;
      }
      return 1;
   }

   private static boolean validNumber(ServerPlayer player, double... values) {
      if (GeometryNumbers.finite(values)) {
         return true;
      }
      FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.number_invalid"));
      return false;
   }

   private static boolean commandCanOperate(ServerPlayer player) {
      if (ServerInputDispatcher.canOperate(player)) {
         return true;
      }
      ServerInputDispatcher.stopBecauseUnavailable(player);
      FastPlaceMessages.chat(
         player,
         FastPlaceMessages.text(
            PersistentRecoveryJournal.writesAllowed()
               ? "fastformer.message.command_unavailable"
               : "fastformer.message.recovery_journal_blocked"
         )
      );
      return false;
   }

   private static void setReachBase(ServerPlayer player, double blockReach, double entityReach) {
      setBlockReachBase(player, blockReach);
      setEntityReachBase(player, entityReach);
   }

   private static void setBlockReachBase(ServerPlayer player, double value) {
      AttributeInstance attribute = player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
      if (attribute != null) {
         attribute.setBaseValue(value);
      }
   }

   private static void setEntityReachBase(ServerPlayer player, double value) {
      AttributeInstance attribute = player.getAttribute(Attributes.ENTITY_INTERACTION_RANGE);
      if (attribute != null) {
         attribute.setBaseValue(value);
      }
   }

   private static void resetReachBase(ServerPlayer player, boolean block, boolean entity) {
      if (!block && !entity) {
         return;
      }
      AttributeInstance blockAttribute = player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
      double vanillaBlock = blockAttribute == null
         ? 4.5
         : blockAttribute.getAttribute().value().getDefaultValue();
      // FastFormer's reach pair is intentionally symmetric; reset restores the
      // vanilla block default and applies that same value to entity interaction.
      setReachBase(player, vanillaBlock, vanillaBlock);
   }

}
