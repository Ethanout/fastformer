package io.github.fastformer.fastplace;

import io.github.fastformer.fastplace.world.*;

import io.github.fastformer.fastplace.geometry.GeometryNumbers;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

public final class FastPlaceMessages {
   private FastPlaceMessages() {
   }

   static void actionBar(ServerPlayer player, String key, Object... args) {
      player.displayClientMessage(Component.translatable(key, cleanArgs(args)), true);
   }

   static void actionBar(ServerPlayer player, TranslatableText value, Object... args) {
      actionBar(player, value.translationKey(), args);
   }

   public static void actionBar(ServerPlayer player, Component message) {
      player.displayClientMessage(message, true);
   }

   static void chat(ServerPlayer player, String key, Object... args) {
      player.displayClientMessage(Component.translatable(key, cleanArgs(args)), false);
   }

   static void chat(ServerPlayer player, TranslatableText value, Object... args) {
      chat(player, value.translationKey(), args);
   }

   public static void chat(ServerPlayer player, Component message) {
      player.displayClientMessage(message, false);
   }

   public static MutableComponent text(String key, Object... args) {
      return Component.translatable(key, cleanArgs(args));
   }

   public static MutableComponent text(TranslatableText value) {
      return value.text();
   }

   public static MutableComponent text(TranslatableText value, Object... args) {
      return Component.translatable(value.translationKey(), cleanArgs(args));
   }

   private static Object[] cleanArgs(Object[] args) {
      if (args == null || args.length == 0) {
         return args;
      }
      Object[] result = args.clone();
      for (int i = 0; i < result.length; i++) {
         if (result[i] instanceof Double value) {
            result[i] = GeometryNumbers.finiteOr(value, 0.0);
         } else if (result[i] instanceof Float value) {
            result[i] = (float)GeometryNumbers.finiteOr(value, 0.0);
         }
      }
      return result;
   }
}
