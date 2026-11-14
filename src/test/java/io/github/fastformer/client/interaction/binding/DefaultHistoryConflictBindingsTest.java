package io.github.fastformer.client.interaction.binding;

import static org.junit.jupiter.api.Assertions.*;
import static io.github.fastformer.testkit.BindingFixtures.*;

import com.google.gson.JsonParser;
import io.github.fastformer.client.interaction.binding.action.BindingAction;
import io.github.fastformer.client.interaction.binding.action.HistoryConflictCommand;
import io.github.fastformer.client.interaction.binding.context.BindingContext;
import io.github.fastformer.client.interaction.binding.context.key.HistoryKeyContext;
import io.github.fastformer.client.interaction.binding.data.UserOverrides;
import io.github.fastformer.testkit.RecordingBindingCommands;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DefaultHistoryConflictBindingsTest {
   private final RecordingBindingCommands commands = new RecordingBindingCommands();

   @Test void actualDefaultsPreserveEveryPromptAndModifierCombination() throws Exception {
      var system = system(defaults("history-conflict"));
      for (int flags = 0; flags < 64; flags++) for (int action = 0; action < 3; action++)
         for (String key : List.of("enter", "keypad.enter", "q", "z", "y", "w")) {
            commands.clear();
            var result = system.onKeyResult(Set.of("key.keyboard." + key), context(flags, action), commands);
            boolean ready = bit(flags, 0) && bit(flags, 2) && bit(flags, 3) && action == 1;
            HistoryConflictCommand expected = !ready ? null : switch (key) {
               case "enter", "keypad.enter" -> bit(flags, 1) ? HistoryConflictCommand.SKIP : HistoryConflictCommand.OVERWRITE;
               case "q" -> HistoryConflictCommand.CANCEL;
               default -> null;
            };
            boolean heldHistoryKey = ready && bit(flags, 1) && (key.equals("z") || key.equals("y"));
            String label = flags + "/" + action + "/" + key;
            assertEquals(expected != null || heldHistoryKey, result.consumesVanilla(), label);
            assertEquals(expected, commands.historyConflict(), label);
         }
   }

   @Test void rebindingAndDisablingRemoveTheOriginalPromptAnswerKeys() throws Exception {
      var system = system(UserOverrides.merge(defaults("history-conflict"), JsonParser.parseString("""
         {"bindings":[
          {"id":"fastformer:history_conflict/cancel","input":{"button":"key.keyboard.w","gesture":"key"}},
          {"id":"fastformer:history_conflict/overwrite","enabled":false}
         ]}
         """).getAsJsonObject()));
      var plain = context(1 | 4 | 8, 1);
      assertEquals(BindingAction.Result.PASS, system.onKeyResult(Set.of("key.keyboard.q"), plain, commands));
      assertEquals(BindingAction.Result.PASS, system.onKeyResult(Set.of("key.keyboard.enter"), plain, commands));
      assertTrue(system.onKeyResult(Set.of("key.keyboard.w"), plain, commands).consumesVanilla());
      assertEquals(HistoryConflictCommand.CANCEL, commands.historyConflict());
      assertTrue(system.onKeyResult(Set.of("key.keyboard.enter"), context(1 | 2 | 4 | 8, 1), commands).consumesVanilla());
      assertEquals(HistoryConflictCommand.SKIP, commands.historyConflict());
   }

   @Test void vanillaOverrideTerminatesPromptHandlingWithoutSendingAnAnswer() throws Exception {
      var system = system(UserOverrides.merge(defaults("history-conflict"), JsonParser.parseString("""
         {"bindings":[{"id":"fastformer:history_conflict/cancel","action":"fastformer:vanilla"}]}
         """).getAsJsonObject()));
      assertEquals(BindingAction.Result.HANDLED_PASS_VANILLA, system.onKeyResult(Set.of("key.keyboard.q"), context(1 | 4 | 8, 1), commands));
      assertNull(commands.historyConflict());
   }

   @Test void promptPhaseCannotInvokeOrdinaryWorkspaceHistoryOrConfirmation() throws Exception {
      var system = system(defaults("history-conflict", "workspace-history", "confirmation", "tools"));
      for (String key : List.of("z", "y")) {
         commands.clear();
         assertTrue(system.onKeyResult(Set.of("key.keyboard." + key), context(1 | 2 | 4 | 8, 1), commands).consumesVanilla());
         assertNull(commands.workspace());
         assertNull(commands.confirmation());
         assertNull(commands.historyConflict());
      }
      assertTrue(system.onKeyResult(Set.of("key.keyboard.enter"), context(1 | 4 | 8, 1), commands).consumesVanilla());
      assertEquals(HistoryConflictCommand.OVERWRITE, commands.historyConflict());
      assertNull(commands.confirmation());
   }

   private static BindingContext context(int flags, int action) {
      return new HistoryKeyContext("fastformer:idle", bit(flags, 2), bit(flags, 3), bit(flags, 0), action,
         bit(flags, 1), bit(flags, 4), bit(flags, 5)).freeze();
   }

   private static boolean bit(int flags, int index) { return (flags & (1 << index)) != 0; }
}
