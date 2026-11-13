package io.github.fastformer.server.command.syntax;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.brigadier.CommandDispatcher;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;

class IntentCommandsTest {
   @Test
   void typedParametersPreserveOrderAndBounds() throws Exception {
      var dispatcher = new CommandDispatcher<CommandSourceStack>();
      var count = CommandParameter.integer("count", 1, 8);
      var angle = CommandParameter.number("angle", -90, 90);
      dispatcher.register(IntentCommands.routeCommand("edit", context -> {
         assertEquals(3, count.read(context));
         assertEquals(-12.5, angle.read(context));
         return 7;
      }, count, angle));
      assertEquals(7, dispatcher.execute("edit 3 -12.5", (CommandSourceStack) null));
      var countType = (com.mojang.brigadier.arguments.IntegerArgumentType) count.type();
      assertEquals(1, countType.getMinimum());
      assertEquals(8, countType.getMaximum());
      var angleType = (com.mojang.brigadier.arguments.DoubleArgumentType) angle.type();
      assertEquals(-90, angleType.getMinimum());
      assertEquals(90, angleType.getMaximum());
   }

   @Test
   void argumentlessRouteExecutesItsOwnCommand() throws Exception {
      var dispatcher = new CommandDispatcher<CommandSourceStack>();
      dispatcher.register(IntentCommands.routeCommand("apply", context -> 11));
      assertEquals(11, dispatcher.execute("apply", (CommandSourceStack) null));
   }

   @Test
   void malformedSchemasFailBeforeRegistration() {
      var count = CommandParameter.integer("count", 1, 8);
      assertThrows(IllegalArgumentException.class, () -> IntentCommands.arguments(context -> 1));
      assertThrows(IllegalArgumentException.class, () -> IntentCommands.arguments(context -> 1, count, count));
      assertThrows(IllegalArgumentException.class, () -> CommandParameter.number(" "));
   }

   @Test
   void choicesFreezeTheAcceptedVocabulary() {
      var values = new HashMap<>(Map.of("axis", 1));
      var choices = new CommandChoices<>(values, "usage");
      values.put("new_mode", 2);
      assertEquals(Map.of("axis", 1), choices.values());
      assertThrows(UnsupportedOperationException.class, () -> choices.values().put("other", 3));
      assertThrows(IllegalArgumentException.class, () -> new CommandChoices<>(Map.of(), "usage"));
   }
}
