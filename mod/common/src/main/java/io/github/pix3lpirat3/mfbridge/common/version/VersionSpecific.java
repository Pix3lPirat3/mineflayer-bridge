package io.github.pix3lpirat3.mfbridge.common.version;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method that only some game versions call, such as an override under a name another
 * version does not have. The build's link check skips it; keep such methods tiny.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface VersionSpecific {
}
