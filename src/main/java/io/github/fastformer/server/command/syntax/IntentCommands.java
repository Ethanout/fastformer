package io.github.fastformer.server.command.syntax;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.github.fastformer.core.command.Intent;
import io.github.fastformer.core.command.IntentSource;
import io.github.fastformer.server.command.CommandChecks;
import io.github.fastformer.server.intent.PlayerIntentSystem;
import java.util.HashSet;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/** Builds argument chains from explicit schemas; intent construction stays type checked. */
public final class IntentCommands {
   private IntentCommands() {}

   public static LiteralArgumentBuilder<CommandSourceStack> route(String literal,
      Function<CommandContext<CommandSourceStack>, Intent> intent, CommandParameter<?>... parameters) {
      return routeCommand(literal, execute(intent), parameters);
   }

   public static LiteralArgumentBuilder<CommandSourceStack> routeCommand(String literal,
      Command<CommandSourceStack> command, CommandParameter<?>... parameters) {
      var root = Commands.literal(literal);
      return parameters.length == 0 ? root.executes(command) : root.then(arguments(command, parameters));
   }

   public static RequiredArgumentBuilder<CommandSourceStack, ?> arguments(
      Command<CommandSourceStack> command, CommandParameter<?>... parameters) {
      Objects.requireNonNull(command, "command");
      if (parameters.length == 0) throw new IllegalArgumentException("Missing command parameters");
      var names = new HashSet<String>();
      for (var parameter : parameters) {
         if (!names.add(parameter.name())) throw new IllegalArgumentException("Duplicate parameter: " + parameter.name());
      }
      RequiredArgumentBuilder<CommandSourceStack, ?> branch = parameters[parameters.length - 1].argument().executes(command);
      for (int index = parameters.length - 2; index >= 0; index--) {
         branch = parameters[index].argument().then(branch);
      }
      return branch;
   }

   public static Command<CommandSourceStack> execute(Function<CommandContext<CommandSourceStack>, Intent> intent) {
      Objects.requireNonNull(intent, "intent");
      return context -> {
         var player = context.getSource().getPlayerOrException();
         if (!CommandChecks.commandCanOperate(player)) return 0;
         return PlayerIntentSystem.dispatch(player, IntentSource.COMMAND, intent.apply(context)).handled() ? 1 : 0;
      };
   }
}
