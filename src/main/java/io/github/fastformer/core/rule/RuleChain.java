package io.github.fastformer.core.rule;

import io.github.fastformer.core.registry.Registry;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** A frozen, ordered rule set. Evaluation does not reinterpret the returned value. */
public final class RuleChain<C, R> {
   private final List<Rule<C, R>> rules;

   private RuleChain(List<Rule<C, R>> rules) { this.rules = List.copyOf(rules); }

   public static <C, R> Builder<C, R> builder(String name) { return new Builder<>(name); }

   public Optional<R> apply(C context) {
      Objects.requireNonNull(context, "context");
      for (Rule<C, R> rule : rules) {
         Optional<R> result = Objects.requireNonNull(rule.apply(context), "rule result");
         if (result.isPresent()) return result;
      }
      return Optional.empty();
   }

   /** Higher priorities run first. Equal priorities retain registration order. */
   public static final class Builder<C, R> {
      private final Registry<String, Entry<C, R>> registry;

      private Builder(String name) { registry = Registry.create(name); }

      public Builder<C, R> register(String id, int priority, Rule<C, R> rule) {
         registry.register(id, new Entry<>(priority, Objects.requireNonNull(rule, "rule")));
         return this;
      }

      public RuleChain<C, R> build() {
         registry.freeze();
         return new RuleChain<>(registry.ordered(Comparator.comparingInt(Entry<C, R>::priority).reversed())
            .map(Entry::rule).toList());
      }
   }

   private record Entry<C, R>(int priority, Rule<C, R> rule) {}
}
