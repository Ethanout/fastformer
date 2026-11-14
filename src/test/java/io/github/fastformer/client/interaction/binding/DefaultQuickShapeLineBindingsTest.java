package io.github.fastformer.client.interaction.binding;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import io.github.fastformer.client.interaction.binding.action.*;
import io.github.fastformer.client.interaction.binding.context.*;
import io.github.fastformer.client.interaction.binding.data.*;
import io.github.fastformer.client.interaction.binding.tool.QuickShapeLineBindingInput;
import io.github.fastformer.client.interaction.binding.tool.ToolBindingCommand;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class DefaultQuickShapeLineBindingsTest {
   private final Commands commands = new Commands();

   @Test void mouseDefaultsMatchPreviousCaptureForEveryFrozenFactCombination() throws Exception {
      var system = system(defaults());
      for (int flags = 0; flags < 256; flags++) for (int action : new int[]{0, 1}) for (int button : new int[]{0, 1, 2, 4}) {
         var context = context(flags);
         commands.last = null;
         var actual = QuickShapeLineBindingInput.mouse(true, context, action, button, 10, dispatch(system));
         var expected = previousMouse(context, action, button);
         assertTrue(actual.owns());
         assertEquals(expected != null, actual.consumesVanilla(), flags + "/" + action + "/" + button);
         assertEquals(expected, commands.last, flags + "/" + action + "/" + button);
      }
   }

   @Test void wheelDefaultsMatchPreviousCandidateRoutingAndModifierConsumption() throws Exception {
      var system = system(defaults());
      for (int flags = 0; flags < 256; flags++) for (double delta : new double[]{-1, 0, 1}) {
         var context = context(flags);
         commands.last = null;
         var actual = QuickShapeLineBindingInput.scroll(true, context, delta, 20, dispatch(system));
         QuickShapeLineCommand expected = !context.scrollContext() || delta == 0 ? null
            : context.modifierYields() ? QuickShapeLineCommand.YIELD_SCROLL : context.vanillaPointer() ? null
            : delta > 0 ? QuickShapeLineCommand.SCROLL_UP : QuickShapeLineCommand.SCROLL_DOWN;
         assertTrue(actual.owns());
         assertEquals(expected != null && expected != QuickShapeLineCommand.YIELD_SCROLL, actual.consumesVanilla());
         assertEquals(expected, commands.last);
      }
   }

   @Test void explicitPassAndMissingDefinitionsNeverFallBackToLegacyLineRules() throws Exception {
      var empty = system(new BindingFile(List.of(), List.of(), List.of()));
      var context = context(255);
      assertEquals(new QuickShapeLineBindingInput.Result(true, false),
         QuickShapeLineBindingInput.mouse(true, context, 1, 1, 1, dispatch(empty)));
      assertNull(commands.last);
      var vanilla = system(UserOverrides.merge(defaults(), JsonParser.parseString("""
         {"bindings":[
          {"id":"fastformer:quick_shape/line/point","action":"fastformer:vanilla"},
          {"id":"fastformer:quick_shape/line/point_outside_vanilla","action":"fastformer:vanilla"}
         ]}
         """).getAsJsonObject()));
      assertFalse(QuickShapeLineBindingInput.mouse(true, context, 1, 1, 2, dispatch(vanilla)).consumesVanilla());
      assertNull(commands.last);
   }

   @Test void inactiveStageAndNonPointerEventsNeverRunTheLineSubtree() throws Exception {
      ToolBindingCommand unexpected = (name, gesture, context, time) -> { fail("Unowned event dispatched"); return null; };
      assertFalse(QuickShapeLineBindingInput.mouse(false, context(0), 1, 0, 1, unexpected).owns());
      assertFalse(QuickShapeLineBindingInput.scroll(false, context(0), 1, 1, unexpected).owns());
      assertTrue(QuickShapeLineBindingInput.mouse(true, context(0), 2, 0, 1, unexpected).owns());
      var system = system(defaults());
      assertFalse(system.onKey(Set.of("key.keyboard.q", "key.keyboard.enter"), context(255).freeze(), commands));
      assertFalse(system.onSignal("use", "interaction", context(255).freeze(), commands));
   }

   @Test void reloadImmediatelyUsesTheNewLinePolicyBeforeTheNextTick() throws Exception {
      var actions = BuiltinBindingActions.create();
      var rules = new java.util.concurrent.atomic.AtomicReference<>(compile(defaults(), actions));
      var revision = new java.util.concurrent.atomic.AtomicLong();
      var system = new BindingSystem(new ContextMatcher(ContextFields.defaults().freeze()), rules::get, id -> true, revision::get, () -> { });
      assertTrue(QuickShapeLineBindingInput.mouse(true, context(255), 1, 1, 1, dispatch(system)).consumesVanilla());
      rules.set(List.of());
      revision.incrementAndGet();
      commands.last = null;
      assertFalse(QuickShapeLineBindingInput.mouse(true, context(255), 1, 1, 2, dispatch(system)).consumesVanilla());
      assertNull(commands.last);
   }

   private ToolBindingCommand dispatch(BindingSystem system) {
      return (name, gesture, context, time) -> system.onSignalResult(name, gesture, context, commands);
   }

   private static QuickShapeLineCommand previousMouse(QuickShapeLineContext c, int action, int button) {
      if (action == 0) return button == 0 ? c.undoCaptured() ? QuickShapeLineCommand.END_UNDO : null
         : (button == 1 || button == 2) && c.pointerCaptured() ? QuickShapeLineCommand.RELEASE_POINTER : null;
      if (button == 0) return !c.vanillaPointer() && c.pointerChannel() ? QuickShapeLineCommand.BEGIN_UNDO : null;
      if (button == 1) return c.pointerChannel() && (c.overlaps() || !c.vanillaPointer()) ? QuickShapeLineCommand.POINT : null;
      if (button == 2) return c.pointerChannel() && c.middleEnabled() ? QuickShapeLineCommand.MIDDLE : null;
      return null;
   }

   private static QuickShapeLineContext context(int flags) {
      return new QuickShapeLineContext(bit(flags,0),bit(flags,1),bit(flags,2),bit(flags,3),
         bit(flags,4),bit(flags,5),bit(flags,6),bit(flags,7));
   }

   private static boolean bit(int flags, int bit) { return (flags & (1 << bit)) != 0; }

   private static BindingFile defaults() throws Exception {
      try (var stream = DefaultQuickShapeLineBindingsTest.class.getResourceAsStream("/assets/fastformer/fastformer/bindings/default/quick_shape_line.json")) {
         assertNotNull(stream);
         return BindingFile.parse(JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)));
      }
   }

   private static BindingSystem system(BindingFile file) {
      assertEquals(List.of(), file.diagnostics());
      var actions = BuiltinBindingActions.create();
      var rules = compile(file, actions);
      return new BindingSystem(new ContextMatcher(ContextFields.defaults().freeze()), () -> rules, id -> true);
   }

   private static List<BindingSystem.Rule> compile(BindingFile file, BindingActions actions) {
      return file.bindings().stream().sorted(InteractionBinding.ORDER)
         .map(binding -> new BindingSystem.Rule(binding, actions.resolve(binding.action()).getOrThrow())).toList();
   }

   private static final class Commands implements BindingCommands {
      private QuickShapeLineCommand last;
      public boolean quickShapeLine(QuickShapeLineCommand command) { last = command; return true; }
      public boolean start(ResourceLocation profile) { return false; }
      public boolean point() { return false; }
      public boolean middleConfirm() { return false; }
      public boolean undoPoint() { return false; }
      public boolean cancel() { return false; }
      public boolean cycleMode() { return false; }
      public void replayPress(long time) { fail("Line event must not replay idle input"); }
   }
}
