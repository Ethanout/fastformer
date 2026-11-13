package io.github.fastformer.common.tinker;

import io.github.fastformer.core.rule.Rule;
import net.minecraft.resources.ResourceLocation;

/** Empty means unmatched. A present PASS result stops evaluation and permits vanilla interaction. */
public interface TinkerRule extends Rule<TinkerContext, TinkerAction> {
   ResourceLocation id();
   int priority();
}
