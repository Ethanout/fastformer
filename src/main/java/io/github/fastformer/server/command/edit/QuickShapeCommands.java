package io.github.fastformer.server.command.edit;

import io.github.fastformer.core.command.IntentSource;
import io.github.fastformer.server.intent.PlayerIntentSystem;
import io.github.fastformer.server.intent.quickshape.QuickShapeIntents;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.fastformer.common.quickshape.mode.FaceMode;
import io.github.fastformer.common.quickshape.mode.LineMode;
import io.github.fastformer.common.quickshape.mode.VolumeMode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import static io.github.fastformer.server.gate.CommandAdmission.available;
import static io.github.fastformer.server.command.CommandChecks.validNumber;
import io.github.fastformer.server.command.syntax.CommandParameter;
import io.github.fastformer.server.command.syntax.IntentCommands;
import io.github.fastformer.server.command.syntax.CommandChoices;
import java.util.Map;

/** Feature-owned command declarations and execution adapters. */
public final class QuickShapeCommands {
   private QuickShapeCommands() {}

   public static LiteralArgumentBuilder<CommandSourceStack> lineCommand() {
      return Commands.literal("line")
         .then(lineFreeDistanceCommand())
         .then(lineModeCommand());
   }

   public static LiteralArgumentBuilder<CommandSourceStack> faceCommand() {
      return Commands.literal("face")
         .then(faceAngleValueCommand())
         .then(faceModeSelectCommand());
   }

   public static LiteralArgumentBuilder<CommandSourceStack> volumeCommand() {
      return Commands.literal("volume")
         .then(volumeDistanceCommand())
         .then(volumeModeCommand());
   }

   public static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, Double> contextValueCommand() {
      return Commands.argument("value", DoubleArgumentType.doubleArg())
         .executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            if (!available(player)) {
               return 0;
            }
            double value = DoubleArgumentType.getDouble(context, "value");
            if (!validNumber(player, value)) {
               return 0;
            }
            return PlayerIntentSystem.dispatch(player, IntentSource.COMMAND, new QuickShapeIntents.ContextValue(value)).handled() ? 1 : 0;
         })
         .then(Commands.argument("x", DoubleArgumentType.doubleArg()).executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            if (!available(player)) {
               return 0;
            }
            double y = DoubleArgumentType.getDouble(context, "value");
            double x = DoubleArgumentType.getDouble(context, "x");
            if (!validNumber(player, y, x)) {
               return 0;
            }
            return PlayerIntentSystem.dispatch(player, IntentSource.COMMAND,
               new QuickShapeIntents.FaceAngle(Math.toDegrees(Math.atan2(y, x)))).handled() ? 1 : 0;
         }));
   }

   private static LiteralArgumentBuilder<CommandSourceStack> lineFreeDistanceCommand() {
      var blocks = CommandParameter.integer("blocks", 1, 128);
      return IntentCommands.route("free_distance", context -> new QuickShapeIntents.LineDistance(blocks.read(context)), blocks);
   }

   private static LiteralArgumentBuilder<CommandSourceStack> lineModeCommand() {
      return new CommandChoices<>(Map.of(
         "axis", LineMode.AXIS,
         "free_scroll", LineMode.FREE_SCROLL,
         "raycast", LineMode.RAYCAST),
         "fastformer.message.line_mode_usage").intent("mode", QuickShapeIntents.SelectStageMode::new);
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
      return new CommandChoices<>(Map.of(
         "coordinate_plane", FaceMode.COORDINATE_PLANE,
         "base_plane", FaceMode.PARALLELOGRAM_BASE_PLANE,
         "parallelogram_base_plane", FaceMode.PARALLELOGRAM_BASE_PLANE,
         "polygon", FaceMode.POLYGON),
         "fastformer.message.face_mode_usage").intent("mode", QuickShapeIntents.SelectStageMode::new);
   }

   private static LiteralArgumentBuilder<CommandSourceStack> volumeDistanceCommand() {
      var blocks = CommandParameter.integer("blocks", -128, 128);
      return IntentCommands.route("distance", context -> new QuickShapeIntents.VolumeDistance(blocks.read(context)), blocks);
   }

   private static LiteralArgumentBuilder<CommandSourceStack> volumeModeCommand() {
      return new CommandChoices<>(Map.of(
         "perpendicular_to_face", VolumeMode.PERPENDICULAR_TO_FACE,
         "free", VolumeMode.FREE),
         "fastformer.message.volume_mode_usage").intent("mode", QuickShapeIntents.SelectStageMode::new);
   }

   private static int setFaceAngle(ServerPlayer player, double degrees) {
      if (!available(player)) {
         return 0;
      }
      if (!validNumber(player, degrees)) {
         return 0;
      }
      return PlayerIntentSystem.dispatch(player, IntentSource.COMMAND, new QuickShapeIntents.FaceAngle(degrees)).handled() ? 1 : 0;
   }
}
