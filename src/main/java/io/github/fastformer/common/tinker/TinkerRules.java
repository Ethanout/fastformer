package io.github.fastformer.common.tinker;

import io.github.fastformer.core.rule.RuleChain;
import java.util.function.Consumer;

/** Assembles and freezes the tinker rules used by both prediction and authoritative edits. */
public final class TinkerRules {
   private final RuleChain<TinkerContext, TinkerAction> chain;

   private TinkerRules(RuleChain<TinkerContext, TinkerAction> chain) { this.chain = chain; }

   public static TinkerRules create(Consumer<Registration> assemble) {
      var builder = RuleChain.<TinkerContext, TinkerAction>builder("tinker");
      assemble.accept(rule -> builder.register(rule.id().toString(), rule.priority(), rule));
      return new TinkerRules(builder.build());
   }

   public TinkerAction resolve(TinkerContext context) { return chain.apply(context).orElse(TinkerAction.PASS); }

   @FunctionalInterface
   public interface Registration { void register(TinkerRule rule); }
}
