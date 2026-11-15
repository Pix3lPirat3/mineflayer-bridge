package io.github.pix3lpirat3.mfbridge.api.thread;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method, constructor or type whose members may only be called on the render thread. Such
 * code reads only immutable render snapshots. Documentation only: nothing is enforced at runtime.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.TYPE})
public @interface RenderThread {}
