package com.fundamentum.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Not API: public only so Fundamentum's own packages can reach it. It may change or go in any release; addons should
 * not call it. See {@link FundamentumApi}.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.CONSTRUCTOR, ElementType.PACKAGE})
public @interface Internal {
    /** Optional note on what to use instead. */
    String value() default "";
}
