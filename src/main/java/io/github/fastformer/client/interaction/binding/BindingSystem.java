package io.github.fastformer.client.interaction.binding;

import io.github.fastformer.client.interaction.binding.action.*;
import io.github.fastformer.client.interaction.binding.context.*;
import io.github.fastformer.client.interaction.binding.data.InteractionBinding;
import io.github.fastformer.client.interaction.binding.input.ButtonArbiter;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;

/** One input owner's data dispatch and deferred hold. Context loss never replays an input. */
public final class BindingSystem {
   public record Rule(InteractionBinding binding, BindingAction action) {}
   private record Press(Rule rule, BindingContext context, BindingCommands commands) {}
   private final ContextMatcher matcher;
   private final ButtonArbiter<Press> button = new ButtonArbiter<>();
   private final java.util.function.Supplier<List<Rule>> rules;
   private final Predicate<ResourceLocation> optionEnabled;

   public BindingSystem(ContextMatcher matcher, java.util.function.Supplier<List<Rule>> rules, Predicate<ResourceLocation> optionEnabled) {
      this.matcher = Objects.requireNonNull(matcher);
      this.rules = Objects.requireNonNull(rules);
      this.optionEnabled = Objects.requireNonNull(optionEnabled);
   }

   public boolean onButton(int physicalButton, String name, boolean pressed, long occurredAt,
      BindingContext context, BindingCommands commands) {
      return onButton(physicalButton, name, pressed, occurredAt, () -> context, commands);
   }

   public boolean onButton(int physicalButton, String name, boolean pressed, long occurredAt,
      java.util.function.Supplier<BindingContext> contexts, BindingCommands commands) {
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

   public void tick(long now) {
      for (var signal : button.tick(now)) dispatch(signal, signal.snapshot().context(), signal.snapshot().commands());
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
      for (Rule rule : rules.get()) {
         if (matches(rule, name, gesture, context) && rule.action.execute(context, commands) == BindingAction.Result.HANDLED) return true;
      }
      return false;
   }

   private boolean matches(Rule rule, String name, String gesture, BindingContext context) {
      InteractionBinding binding = rule.binding;
      return binding.enabled() && binding.input().button().equals(name) && binding.input().gesture().equals(gesture)
         && binding.option().map(optionEnabled::test).orElse(true) && matcher.matches(binding.context(), context);
   }
}
