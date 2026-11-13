package io.github.fastformer.server.command.syntax;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.fastformer.core.command.Intent;
import io.github.fastformer.core.command.IntentSource;
import io.github.fastformer.fastplace.text.FastPlaceMessages;
import io.github.fastformer.server.command.CommandChecks;
import io.github.fastformer.server.intent.PlayerIntentSystem;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/** Named choices preserve existing usage feedback and admission order. */
public record CommandChoices<T>(Map<String, T> values, String usage) {
   public CommandChoices {
      values = Map.copyOf(values);
      if (values.isEmpty()) throw new IllegalArgumentException("Empty command choices");
      Objects.requireNonNull(usage, "usage");
   }

   public LiteralArgumentBuilder<CommandSourceStack> intent(String literal, Function<T, Intent> intent) {
      return build(literal, intent, true);
   }

   public LiteralArgumentBuilder<CommandSourceStack> validateFirst(String literal, Function<T, Intent> intent) {
      return build(literal, intent, false);
   }

   private LiteralArgumentBuilder<CommandSourceStack> build(String literal, Function<T, Intent> intent, boolean admitFirst) {
      Objects.requireNonNull(intent, "intent");
      return Commands.literal(literal).then(Commands.argument("mode", StringArgumentType.word()).executes(context -> {
         var player = context.getSource().getPlayerOrException();
         if (admitFirst && !CommandChecks.commandCanOperate(player)) return 0;
         T value = values.get(StringArgumentType.getString(context, "mode"));
         if (value == null) {
            FastPlaceMessages.chat(player, FastPlaceMessages.text(usage));
            return 0;
         }
         if (!admitFirst && !CommandChecks.commandCanOperate(player)) return 0;
         return PlayerIntentSystem.dispatch(player, IntentSource.COMMAND, intent.apply(value)).handled() ? 1 : 0;
      }));
   }
}
