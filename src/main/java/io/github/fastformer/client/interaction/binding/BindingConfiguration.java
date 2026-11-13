package io.github.fastformer.client.interaction.binding;

import io.github.fastformer.client.interaction.binding.action.*;
import io.github.fastformer.client.interaction.binding.context.*;
import io.github.fastformer.client.interaction.binding.data.*;
import io.github.fastformer.client.interaction.binding.option.*;
import io.github.fastformer.client.session.ClientStorageQueue;
import io.github.fastformer.client.lifecycle.ClientBackgroundTasks;
import java.util.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

/** Application-scoped immutable definitions; each input session owns its own BindingSystem. */
@EventBusSubscriber(modid = "fastformer", value = Dist.CLIENT)
public final class BindingConfiguration {
   private static final BindingConfiguration INSTANCE = new BindingConfiguration();
   private final ClientStorageQueue storage = new ClientStorageQueue(ClientBackgroundTasks.serial("binding-storage", 8));
   private final OptionStore options = new OptionStore(FMLPaths.CONFIGDIR.get().resolve("fastformer/options.json"), storage);
   private final ContextMatcher matcher = new ContextMatcher(ContextFields.defaults().register("double_second_press", com.google.gson.JsonElement::equals).freeze());
   private final BindingActions actions = BuiltinBindingActions.create();
   private List<BindingSystem.Rule> rules = List.of();
   private List<String> diagnostics = List.of();
   private long revision;

   private BindingConfiguration() {}
   public static BindingConfiguration instance() { return INSTANCE; }
   public BindingSystem createSystem() { return new BindingSystem(matcher, () -> rules, options::enabled); }
   public OptionStore options() { return options; }
   public List<String> diagnostics() { return diagnostics; }
   public long revision() { return revision; }

   public void install(BindingFile file) {
      List<BindingSystem.Rule> compiled = new ArrayList<>();
      List<String> errors = new ArrayList<>(file.diagnostics());
      for (InteractionBinding binding : file.bindings().stream().sorted(InteractionBinding.ORDER).toList()) {
         if (binding.context().keySet().stream().anyMatch(field -> !matcher.known(field))) {
            errors.add(binding.id() + ": unknown context field"); continue;
         }
         var action = actions.resolve(binding.action());
         action.result().ifPresent(value -> compiled.add(new BindingSystem.Rule(binding, value)));
         action.error().ifPresent(error -> errors.add(binding.id() + ": " + error.message()));
      }
      options.install(file.options());
      rules = List.copyOf(compiled);
      diagnostics = List.copyOf(errors);
      revision++;
      errors.forEach(error -> com.mojang.logging.LogUtils.getLogger().warn("Interaction binding: {}", error));
   }

   public void tick(long now) { storage.drain(); options.tick(now); }

   @SubscribeEvent public static void register(RegisterClientReloadListenersEvent event) {
      event.registerReloadListener(new BindingLoader(FMLPaths.CONFIGDIR.get().resolve("fastformer/bindings.json")));
   }
}
