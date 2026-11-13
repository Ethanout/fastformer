package io.github.fastformer.common.persistence;

import java.io.IOException;

/** Data is intact but cannot safely be interpreted by this Minecraft version. */
public final class IncompatibleWorldDataException extends IOException {
   public IncompatibleWorldDataException(String message) { super(message); }
   public IncompatibleWorldDataException(String message, Throwable cause) { super(message, cause); }
}
