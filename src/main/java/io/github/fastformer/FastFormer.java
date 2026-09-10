package io.github.fastformer;

import io.github.fastformer.fastplace.events.FastPlaceEvents;
import io.github.fastformer.network.FastPlaceNetwork;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod("fastformer")
public final class FastFormer {
   public static final String MOD_ID = "fastformer";

   public FastFormer(IEventBus modBus) {
      FastPlaceNetwork.register(modBus);
      FastPlaceEvents.register();
      modBus.addListener(FastFormerGameTests::register);
   }
}
