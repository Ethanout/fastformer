package io.github.fastformer.fastplace.geometry;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record GizmoTextTemplate(String template) {
   private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z][A-Za-z0-9_]*)}");

   public GizmoTextTemplate {
      template = template == null ? "" : template;
   }

   public String render(GizmoTextContext context) {
      GizmoTextContext safeContext = context == null ? GizmoTextContext.empty() : context;
      Matcher matcher = VARIABLE.matcher(this.template);
      StringBuffer result = new StringBuffer();
      while (matcher.find()) {
         String value = safeContext.value(matcher.group(1));
         matcher.appendReplacement(
            result,
            Matcher.quoteReplacement(value == null ? matcher.group(0) : value)
         );
      }
      matcher.appendTail(result);
      return result.toString();
   }
}
