package org.eclipse.jdt.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Compile-time stand-in for Eclipse's null-analysis annotation; never packaged. */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE_USE)
public @interface Nullable {
}
