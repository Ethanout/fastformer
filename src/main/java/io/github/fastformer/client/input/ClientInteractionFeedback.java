package io.github.fastformer.client.input;

import io.github.fastformer.client.interaction.selectiongesture.AabbAdjustDecision;
import io.github.fastformer.client.operation.clipboard.ClipboardActionResult;
import io.github.fastformer.client.operation.submission.SubmissionActionResult;
import io.github.fastformer.client.session.ClientSessionSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Selects and displays client actionbar feedback for interaction results. */
public final class ClientInteractionFeedback {
   private static final String COPY_SUCCESS = "fastformer.message.operation_copy_success";
   private static final String PASTE_SUCCESS = "fastformer.message.operation_paste_success";

   private ClientInteractionFeedback() {
   }

   public static void showCopyResult(Minecraft minecraft, ClipboardActionResult result) {
      show(minecraft, result.succeeded() ? COPY_SUCCESS : result.failureKey());
   }

   public static void showPasteResult(Minecraft minecraft, ClipboardActionResult result) {
      show(minecraft, result.succeeded() ? PASTE_SUCCESS : result.failureKey());
   }

   public static void showWorkspaceSubmitFailure(Minecraft minecraft, SubmissionActionResult result) {
      show(minecraft, result.failureKey());
   }

   /** Explains why a selection adjustment did not start. */
   public static void showAabbAdjustFailure(Minecraft minecraft, AabbAdjustDecision decision) {
      show(minecraft, ClientSessionSystem.instance().operations().transforms().aabbAdjustFailureKey(decision));
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
