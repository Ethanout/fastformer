package io.github.fastformer.fastplace.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.ai.attributes.Attributes;

final class ReachCommand {
   private ReachCommand() { }

   static LiteralArgumentBuilder<CommandSourceStack> create() {
      return Commands.literal("reach")
         .executes(context -> show(context.getSource()))
         .then(Commands.argument("distance", IntegerArgumentType.integer()).executes(context -> {
            var player = context.getSource().getPlayerOrException();
            player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE).setBaseValue(IntegerArgumentType.getInteger(context, "distance"));
            return show(context.getSource());
         }));
   }

   private static int show(CommandSourceStack source) throws CommandSyntaxException {
      var attribute = source.getPlayerOrException().getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
      double value = attribute.getValue(), base = attribute.getBaseValue();
      source.sendSuccess(() -> Component.translatable("fastformer.message.block_reach", value, base), false);
      return 1;
   }
}
