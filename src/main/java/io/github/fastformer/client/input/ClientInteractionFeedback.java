package io.github.fastformer.client.input;

import io.github.fastformer.client.session.ClientSessionSystem;

import io.github.fastformer.client.operation.controller.OperationContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Selects and displays client actionbar feedback for interaction results. */
public final class ClientInteractionFeedback {
   private static final String COPY_FAILED = "fastformer.message.operation_copy_failed";
   private static final String COPY_SUCCESS = "fastformer.message.operation_copy_success";
   private static final String PASTE_FAILED = "fastformer.message.operation_paste_failed";
   private static final String PASTE_SUCCESS = "fastformer.message.operation_paste_success";
   private static final String SUBMIT_FAILED = "fastformer.message.operation_submit_failed";

   private ClientInteractionFeedback() {
   }

   public static void showCopyResult(Minecraft minecraft, boolean succeeded) {
      String failureKey = succeeded
         ? null
         : ClientSessionSystem.instance().operations().lastOperationFailureKey(COPY_FAILED);
      show(minecraft, resultKey(
         succeeded,
         COPY_SUCCESS,
         failureKey
      ));
   }

   public static void showPasteResult(Minecraft minecraft, boolean succeeded) {
      String failureKey = succeeded
         ? null
         : ClientSessionSystem.instance().operations().lastOperationFailureKey(PASTE_FAILED);
      show(minecraft, resultKey(
         succeeded,
         PASTE_SUCCESS,
         failureKey
      ));
   }

   public static void showWorkspaceSubmitFailure(Minecraft minecraft) {
      show(minecraft, ClientSessionSystem.instance().operations().lastOperationFailureKey(SUBMIT_FAILED));
   }

   /** Explains why a selection adjustment did not start. */
   public static void showAabbAdjustFailure(Minecraft minecraft, OperationContext.AabbAdjustDecision decision) {
      show(minecraft, ClientSessionSystem.instance().operations().aabbAdjustFailureKey(decision));
   }

   public static void show(Minecraft minecraft, String translationKey) {
      if (minecraft != null && minecraft.player != null && translationKey != null) {
         minecraft.player.displayClientMessage(Component.translatable(translationKey), true);
      }
   }

   static String resultKey(boolean succeeded, String successKey, String failureKey) {
      return succeeded ? successKey : failureKey;
   }
}
