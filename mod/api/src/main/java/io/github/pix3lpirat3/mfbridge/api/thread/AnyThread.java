package io.github.pix3lpirat3.mfbridge.api.thread;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method, constructor or type that may be called from any thread; the implementation is
 * thread-safe. Documentation only: nothing is enforced at runtime.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.TYPE})
public @interface AnyThread {}
