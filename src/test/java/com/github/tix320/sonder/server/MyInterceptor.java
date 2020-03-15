package com.github.tix320.sonder.server;

import java.util.List;

import com.github.tix320.kiwi.api.proxy.AnnotationInterceptor;
import com.github.tix320.kiwi.api.util.None;

public class MyInterceptor implements AnnotationInterceptor<Object, MyAnno> {

	@Override
	public Class<MyAnno> getAnnotationClass() {
		return MyAnno.class;
	}

	@Override
	public Object intercept(MyAnno annotation, InterceptionContext<Object> context) {
		Object[] args = context.getArgs();
		String message = (String) args[args.length - 1];

		if (message.equals("stop")) {
			return List.of(4, 8, 7);
		}

		System.out.println("Intercepted: " + message);
		return None.SELF;
	}
}
