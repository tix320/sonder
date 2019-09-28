package com.gitlab.tixtix320.sonder.internal.client;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.gitlab.tixtix320.kiwi.api.observable.Observable;
import com.gitlab.tixtix320.sonder.api.common.Origin;
import com.gitlab.tixtix320.sonder.api.extra.ClientID;
import com.gitlab.tixtix320.sonder.internal.common.ExtraParam;
import com.gitlab.tixtix320.sonder.internal.common.OriginMethod;
import com.gitlab.tixtix320.sonder.internal.common.Param;
import com.gitlab.tixtix320.sonder.internal.common.Boot;
import com.gitlab.tixtix320.sonder.internal.common.BootException;

import static java.util.function.Predicate.not;

public class OriginBoot extends Boot<OriginMethod> {

	public OriginBoot(List<Class<?>> classes) {
		super(classes.stream().filter(clazz -> clazz.isAnnotationPresent(Origin.class)).collect(Collectors.toList()));
	}

	@Override
	protected void checkService(Class<?> clazz) {
		BootException.checkAndThrow(clazz,
				aClass -> String.format("Failed to resolve origin service(%s), there are the following errors.",
						aClass), BootException.throwWhen(not(Class::isInterface), "Must be interface"),
				BootException.throwWhen(not(aClass -> Modifier.isPublic(aClass.getModifiers())), "Must be public"));
	}

	@Override
	protected boolean isServiceMethod(Method method) {
		if (method.isAnnotationPresent(Origin.class)) {
			return true;
		}
		if (Modifier.isAbstract(method.getModifiers())) {
			throw new BootException(String.format("Abstract method '%s'(%s) must be annotated by @%s", method.getName(),
					method.getDeclaringClass(), Origin.class.getSimpleName()));
		}
		else {
			return false;
		}
	}

	@Override
	protected void checkMethod(Method method) {
		BootException.checkAndThrow(method,
				m -> String.format("Failed to resolve endpoint method '%s'(%s), there are the following errors. ",
						m.getName(), m.getDeclaringClass()),
				BootException.throwWhen(m -> m.getReturnType() != void.class && m.getReturnType() != Observable.class,
						"Return type must be void or Observable"),
				BootException.throwWhen(not(m -> Modifier.isPublic(m.getModifiers())), "Must be public"));
	}

	@Override
	protected String getPath(Method method) {
		return method.getDeclaringClass().getAnnotation(Origin.class).value() + ":" + method.getAnnotation(Origin.class)
				.value();
	}

	@Override
	protected OriginMethod createServiceMethod(String path, Method method, List<Param> simpleParams,
											   List<ExtraParam> extraParams) {
		return new OriginMethod(path, method, simpleParams, extraParams, needResponse(method),
				getDestination(extraParams));
	}

	@Override
	protected Map<Class<? extends Annotation>, ExtraParamDefinition> getExtraParamDefinitions() {
		return Map.of(ClientID.class, new ExtraParamDefinition(ClientID.class, long.class, false));
	}

	private boolean needResponse(Method method) {
		return method.getReturnType() != void.class;
	}

	private OriginMethod.Destination getDestination(List<ExtraParam> extraParams) {
		for (ExtraParam extraParam : extraParams) {
			if (extraParam.getAnnotation().annotationType() == ClientID.class) {
				return OriginMethod.Destination.CLIENT;
			}
		}
		return OriginMethod.Destination.SERVER;
	}
}
