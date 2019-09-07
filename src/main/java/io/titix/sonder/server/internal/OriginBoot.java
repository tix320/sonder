package io.titix.sonder.server.internal;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.gitlab.tixtix320.kiwi.api.observable.Observable;
import io.titix.sonder.Origin;
import io.titix.sonder.extra.ClientID;
import io.titix.sonder.internal.ExtraParam;
import io.titix.sonder.internal.OriginMethod;
import io.titix.sonder.internal.Param;
import io.titix.sonder.internal.boot.Boot;
import io.titix.sonder.internal.boot.BootException;

import static io.titix.sonder.internal.boot.BootException.throwWhen;
import static java.util.function.Predicate.not;

public class OriginBoot extends Boot<OriginMethod> {

	public OriginBoot(List<Class<?>> classes) {
		super(classes.stream().filter(clazz -> clazz.isAnnotationPresent(Origin.class)).collect(Collectors.toList()));
	}

	@Override
	protected void checkService(Class<?> clazz) {
		BootException.checkAndThrow(clazz,
				aClass -> String.format("Failed to resolve origin service(%s), there are the following errors.",
						aClass), throwWhen(not(Class::isInterface), "Must be interface"),
				throwWhen(not(aClass -> Modifier.isPublic(aClass.getModifiers())), "Must be public"));
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
				throwWhen(m -> m.getReturnType() != void.class && m.getReturnType() != Observable.class,
						"Return type must be void or Observable"),
				throwWhen(not(m -> Modifier.isPublic(m.getModifiers())), "Must be public"));
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
		return Map.of(ClientID.class, new ExtraParamDefinition(ClientID.class, long.class, true));
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
