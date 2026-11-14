package io.github.fastformer.client.interaction.binding;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import io.github.fastformer.client.interaction.binding.action.*;
import io.github.fastformer.client.interaction.binding.context.*;
import io.github.fastformer.client.interaction.binding.data.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class DefaultWorkspaceBindingsTest {
   private final Commands commands = new Commands();

   @Test void actualDefaultsMatchPreviousAdmissionForEveryWorkspaceFactCombination() throws Exception {
      var system = system(defaults());
      for (String route : List.of("operation", "vanilla", "building", "geometry", "blocked"))
         for (int flags = 0; flags < 128; flags++) for (int action = 0; action <= 2; action++)
            for (String key : List.of("a", "c", "v", "d", "r", "f", "x")) {
               boolean active = bit(flags, 0), paste = bit(flags, 1), editing = bit(flags, 2), pending = bit(flags, 3);
               boolean control = bit(flags, 4), screenClosed = bit(flags, 5), player = bit(flags, 6);
               var context = new WorkspaceKeyContext("fastformer:selection", route, paste, active, editing, pending,
                  screenClosed, player, action, control, false, false, 0).freeze();
               commands.last = null;
               var result = system.onKeyResult(Set.of("key.keyboard." + key), context, commands);
               String expected = previous(key, action, route, active, paste, editing, pending, control, screenClosed, player);
               assertEquals(!expected.equals("pass"), result.consumesVanilla(), route + "/" + flags + "/" + action + "/" + key);
               assertEquals(expected.equals("block") || expected.equals("pass") ? null : WorkspaceCommand.valueOf(expected), commands.last);
            }
   }

   @Test void disablingAndRebindingRemoveTheOriginalPhysicalShortcut() throws Exception {
      var custom = UserOverrides.merge(defaults(), JsonParser.parseString("""
         {"bindings":[
          {"id":"fastformer:workspace/copy","enabled":false},
          {"id":"fastformer:workspace/select_all","input":{"button":"key.keyboard.w","gesture":"key"}}
         ]}
         """).getAsJsonObject());
      var system = system(custom);
      assertEquals(BindingAction.Result.PASS, system.onKeyResult(Set.of("key.keyboard.c"), active(), commands));
      assertEquals(BindingAction.Result.PASS, system.onKeyResult(Set.of("key.keyboard.a"), active(), commands));
      assertTrue(system.onKeyResult(Set.of("key.keyboard.w"), active(), commands).consumesVanilla());
      assertEquals(WorkspaceCommand.TOGGLE_SELECT_ALL, commands.last);
   }

   @Test void vanillaOverrideTerminatesMatchingWithoutClaimingTheMovementMapping() throws Exception {
      var system = system(UserOverrides.merge(defaults(), JsonParser.parseString("""
         {"bindings":[{"id":"fastformer:workspace/select_all","action":"fastformer:vanilla"}]}
         """).getAsJsonObject()));
      assertEquals(BindingAction.Result.HANDLED_PASS_VANILLA, system.onKeyResult(Set.of("key.keyboard.a"), active(), commands));
      assertNull(commands.last);
   }

   @Test void modifiersAndScreenFactsAreFrozenAndReleaseNeverRunsAWorkspaceCommand() throws Exception {
      var system = system(defaults());
      for (int action : new int[]{0, 2}) {
         var context = new WorkspaceKeyContext("fastformer:selection", "operation", true, true, false, false, true, true,
            action, true, true, true, 0).freeze();
         assertFalse(system.onKey(Set.of("key.keyboard.a"), context, commands));
      }
      var withAltShift = new WorkspaceKeyContext("fastformer:selection", "operation", true, true, false, false, true, true,
         1, true, true, true, 0).freeze();
      assertTrue(system.onKey(Set.of("key.keyboard.a"), withAltShift, commands));
      assertEquals(WorkspaceCommand.TOGGLE_SELECT_ALL, commands.last);
   }

   private static String previous(String key, int action, String route, boolean active, boolean paste, boolean editing,
      boolean pending, boolean control, boolean screen, boolean player) {
      if (action != 1 || !control || !screen || !player || key.equals("x")) return "pass";
      if (key.equals("v")) return paste ? "PASTE" : "pass";
      boolean transform = List.of("d", "r", "f").contains(key);
      if (!route.equals("operation")) return route.equals("blocked") && active && transform ? "block" : "pass";
      if (transform) {
         if (pending) return "REPORT_SUBMISSION_PENDING";
         if (editing || key.equals("d") && !paste) return "block";
      }
      if (!active) return "pass";
      return switch (key) {
         case "a" -> "TOGGLE_SELECT_ALL";
         case "c" -> "COPY";
         case "d" -> "DUPLICATE";
         case "r" -> "ROTATE_SELECTION";
         case "f" -> "FLIP_SELECTION";
         default -> throw new AssertionError(key);
      };
   }

   private static BindingContext active() {
      return new WorkspaceKeyContext("fastformer:selection", "operation", true, true, false, false, true, true,
         1, true, false, false, 0).freeze();
   }

   private static BindingFile defaults() throws Exception {
      try (var stream = DefaultWorkspaceBindingsTest.class.getResourceAsStream("/assets/fastformer/fastformer/bindings/default/workspace.json")) {
         assertNotNull(stream);
         return BindingFile.parse(JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)));
      }
   }

   private static BindingSystem system(BindingFile file) {
      assertEquals(List.of(), file.diagnostics());
      var actions = BuiltinBindingActions.create();
      var rules = file.bindings().stream().sorted(InteractionBinding.ORDER)
         .map(binding -> new BindingSystem.Rule(binding, actions.resolve(binding.action()).getOrThrow())).toList();
      return new BindingSystem(new ContextMatcher(ContextFields.defaults().freeze()), () -> rules, id -> true);
   }

   private static boolean bit(int flags, int index) { return (flags & (1 << index)) != 0; }

   private static final class Commands implements BindingCommands {
      private WorkspaceCommand last;
      public boolean start(ResourceLocation profile) { return false; }
      public boolean point() { return false; }
      public boolean middleConfirm() { return false; }
      public boolean undoPoint() { return false; }
      public boolean cancel() { return false; }
      public boolean cycleMode() { return false; }
      public boolean workspace(WorkspaceCommand command) { last = command; return true; }
      public void replayPress(long time) { fail("Workspace key must not replay pointer input"); }
   }
}
