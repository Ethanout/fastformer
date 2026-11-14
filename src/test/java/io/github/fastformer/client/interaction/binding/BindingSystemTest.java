package io.github.fastformer.client.interaction.binding;

import com.google.gson.*;
import io.github.fastformer.client.interaction.binding.action.*;
import io.github.fastformer.client.interaction.binding.context.*;
import io.github.fastformer.client.interaction.binding.data.*;
import java.util.*;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BindingSystemTest {
   private static final BindingContext IDLE = context("fastformer:idle");
   private static final BindingContext ACTIVE = context("fastformer:quick_shape");
   private static final long PRESS = 1_000_000_000L;
   private final List<String> calls = new ArrayList<>();
   private BindingContext current = IDLE;
   private final BindingCommands commands = new BindingCommands() {
      public boolean start(ResourceLocation profile) { calls.add("start"); current = ACTIVE; return true; }
      public boolean point() { calls.add("point"); return true; }
      public boolean middleConfirm() { calls.add("confirm"); return true; }
      public boolean undoPoint() { return false; }
      public boolean cancel() { calls.add("cancel"); return true; }
      public boolean cycleMode() { return false; }
      public void replayPress(long occurredAtNanos) { calls.add("replay:" + occurredAtNanos); }
   };

   @Test void shortPressReplaysOnceWithOriginalTime() {
      BindingSystem system = system(true);
      assertTrue(system.onButton(1, "right", true, PRESS, IDLE, commands));
      assertTrue(system.onButton(1, "right", false, PRESS + 80_000_000L, IDLE, commands));
      system.tick(PRESS + 500_000_000L);
      assertFalse(system.onButton(1, "right", false, PRESS + 500_000_001L, IDLE, commands));
      assertEquals(List.of("replay:" + PRESS), calls);
   }

   @Test void releaseAtThresholdWithoutTickRunsStartThenProfileRelease() {
      BindingSystem system = system(true);
      system.onButton(1, "right", true, PRESS, IDLE, commands);
      assertTrue(system.onButton(1, "right", false, PRESS + 200_000_000L, () -> current, commands));
      assertEquals(List.of("start", "confirm"), calls);
   }

   @Test void contextLossCancelsWithoutReplayOrWrite() {
      BindingSystem system = system(true);
      system.onButton(1, "right", true, PRESS, IDLE, commands);
      system.reset();
      system.tick(PRESS + 500_000_000L);
      system.onButton(1, "right", false, PRESS + 600_000_000L, IDLE, commands);
      assertTrue(calls.isEmpty());
   }

   @Test void disabledOptionAndUnmatchedContextDoNotIntercept() {
      assertFalse(system(false).onButton(1, "right", true, PRESS, IDLE, commands));
      assertFalse(system(true).onButton(1, "right", true, PRESS, context("fastformer:selection"), commands));
      assertTrue(calls.isEmpty());
   }

   @Test void reloadBeforePhysicalReleaseCancelsCapturedPressWithoutReplayingOrUsingNewReleaseRules() {
      var revision = new java.util.concurrent.atomic.AtomicLong();
      var cancellations = new java.util.concurrent.atomic.AtomicInteger();
      var file = BindingFile.parse(JsonParser.parseString("""
         {"bindings":[
          {"id":"fastformer:pending","context":{"session":"fastformer:idle"},
           "input":{"button":"right","gesture":"hold","hold_start":"deferred"},
           "action":"fastformer:quick_shape/start"},
          {"id":"fastformer:new_release","context":{},
           "input":{"button":"right","gesture":"release"},
           "action":"fastformer:quick_shape/middle_confirm"}]}
         """));
      var actions = BuiltinBindingActions.create();
      var rules = file.bindings().stream().map(binding -> new BindingSystem.Rule(binding, actions.resolve(binding.action()).getOrThrow())).toList();
      var published = new java.util.concurrent.atomic.AtomicReference<>(rules);
      var reloading = new BindingSystem(new ContextMatcher(ContextFields.defaults().freeze()), published::get,
         id -> true, revision::get, cancellations::incrementAndGet);

      assertTrue(reloading.onButton(1, "right", true, PRESS, IDLE, commands));
      published.set(rules.subList(1, 2));
      revision.incrementAndGet();
      assertTrue(reloading.onButton(1, "right", false, PRESS + 80_000_000L, IDLE, commands));
      reloading.tick(PRESS + 400_000_000L);
      assertEquals(1, cancellations.get());
      assertTrue(calls.isEmpty());
      assertFalse(reloading.active());

      assertTrue(reloading.onButton(1, "right", false, PRESS + 500_000_000L, IDLE, commands));
      assertEquals(List.of("confirm"), calls);
   }

   @Test void reloadBeforeHoldThresholdDiscardsStartAndNotifiesOwnerBeforeClearingCapture() {
      var revision = new java.util.concurrent.atomic.AtomicLong();
      var callbacks = new ArrayList<Boolean>();
      var reference = new java.util.concurrent.atomic.AtomicReference<BindingSystem>();
      var file = BindingFile.parse(JsonParser.parseString("""
         {"bindings":[{"id":"fastformer:pending","context":{},
          "input":{"button":"right","gesture":"hold","hold_start":"deferred"},
          "action":"fastformer:quick_shape/start"}]}
         """));
      var actions = BuiltinBindingActions.create();
      var rules = file.bindings().stream().map(binding -> new BindingSystem.Rule(binding, actions.resolve(binding.action()).getOrThrow())).toList();
      var reloading = new BindingSystem(new ContextMatcher(ContextFields.defaults().freeze()), () -> rules,
         id -> true, revision::get, () -> callbacks.add(reference.get().active()));
      reference.set(reloading);
      assertTrue(reloading.onButton(1, "right", true, PRESS, IDLE, commands));
      revision.incrementAndGet();
      reloading.tick(PRESS + 300_000_000L);
      assertEquals(List.of(true), callbacks);
      assertFalse(reloading.active());
      assertTrue(calls.isEmpty());
   }

   @Test void fillSignalUsesReloadedPolicyImmediatelyWithoutWaitingForTick() {
      var revision = new java.util.concurrent.atomic.AtomicLong();
      var rules = new java.util.concurrent.atomic.AtomicReference<List<BindingSystem.Rule>>();
      var actions = BuiltinBindingActions.create();
      var block = BindingFile.parse(JsonParser.parseString("""
         {"bindings":[{"id":"fastformer:fill_wheel","context":{"session":"fastformer:smart_fill"},
          "input":{"button":"scroll","gesture":"scroll_up"},
          "action":"fastformer:block"}]}
         """));
      var binding = block.bindings().getFirst();
      rules.set(List.of(new BindingSystem.Rule(binding, actions.resolve(binding.action()).getOrThrow())));
      var system = new BindingSystem(new ContextMatcher(ContextFields.defaults().freeze()), rules::get, id -> true,
         revision::get, () -> {});
      var fill = context("fastformer:smart_fill");
      assertTrue(system.onSignalResult("scroll", "scroll_up", fill, commands).consumesVanilla());
      var allow = UserOverrides.merge(block, JsonParser.parseString("""
         {"bindings":[{"id":"fastformer:fill_wheel","action":"fastformer:vanilla"}]}
         """).getAsJsonObject()).bindings().getFirst();
      rules.set(List.of(new BindingSystem.Rule(allow, actions.resolve(allow.action()).getOrThrow())));
      revision.incrementAndGet();
      assertEquals(BindingAction.Result.HANDLED_PASS_VANILLA, system.onSignalResult("scroll", "scroll_up", fill, commands));
      assertTrue(calls.isEmpty());
   }

   private BindingSystem system(boolean enabled) {
      BindingFile file = BindingFile.parse(JsonParser.parseString("""
         {"option":"fastformer:hold_line","bindings":[
          {"id":"fastformer:start","context":{"session":"fastformer:idle"},
           "input":{"button":"right","gesture":"hold","hold_start":"deferred"},
           "action":{"id":"fastformer:quick_shape/start","profile":"fastformer:hold_line"}},
          {"id":"fastformer:release","context":{"session":"fastformer:quick_shape"},
           "input":{"button":"right","gesture":"release"},"action":"fastformer:quick_shape/middle_confirm"}]}
         """));
      BindingActions actions = BuiltinBindingActions.create();
      var rules = file.bindings().stream().map(binding -> new BindingSystem.Rule(binding, actions.resolve(binding.action()).getOrThrow())).toList();
      return new BindingSystem(new ContextMatcher(ContextFields.defaults().freeze()), () -> rules, id -> enabled);
   }

   private static BindingContext context(String session) { return new BindingContext(Map.of("session", new JsonPrimitive(session))); }
}
