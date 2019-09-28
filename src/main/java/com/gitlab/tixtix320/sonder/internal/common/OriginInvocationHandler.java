package com.gitlab.tixtix320.sonder.internal.common;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class OriginInvocationHandler implements InvocationHandler {

	private final Function<Method, OriginMethod> toOriginMethodFunction;

	private final Handler handler;

	public OriginInvocationHandler(Function<Method, OriginMethod> toOriginMethodFunction, Handler handler) {
		this.toOriginMethodFunction = toOriginMethodFunction;
		this.handler = handler;
	}

	@Override
	public Object invoke(Object proxy, Method method, Object[] args) {
		if (method.getDeclaringClass() == Object.class) {
			throw new UnsupportedOperationException("This method does not allowed on origin services");
		}

		OriginMethod originMethod = toOriginMethodFunction.apply(method);
		List<Param> simpleParams = originMethod.simpleParams;
		List<ExtraParam> extraParams = originMethod.extraParams;

		List<Object> simpleArgs = new ArrayList<>(simpleParams.size());
		Map<Class<? extends Annotation>, ExtraArg> extraArgs = new HashMap<>(extraParams.size());

		for (Param simpleParam : simpleParams) {
			int index = simpleParam.index;
			simpleArgs.add(args[index]);
		}

		for (ExtraParam extraParam : extraParams) {
			int index = extraParam.index;
			extraArgs.put(extraParam.getAnnotation().annotationType(),
					new ExtraArg(args[index], extraParam.getAnnotation()));
		}

		return handler.handle(originMethod, simpleArgs, extraArgs);
	}

	public interface Handler {

		Object handle(OriginMethod originMethod, List<Object> simpleArgs,
					  Map<Class<? extends Annotation>, ExtraArg> extraArgs);
	}
}
