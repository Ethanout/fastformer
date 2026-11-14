package io.github.fastformer.protocol.settings;

import io.github.fastformer.protocol.c2s.settings.SettingPayload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.fastformer.common.placement.PlacementUpdateMode;
import io.github.fastformer.common.workspace.model.OperationConflictMode;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

class SettingPayloadTest {
   @Test
   void registeredValueTypesAndQueriesRoundTrip() {
      for (SettingRequest<?> request : List.of(
         SettingsKeys.MIDDLE_CONFIRM.set(true), SettingsKeys.EMPTY_HAND_WRENCH.set(false),
         SettingsKeys.VANILLA_BLOCK_HISTORY.set(true), SettingsKeys.SKIP_REPEATED_CROP_STAGES.set(false),
         SettingsKeys.FALLING_DISABLED.query(), SettingsKeys.FALLING_DISABLED.set(true),
         SettingsKeys.WORLD_HISTORY_LIMIT.set(800), SettingsKeys.SESSION_HISTORY_LIMIT.set(1),
         SettingsKeys.RECENT_AFTER.set(5), SettingsKeys.RECENT_GROUP.set(8),
         SettingsKeys.OLDER_AFTER.set(32), SettingsKeys.OLDER_GROUP.set(4),
         SettingsKeys.OLDEST_AFTER.set(128), SettingsKeys.OLDEST_GROUP.set(4),
         SettingsKeys.PLACEMENT_CONFLICT.set(OperationConflictMode.KEEP_EXISTING),
         SettingsKeys.PLACEMENT_UPDATE.set(PlacementUpdateMode.CLIENT_ONLY),
         SettingsKeys.PLACEMENT_EFFECT.set(new PlacementEffectValue(ResourceLocation.parse("fastformer:wood_frame"), false)))) {
         FriendlyByteBuf buffer = buffer();
         try {
            var payload = new SettingPayload(request);
            SettingPayload.STREAM_CODEC.encode(buffer, payload);
            assertEquals(payload, SettingPayload.STREAM_CODEC.decode(buffer));
            assertEquals(0, buffer.readableBytes());
         } finally { buffer.release(); }
      }
   }

   @Test
   void unknownKeyDoesNotFallBackToAnotherSetting() {
      FriendlyByteBuf buffer = buffer();
      try {
         buffer.writeResourceLocation(ResourceLocation.parse("fastformer:missing"));
         buffer.writeBoolean(false);
         assertThrows(DecoderException.class, () -> SettingPayload.STREAM_CODEC.decode(buffer));
      } finally { buffer.release(); }
   }

   @Test
   void writeRequiresAValueAndDoesNotDecodeAsAQueryWhenTruncated() {
      FriendlyByteBuf buffer = buffer();
      try {
         buffer.writeResourceLocation(SettingsKeys.MIDDLE_CONFIRM.id());
         buffer.writeBoolean(true);
         assertThrows(IndexOutOfBoundsException.class, () -> SettingPayload.STREAM_CODEC.decode(buffer));
      } finally { buffer.release(); }
   }

   @Test
   void equalIdFromAnotherCatalogCannotMasqueradeAsARegisteredKey() {
      var catalog = new SettingCatalog();
      var foreign = catalog.register(SettingsKeys.MIDDLE_CONFIRM.id(), Boolean.class, ByteBufCodecs.BOOL);
      FriendlyByteBuf buffer = buffer();
      try {
         assertThrows(IllegalArgumentException.class,
            () -> SettingPayload.STREAM_CODEC.encode(buffer, new SettingPayload(foreign.set(true))));
         assertEquals(0, buffer.writerIndex());
      } finally { buffer.release(); }
   }

   @Test
   void frozenCatalogRejectsLateRegistration() {
      var catalog = new SettingCatalog();
      catalog.freeze();
      assertThrows(IllegalStateException.class,
         () -> catalog.register(ResourceLocation.parse("test:late"), Boolean.class, ByteBufCodecs.BOOL));
   }

   @Test
   @SuppressWarnings({"rawtypes", "unchecked"})
   void malformedRawRequestFailsBeforeReachingAHandler() {
      assertThrows(ClassCastException.class, () -> new SettingRequest((SettingKey) SettingsKeys.MIDDLE_CONFIRM, Optional.of(1)));
   }

   private static FriendlyByteBuf buffer() { return new FriendlyByteBuf(Unpooled.buffer()); }
}
