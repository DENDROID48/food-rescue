package com.foodrescue.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller parameter that receives the id of the signed-in user, taken from the
 * verified login token. For example {@code public List<OrderResponse> mine(@CurrentUser Long userId)}.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
