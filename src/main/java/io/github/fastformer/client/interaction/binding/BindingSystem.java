package io.github.fastformer.client.interaction.binding;

import io.github.fastformer.client.interaction.binding.action.*;
import io.github.fastformer.client.interaction.binding.context.*;
import io.github.fastformer.client.interaction.binding.data.InteractionBinding;
import io.github.fastformer.client.interaction.binding.input.ButtonArbiter;
import java.util.*;
import java.util.function.Predicate;
import java.util.function.LongSupplier;
import net.minecraft.resources.ResourceLocation;

/** One input owner's data dispatch and deferred hold. Context loss never replays an input. */
public final class BindingSystem {
   public record Rule(InteractionBinding binding, BindingAction action) {}
   private record Press(Rule rule, BindingContext context, BindingCommands commands) {}
   private final ContextMatcher matcher;
   private final ButtonArbiter<Press> button = new ButtonArbiter<>();
   private final java.util.function.Supplier<List<Rule>> rules;
   private final Predicate<ResourceLocation> optionEnabled;
   private final LongSupplier revisions;
   private final Runnable definitionsChanged;
   private long revision;

   public BindingSystem(ContextMatcher matcher, java.util.function.Supplier<List<Rule>> rules, Predicate<ResourceLocation> optionEnabled) {
      this(matcher, rules, optionEnabled, () -> 0L, () -> {});
   }

   public BindingSystem(ContextMatcher matcher, java.util.function.Supplier<List<Rule>> rules,
      Predicate<ResourceLocation> optionEnabled, LongSupplier revisions, Runnable definitionsChanged) {
      this.matcher = Objects.requireNonNull(matcher);
      this.rules = Objects.requireNonNull(rules);
      this.optionEnabled = Objects.requireNonNull(optionEnabled);
      this.revisions = Objects.requireNonNull(revisions);
      this.definitionsChanged = Objects.requireNonNull(definitionsChanged);
      revision = revisions.getAsLong();
   }

   public boolean onButton(int physicalButton, String name, boolean pressed, long occurredAt,
      BindingContext context, BindingCommands commands) {
      return onButton(physicalButton, name, pressed, occurredAt, () -> context, commands);
   }

   public boolean onButton(int physicalButton, String name, boolean pressed, long occurredAt,
      java.util.function.Supplier<BindingContext> contexts, BindingCommands commands) {
      boolean owned = button.owns(physicalButton);
      if (synchronizeDefinitions() && owned) return true;
      if (button.owns(physicalButton)) {
         if (!pressed) for (var signal : button.release(physicalButton, occurredAt)) dispatch(signal, contexts.get(), commands);
         return true;
      }
      BindingContext context = contexts.get();
      if (dispatch(name, pressed ? "press" : "release", context, commands)) return true;
      if (!pressed || button.active()) return false;
      for (Rule rule : rules.get()) {
         if (!matches(rule, name, "hold", context)) continue;
         return button.press(physicalButton, occurredAt, rule.binding.input(), new Press(rule, context, commands));
      }
      return false;
   }

   public boolean onScroll(boolean up, BindingContext context, BindingCommands commands) {
      return dispatch("scroll", up ? "scroll_up" : "scroll_down", context, commands);
   }

   public boolean onKey(String key, BindingContext context, BindingCommands commands) {
      return dispatch(key, "key", context, commands);
   }

   /** Physical key and live key-mapping aliases share one priority-ordered dispatch. */
   public boolean onKey(Set<String> keys, BindingContext context, BindingCommands commands) {
      return dispatch(name -> name.equals("*") || keys.contains(name), "key", context, commands);
   }

   public boolean onSignal(String name, String gesture, BindingContext context, BindingCommands commands) {
      return dispatch(name, gesture, context, commands);
   }

   public BindingAction.Result onSignalResult(String name, String gesture, BindingContext context, BindingCommands commands) {
      return dispatchResult(name::equals, gesture, context, commands);
   }

   public void tick(long now) {
      synchronizeDefinitions();
      for (var signal : button.tick(now)) dispatch(signal, signal.snapshot().context(), signal.snapshot().commands());
   }

   /** A reload cancels the captured press before any release or threshold can execute it. */
   public boolean synchronizeDefinitions() {
      long next = revisions.getAsLong();
      if (next == revision) return false;
      revision = next;
      try { definitionsChanged.run(); }
      finally { button.cancel(); }
      return true;
   }

   public boolean active() { return button.active(); }
   public void reset() { button.cancel(); }

   private void dispatch(ButtonArbiter.Signal<Press> signal, BindingContext releaseContext, BindingCommands releaseCommands) {
      Press press = signal.snapshot();
      switch (signal.kind()) {
         case START -> press.rule.action.execute(press.context, press.commands);
         case REPLAY_PRESS -> press.commands.replayPress(signal.pressedAtNanos());
         case RELEASE -> dispatch(press.rule.binding.input().button(), "release", releaseContext, releaseCommands);
      }
   }

   private boolean dispatch(String name, String gesture, BindingContext context, BindingCommands commands) {
      return dispatch(name::equals, gesture, context, commands);
   }

   private boolean dispatch(Predicate<String> names, String gesture, BindingContext context, BindingCommands commands) {
      return dispatchResult(names, gesture, context, commands).handled();
   }

   private BindingAction.Result dispatchResult(Predicate<String> names, String gesture, BindingContext context, BindingCommands commands) {
      synchronizeDefinitions();
      for (Rule rule : rules.get()) {
         if (!names.test(rule.binding.input().button()) || !matchesContext(rule, gesture, context)) continue;
         var result = rule.action.execute(context, commands);
         if (result.handled()) return result;
      }
      return BindingAction.Result.PASS;
   }

   private boolean matches(Rule rule, String name, String gesture, BindingContext context) {
      return rule.binding.input().button().equals(name) && matchesContext(rule, gesture, context);
   }

   private boolean matchesContext(Rule rule, String gesture, BindingContext context) {
      InteractionBinding binding = rule.binding;
      return binding.enabled() && binding.input().gesture().equals(gesture)
         && binding.option().map(optionEnabled::test).orElse(true) && matcher.matches(binding.context(), context);
   }
}
