package io.github.fastformer.common.tinker.rule;

import io.github.fastformer.common.tinker.data.TinkerPropertyRule;
import io.github.fastformer.common.tinker.data.TinkerWoodFamily;
import io.github.fastformer.common.tinker.data.TinkerShapeFamilies;
import io.github.fastformer.common.tinker.state.TinkerCopperCycle;
import io.github.fastformer.common.tinker.state.TinkerStateRules;
import io.github.fastformer.common.tinker.state.TinkerGroundCycle;
import io.github.fastformer.common.tinker.state.TinkerTorchCycle;
import io.github.fastformer.common.tinker.shape.TinkerStructureRules;
import io.github.fastformer.common.tinker.shape.TinkerShapeTransform;
import io.github.fastformer.common.tinker.shape.TinkerSurfaceRules;
import io.github.fastformer.common.tinker.TinkerAction;
import io.github.fastformer.common.tinker.TinkerContext;
import io.github.fastformer.common.tinker.TinkerRule;
import io.github.fastformer.common.tinker.TinkerRules;
import java.util.Optional;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

/** Built-in priority ordering. Rules return a terminal result even when no edit is permitted. */
public final class StandardTinkerRules {
   private static final List<Property<?>> GENERIC_PROPERTIES = List.of(
      BlockStateProperties.OPEN, BlockStateProperties.LIT, BlockStateProperties.POWERED);
   private StandardTinkerRules() {}

   public static void register(TinkerRules.Registration registration) {
      registration.register(new PropertyOverride());
      registration.register(new Copper());
      registration.register(new Structure());
      registration.register(new State());
      registration.register(new ShiftFallback());
      registration.register(new Ground());
      registration.register(new Torch());
      registration.register(new Wood());
      registration.register(new Shape());
      registration.register(new Surface());
      registration.register(new Generic());
   }

   private static Optional<TinkerAction> edit(TinkerContext context, BlockState state) {
      return state == null ? Optional.empty() : Optional.of(TinkerAction.edit(context.level(), context.pos(), state));
   }

   private static final class PropertyOverride implements TinkerRule {
      public ResourceLocation id() { return ResourceLocation.parse("fastformer:property_override"); }
      public int priority() { return 1100; }
      public Optional<TinkerAction> apply(TinkerContext c) {
         return TinkerPropertyRule.find(c.level().registryAccess(), c.block(), c.shift())
            .map(rule -> rule.apply(c.level(), c.state(), c.hit()));
      }
   }

   private static final class Copper implements TinkerRule {
      public ResourceLocation id() { return ResourceLocation.parse("fastformer:copper"); }
      public int priority() { return 1000; }
      public Optional<TinkerAction> apply(TinkerContext c) {
         BlockState changed = TinkerCopperCycle.apply(c.state(), c.hit(), c.shift());
         if (changed == null) return Optional.empty();
         return Optional.of(c.block() instanceof DoorBlock
            ? TinkerStructureRules.doorChange(c.level(), c.pos(), c.state(), changed)
            : TinkerAction.edit(c.level(), c.pos(), changed));
      }
   }

   private static final class Structure implements TinkerRule {
      public ResourceLocation id() { return ResourceLocation.parse("fastformer:structure"); }
      public int priority() { return 900; }
      public Optional<TinkerAction> apply(TinkerContext c) {
         return Optional.ofNullable(TinkerStructureRules.apply(c.level(), c.state(), c.hit(), c.shift()));
      }
   }

   private static final class State implements TinkerRule {
      public ResourceLocation id() { return ResourceLocation.parse("fastformer:state"); }
      public int priority() { return 800; }
      public Optional<TinkerAction> apply(TinkerContext c) {
         return edit(c, TinkerStateRules.apply(c.state(), c.hit(), c.shift(), c.skipRepeatedCropStages()));
      }
   }

   private static final class ShiftFallback implements TinkerRule {
      public ResourceLocation id() { return ResourceLocation.parse("fastformer:shift_fallback"); }
      public int priority() { return 700; }
      public Optional<TinkerAction> apply(TinkerContext c) {
         return c.shift() ? Optional.of(TinkerAction.PASS) : Optional.empty();
      }
   }

   private static final class Ground implements TinkerRule {
      public ResourceLocation id() { return ResourceLocation.parse("fastformer:ground"); }
      public int priority() { return 600; }
      public Optional<TinkerAction> apply(TinkerContext c) { return edit(c, TinkerGroundCycle.apply(c.state(), c.face())); }
   }

   private static final class Torch implements TinkerRule {
      public ResourceLocation id() { return ResourceLocation.parse("fastformer:torch"); }
      public int priority() { return 500; }
      public Optional<TinkerAction> apply(TinkerContext c) { return edit(c, TinkerTorchCycle.apply(c.state())); }
   }

   private static final class Wood implements TinkerRule {
      public ResourceLocation id() { return ResourceLocation.parse("fastformer:wood"); }
      public int priority() { return 400; }
      public Optional<TinkerAction> apply(TinkerContext c) {
         return TinkerWoodFamily.find(c.level().registryAccess(), c.block())
            .map(family -> TinkerAction.edit(c.level(), c.pos(), family.apply(c.state(), c.hit())));
      }
   }

   private static final class Shape implements TinkerRule {
      public ResourceLocation id() { return ResourceLocation.parse("fastformer:shape"); }
      public int priority() { return 300; }
      public Optional<TinkerAction> apply(TinkerContext c) {
         if (c.state().hasBlockEntity()) return Optional.empty();
         return TinkerShapeFamilies.find(c.level().registryAccess(), c.block()).map(family -> {
            BlockState changed = TinkerShapeTransform.apply(family, c.state(), c.hit());
            if (changed.equals(c.state()) && TinkerCopperCycle.supports(c.state())) changed = TinkerCopperCycle.combined(c.state());
            return TinkerAction.edit(c.level(), c.pos(), changed);
         });
      }
   }

   private static final class Surface implements TinkerRule {
      public ResourceLocation id() { return ResourceLocation.parse("fastformer:surface"); }
      public int priority() { return 200; }
      public Optional<TinkerAction> apply(TinkerContext c) { return TinkerSurfaceRules.apply(c); }
   }

   private static final class Generic implements TinkerRule {
      public ResourceLocation id() { return ResourceLocation.parse("fastformer:generic"); }
      public int priority() { return 100; }
      public Optional<TinkerAction> apply(TinkerContext c) {
         if (c.state().hasBlockEntity() || !Block.isShapeFullBlock(c.state().getShape(c.level(), c.pos()))) return Optional.empty();
         for (Property<?> property : GENERIC_PROPERTIES) {
            if (c.state().hasProperty(property)) return edit(c, c.state().cycle(property));
         }
         return Optional.empty();
      }
   }
}
