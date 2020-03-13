package com.github.tix320.sonder.server;

import java.lang.reflect.Method;
import java.util.List;

import com.github.tix320.kiwi.api.proxy.Interceptor;
import com.github.tix320.kiwi.api.util.None;

public class MyInterceptor implements Interceptor<Object> {

	@Override
	public Object intercept(Method method, Object[] args, Object proxy) {
		String message = (String) args[args.length - 1];

		if (message.equals("stop")) {
			return List.of(4, 8, 7);
		}

		System.out.println("Intercepted: " + message);
		return None.SELF;
	}
}
