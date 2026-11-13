package io.github.fastformer.client.interaction.binding.option;

import io.github.fastformer.client.interaction.binding.BindingConfiguration;
import java.util.List;
import net.minecraft.network.chat.Component;

/** Presentation adapter for data-defined options; screens do not own a second copy of their values. */
public final class OptionSettingsSection {
   public record Row(Component label, java.util.function.BooleanSupplier value, Runnable toggle) {}
   private OptionSettingsSection() {}

   public static List<Row> rows() {
      OptionStore store = BindingConfiguration.instance().options();
      return store.definitions().stream().map(option -> new Row(Component.translatable(option.translationKey()),
         () -> store.enabled(option.id()), () -> store.set(option.id(), !store.enabled(option.id())))).toList();
   }

   public static List<Component> errors() {
      return BindingConfiguration.instance().diagnostics().stream()
         .map(message -> Component.literal(message).withStyle(net.minecraft.ChatFormatting.RED)).map(value -> (Component)value).toList();
   }
}
