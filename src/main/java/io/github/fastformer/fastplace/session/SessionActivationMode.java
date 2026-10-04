package io.github.fastformer.fastplace.session;

public enum SessionActivationMode {
   MANUAL,
   AUTOMATIC;

   public SessionActivationMode next() { return this == MANUAL ? AUTOMATIC : MANUAL; }
   public String translationKey() { return "fastformer.settings.activation." + name().toLowerCase(java.util.Locale.ROOT); }
}
