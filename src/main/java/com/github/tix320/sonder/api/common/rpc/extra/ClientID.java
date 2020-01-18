package com.github.tix320.sonder.api.common.rpc.extra;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.github.tix320.sonder.internal.common.rpc.extra.ExtraParamQualifier;

/**
 * @author Tigran.Sargsyan on 04-Jan-19
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
@ExtraParamQualifier
public @interface ClientID {}
