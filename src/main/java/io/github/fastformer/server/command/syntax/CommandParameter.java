package io.github.fastformer.server.command.syntax;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.Objects;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/** One typed parameter owns its Brigadier declaration and value lookup. */
public record CommandParameter<T>(String name, ArgumentType<T> type, Class<T> valueType) {
   public CommandParameter {
      if (Objects.requireNonNull(name, "name").isBlank()) throw new IllegalArgumentException("Blank parameter name");
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(valueType, "valueType");
   }

   public static CommandParameter<Double> number(String name) {
      return new CommandParameter<>(name, DoubleArgumentType.doubleArg(), Double.class);
   }

   public static CommandParameter<Double> number(String name, double min, double max) {
      return new CommandParameter<>(name, DoubleArgumentType.doubleArg(min, max), Double.class);
   }

   public static CommandParameter<Integer> integer(String name, int min, int max) {
      return new CommandParameter<>(name, IntegerArgumentType.integer(min, max), Integer.class);
   }

   public RequiredArgumentBuilder<CommandSourceStack, T> argument() {
      return Commands.argument(name, type);
   }

   public T read(CommandContext<CommandSourceStack> context) {
      return context.getArgument(name, valueType);
   }
}
