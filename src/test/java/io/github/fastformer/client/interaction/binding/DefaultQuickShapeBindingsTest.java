package io.github.fastformer.client.interaction.binding;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import io.github.fastformer.client.interaction.binding.action.*;
import io.github.fastformer.client.interaction.binding.context.*;
import io.github.fastformer.client.interaction.binding.data.*;
import io.github.fastformer.client.interaction.binding.tool.QuickShapeBindingInput;
import io.github.fastformer.client.interaction.binding.tool.ToolBindingCommand;
import io.github.fastformer.client.interaction.binding.context.QuickShapeBindingContext.Fact;
import io.github.fastformer.common.interaction.binding.SessionProfiles;
import io.github.fastformer.common.quickshape.mode.QuickShapeStage;
import java.util.EnumSet;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class DefaultQuickShapeBindingsTest {
   private final Commands commands = new Commands();

   @Test void frozenContextKeepsStageModeProfileAndDoesNotShareItsFactSet() {
      var facts = EnumSet.of(Fact.MIDDLE_IGNORED);
      var mode = ResourceLocation.fromNamespaceAndPath("fastformer", "polygon");
      var profile = ResourceLocation.fromNamespaceAndPath("example", "face_only");
      var context = new QuickShapeBindingContext(QuickShapeStage.FACE, mode, profile, facts);
      facts.clear();
      var frozen = context.freeze();
      assertEquals("face", frozen.field("stage").orElseThrow().getAsString());
      assertEquals(mode.toString(), frozen.field("mode").orElseThrow().getAsString());
      assertEquals(profile.toString(), frozen.field("profile").orElseThrow().getAsString());
      assertTrue(frozen.field("middle_ignored").orElseThrow().getAsBoolean());
      assertThrows(UnsupportedOperationException.class, () -> context.facts().clear());
   }

   @Test void aModeAndProfileBranchCanReplaceTheDefaultMiddlePolicy() {
      var file = BindingFile.parse(JsonParser.parseString("""
         {"tree":{"session:fastformer:quick_shape":{"stage:face":{"mode:fastformer:polygon":{
          "profile:example:face_only":{"middle/press":{"action":"fastformer:quick_shape/input/middle"}}
         }}}}}
         """));
      var system = system(file);
      var context = new QuickShapeBindingContext(QuickShapeStage.FACE,
         ResourceLocation.fromNamespaceAndPath("fastformer", "polygon"),
         ResourceLocation.fromNamespaceAndPath("example", "face_only"), EnumSet.of(Fact.MIDDLE_IGNORED));
      assertTrue(QuickShapeBindingInput.mouse(true, context, 1, 2, 1, dispatch(system)).consumesVanilla());
      assertEquals(QuickShapeBindingCommand.MIDDLE, commands.last);
      commands.last = null;
      var otherProfile = new QuickShapeBindingContext(context.stage(), context.mode(), SessionProfiles.NONE, context.facts());
      assertFalse(QuickShapeBindingInput.mouse(true, otherProfile, 1, 2, 2, dispatch(system)).consumesVanilla());
      assertNull(commands.last);
   }

   @Test void mouseDefaultsMatchPreviousCaptureForEveryFrozenFactCombination() throws Exception {
      var system = system(defaults());
      for (QuickShapeStage stage : QuickShapeStage.values()) for (int flags = 0; flags < 512; flags++)
         for (int action : new int[]{0, 1}) for (int button : new int[]{0, 1, 2, 4}) {
         var context = context(flags, stage);
         commands.last = null;
         var actual = QuickShapeBindingInput.mouse(true, context, action, button, 10, dispatch(system));
         var expected = previousMouse(context, action, button);
         assertTrue(actual.owns());
         assertEquals(expected != null, actual.consumesVanilla(), flags + "/" + action + "/" + button);
         assertEquals(expected, commands.last, flags + "/" + action + "/" + button);
      }
   }

   @Test void wheelDefaultsMatchPreviousCandidateRoutingAndModifierConsumption() throws Exception {
      var system = system(defaults());
      for (QuickShapeStage stage : QuickShapeStage.values()) for (int flags = 0; flags < 512; flags++)
         for (double delta : new double[]{-1, 0, 1}) {
         var context = context(flags, stage);
         commands.last = null;
         var actual = QuickShapeBindingInput.scroll(true, context, delta, 20, dispatch(system));
         QuickShapeBindingCommand expected = !context.scrollContext() || delta == 0 ? null
            : context.modifierYields() ? QuickShapeBindingCommand.YIELD_SCROLL : context.vanillaPointer() ? null
            : delta > 0 ? QuickShapeBindingCommand.SCROLL_UP : QuickShapeBindingCommand.SCROLL_DOWN;
         assertTrue(actual.owns());
         assertEquals(expected != null && expected != QuickShapeBindingCommand.YIELD_SCROLL, actual.consumesVanilla());
         assertEquals(expected, commands.last);
      }
   }

   @Test void explicitPassAndMissingDefinitionsNeverFallBackToLegacyLineRules() throws Exception {
      var empty = system(new BindingFile(List.of(), List.of(), List.of()));
      var context = context(255);
      assertEquals(new QuickShapeBindingInput.Result(true, false),
         QuickShapeBindingInput.mouse(true, context, 1, 1, 1, dispatch(empty)));
      assertNull(commands.last);
      var vanilla = system(UserOverrides.merge(defaults(), JsonParser.parseString("""
         {"bindings":[
          {"id":"fastformer:quick_shape/line/point","action":"fastformer:vanilla"},
          {"id":"fastformer:quick_shape/line/point_outside_vanilla","action":"fastformer:vanilla"}
         ]}
         """).getAsJsonObject()));
      assertFalse(QuickShapeBindingInput.mouse(true, context, 1, 1, 2, dispatch(vanilla)).consumesVanilla());
      assertNull(commands.last);
   }

   @Test void inactiveStageAndNonPointerEventsNeverRunTheLineSubtree() throws Exception {
      ToolBindingCommand unexpected = (name, gesture, context, time) -> { fail("Unowned event dispatched"); return null; };
      assertFalse(QuickShapeBindingInput.mouse(false, context(0), 1, 0, 1, unexpected).owns());
      assertFalse(QuickShapeBindingInput.scroll(false, context(0), 1, 1, unexpected).owns());
      assertTrue(QuickShapeBindingInput.mouse(true, context(0), 2, 0, 1, unexpected).owns());
      var system = system(defaults());
      assertFalse(system.onKey(Set.of("key.keyboard.q", "key.keyboard.enter"), context(255).freeze(), commands));
      assertFalse(system.onSignal("use", "interaction", context(255).freeze(), commands));
   }

   @Test void reloadImmediatelyUsesTheNewLinePolicyBeforeTheNextTick() throws Exception {
      var actions = BuiltinBindingActions.create();
      var rules = new java.util.concurrent.atomic.AtomicReference<>(compile(defaults(), actions));
      var revision = new java.util.concurrent.atomic.AtomicLong();
      var system = new BindingSystem(new ContextMatcher(ContextFields.defaults().freeze()), rules::get, id -> true, revision::get, () -> { });
      assertTrue(QuickShapeBindingInput.mouse(true, context(255), 1, 1, 1, dispatch(system)).consumesVanilla());
      rules.set(List.of());
      revision.incrementAndGet();
      commands.last = null;
      assertFalse(QuickShapeBindingInput.mouse(true, context(255), 1, 1, 2, dispatch(system)).consumesVanilla());
      assertNull(commands.last);
   }

   private ToolBindingCommand dispatch(BindingSystem system) {
      return (name, gesture, context, time) -> system.onSignalResult(name, gesture, context, commands);
   }

   private static QuickShapeBindingCommand previousMouse(QuickShapeBindingContext c, int action, int button) {
      if (action == 0) return button == 0 ? c.undoCaptured() ? QuickShapeBindingCommand.END_UNDO : null
         : (button == 1 || button == 2) && c.pointerCaptured() ? QuickShapeBindingCommand.RELEASE_POINTER : null;
      if (button == 0) return !c.vanillaPointer() && c.pointerChannel() ? QuickShapeBindingCommand.BEGIN_UNDO : null;
      if (button == 1) return c.pointerChannel() && (c.overlaps() || !c.vanillaPointer()) ? QuickShapeBindingCommand.POINT : null;
      if (button == 2) return c.middleIgnored() ? QuickShapeBindingCommand.CAPTURE_POINTER
         : c.pointerChannel() && c.middleEnabled() ? QuickShapeBindingCommand.MIDDLE : null;
      return null;
   }

   private static QuickShapeBindingContext context(int flags) {
      return context(flags, QuickShapeStage.LINE);
   }

   private static QuickShapeBindingContext context(int flags, QuickShapeStage stage) {
      Fact[] order = {Fact.VANILLA_POINTER, Fact.CANDIDATE_OVERLAPS, Fact.POINTER_CHANNEL, Fact.MIDDLE_ENABLED,
         Fact.POINTER_CAPTURED, Fact.UNDO_CAPTURED, Fact.SCROLL_CONTEXT, Fact.MODIFIER_YIELDS, Fact.MIDDLE_IGNORED};
      var facts = EnumSet.noneOf(Fact.class);
      for (int index = 0; index < order.length; index++) if (bit(flags, index)) facts.add(order[index]);
      return new QuickShapeBindingContext(stage, ResourceLocation.fromNamespaceAndPath("fastformer", "axis"), SessionProfiles.NONE, facts);
   }

   private static boolean bit(int flags, int bit) { return (flags & (1 << bit)) != 0; }

   private static BindingFile defaults() throws Exception {
      try (var stream = DefaultQuickShapeBindingsTest.class.getResourceAsStream("/assets/fastformer/fastformer/bindings/default/quick_shape.json")) {
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
      private QuickShapeBindingCommand last;
      public boolean quickShapeBinding(QuickShapeBindingCommand command) { last = command; return true; }
      public boolean start(ResourceLocation profile) { return false; }
      public boolean point() { return false; }
      public boolean middleConfirm() { return false; }
      public boolean undoPoint() { return false; }
      public boolean cancel() { return false; }
      public boolean cycleMode() { return false; }
      public void replayPress(long time) { fail("Line event must not replay idle input"); }
   }
}
