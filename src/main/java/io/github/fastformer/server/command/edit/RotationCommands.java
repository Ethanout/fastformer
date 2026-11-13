package io.github.fastformer.server.command.edit;

import io.github.fastformer.core.command.IntentSource;
import io.github.fastformer.server.intent.PlayerIntentSystem;
import io.github.fastformer.server.intent.GeometryIntents;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.fastformer.fastplace.text.FastPlaceMessages;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import static io.github.fastformer.server.command.CommandChecks.commandCanOperate;
import io.github.fastformer.server.command.syntax.CommandParameter;
import io.github.fastformer.server.command.syntax.IntentCommands;

/** Feature-owned command declarations and execution adapters. */
public final class RotationCommands {
   private RotationCommands() {}

   public static LiteralArgumentBuilder<CommandSourceStack> rotateCommand() {
      var root = Commands.literal("rotate");
      var x = CommandParameter.number("x");
      var y = CommandParameter.number("y");
      var z = CommandParameter.number("z");
      var w = CommandParameter.number("w");
      root.then(IntentCommands.route("euler", context -> new GeometryIntents.Euler(
         x.read(context), y.read(context), z.read(context)), x, y, z));
      root.then(IntentCommands.route("quat", context -> new GeometryIntents.Quaternion(
         w.read(context), x.read(context), y.read(context), z.read(context)), w, x, y, z));
      root.then(Commands.literal("mat").then(Commands.argument("values", StringArgumentType.greedyString()).executes(context -> {
         double[] values = parseNumbers(StringArgumentType.getString(context, "values"));
         if (values.length != 9) {
            FastPlaceMessages.chat(context.getSource().getPlayerOrException(), FastPlaceMessages.text("fastformer.message.rotate_matrix_usage"));
            return 0;
         }
         ServerPlayer player = context.getSource().getPlayerOrException();
         return commandCanOperate(player) && PlayerIntentSystem.dispatch(player, IntentSource.COMMAND,
            new GeometryIntents.Matrix(values)).handled() ? 1 : 0;
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
            return commandCanOperate(player) && PlayerIntentSystem.dispatch(player, IntentSource.COMMAND,
               new GeometryIntents.RotationStep(axis, IntegerArgumentType.getInteger(context, "steps"), fine)).handled() ? 1 : 0;
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
            return PlayerIntentSystem.dispatch(player, IntentSource.COMMAND,
               new GeometryIntents.Quaternion(values[0], values[1], values[2], values[3])).handled() ? 1 : 0;
         }
         FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.rotate_quat_usage"));
         return 0;
      }
      if (normalized.startsWith("mat")) {
         if (values.length == 9) {
            return PlayerIntentSystem.dispatch(player, IntentSource.COMMAND, new GeometryIntents.Matrix(values)).handled() ? 1 : 0;
         }
         FastPlaceMessages.chat(player, FastPlaceMessages.text("fastformer.message.rotate_mat_usage"));
         return 0;
      }
      if (values.length == 3) {
         return PlayerIntentSystem.dispatch(player, IntentSource.COMMAND,
            new GeometryIntents.Euler(values[0], values[1], values[2])).handled() ? 1 : 0;
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
}
