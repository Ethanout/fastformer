package io.github.fastformer;

import io.github.fastformer.fastplace.events.FastPlaceEvents;
import io.github.fastformer.network.FastPlaceNetwork;
import io.github.fastformer.server.history.HistoryStorageConfig;
import io.github.fastformer.fastplace.placement.context.PlacementFallbacks;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod("fastformer")
public final class FastFormer {
   public static final String MOD_ID = "fastformer";

   public FastFormer(IEventBus modBus, ModContainer container) {
      container.registerConfig(ModConfig.Type.SERVER, HistoryStorageConfig.SPEC);
      modBus.addListener(HistoryStorageConfig::onConfig);
      container.registerConfig(ModConfig.Type.CLIENT, io.github.fastformer.client.FastFormerClientConfig.SPEC);
      modBus.addListener(PlacementFallbacks::register);
      modBus.addListener(io.github.fastformer.common.tinker.data.TinkerShapeFamilies::register);
      modBus.addListener(io.github.fastformer.common.tinker.data.TinkerPropertyRule::register);
      modBus.addListener(io.github.fastformer.common.tinker.data.TinkerWoodFamily::register);
      modBus.addListener(io.github.fastformer.common.interaction.binding.SessionProfiles::register);
      FastPlaceNetwork.register(modBus);
      FastPlaceEvents.register();
   }
}
