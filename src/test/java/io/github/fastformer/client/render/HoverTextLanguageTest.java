package io.github.fastformer.client.render;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class HoverTextLanguageTest {
   private static final List<String> REQUIRED_KEYS = List.of(
      "fastformer.hud.transform.position",
      "fastformer.hud.transform.scale",
      "fastformer.hud.transform.rotation",
      "fastformer.hud.selection.push",
      "fastformer.hud.selection.pull",
      "fastformer.operation.face_drag_hint",
      "fastformer.operation.selection_click_hint",
      "fastformer.operation.selection_create_hint",
      "fastformer.operation.control_point.primary",
      "fastformer.operation.control_point.secondary",
      "fastformer.operation.control_point.center",
      "fastformer.operation.control_point.radius",
      "fastformer.operation.control_point.diameter",
      "fastformer.operation.control_point.base_face",
      "fastformer.operation.control_point.height",
      "fastformer.operation.control_point.handle",
      "fastformer.operation.part_label.id",
      "fastformer.operation.part_label.hovered",
      "fastformer.operation.part_label.selected",
      "fastformer.operation.part_label.deselect",
      "fastformer.operation.part_label.append",
      "fastformer.operation.part_label.action",
      "fastformer.message.operation_submit_no_content",
      "fastformer.message.operation_source_changed",
      "fastformer.message.operation_submit_pending",
      "fastformer.message.operation_submit_success_kept",
      "fastformer.message.operation_submit_failed_retry",
      "fastformer.message.operation_submit_failed_final",
      "fastformer.message.operation_submit_recovery",
      "fastformer.message.operation_submit_unverified",
      "fastformer.message.operation_receipt_save_failed",
      "fastformer.message.operation_receipt_load_failed",
      "fastformer.message.operation_receipt_version_incompatible",
      "fastformer.message.operation_receipt_capacity_reached",
      "fastformer.message.operation_submit_cleanup_pending",
      "fastformer.message.operation_draft_restore_kept",
      "fastformer.message.operation_draft_applied_not_restored",
      "fastformer.message.operation_draft_receipt_unknown_not_restored"
   );

   @Test
   void everyHoverTextKeyExistsInEnglishAndChinese() {
      assertContainsRequiredKeys("en_us");
      assertContainsRequiredKeys("zh_cn");
   }

   private static void assertContainsRequiredKeys(String language) {
      String resource = "/assets/fastformer/lang/" + language + ".json";
      try (var stream = HoverTextLanguageTest.class.getResourceAsStream(resource)) {
         assertTrue(stream != null, "Missing language resource: " + resource);
         JsonObject translations = JsonParser.parseReader(
            new InputStreamReader(stream, StandardCharsets.UTF_8)
         ).getAsJsonObject();
         for (String key : REQUIRED_KEYS) {
            assertTrue(translations.has(key), () -> resource + " is missing hover text: " + key);
            assertTrue(!translations.get(key).getAsString().isBlank(), () -> resource + " has blank hover text: " + key);
         }
      } catch (java.io.IOException failure) {
         throw new AssertionError("Cannot read language resource: " + resource, failure);
      }
   }
}
