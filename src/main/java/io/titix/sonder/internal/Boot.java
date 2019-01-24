package io.titix.sonder.internal;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import io.titix.sonder.Endpoint;
import io.titix.sonder.Origin;

/**
 * @author Tigran.Sargsyan on 12-Dec-18
 */
final class Boot {

	private static final Pattern packagePattern = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+[0-9a-zA-Z_]$");

	private final OriginHolder originHolder;

	private final EndpointHolder endpointHolder;

	Boot() {
		List<Class<?>> classes = getPackageClasses(getBootPackages()).stream()
				.peek(Boot::checkClass).collect(Collectors.toList());

		OriginHolder originHolder = new OriginHolder(classes.stream()
				.filter(clazz -> clazz.isAnnotationPresent(Origin.class))
				.collect(Collectors.toList()));

		EndpointHolder endpointHolder = new EndpointHolder(classes.stream()
				.filter(clazz -> clazz.isAnnotationPresent(Endpoint.class))
				.collect(Collectors.toList()));

		this.originHolder = originHolder;
		this.endpointHolder = endpointHolder;
	}

	Collection<Class<?>> getOriginServices() {
		return originHolder.getServices();
	}

	Collection<Class<?>> getEndpointServices() {
		return endpointHolder.getServices();
	}

	Signature getOrigin(Method method) {
		return originHolder.getSignature(method);
	}

	Signature getEndpoint(String path) {
		return endpointHolder.getSignature(path);
	}

	private static void checkClass(Class<?> clazz) {
		if (clazz.isAnnotationPresent(Origin.class)) {
			if (clazz.isAnnotationPresent(Endpoint.class)) {
				throw new BootException("Must be one of the annotations: " + Origin.class.getName() + " or " + Endpoint.class
						.getName() + ". in class " + clazz.getName());
			}
		}
	}

	private static String[] getBootPackages() {
		final String key = "service-packages";
		Properties properties = new Properties();
		try {
			InputStream resource = ClassLoader.getSystemClassLoader().getResourceAsStream("boot-config.properties");
			if (resource == null) {
				throw new BootException("boot-config.properties file not found");
			}
			properties.load(resource);
			String property = properties.getProperty(key);
			if (property == null) {
				throw new BootException("Property '" + key + "' is required");
			}
			String[] packages = property.split(",");
			for (String packageName : packages) {
				if (!packagePattern.matcher(packageName).matches()) {
					throw new BootException("Invalid package name");
				}
			}
			return packages;
		}
		catch (IOException e) {
			throw new BootException("Cannot read boot-config.properties file");
		}
	}

	private static List<Class<?>> getPackageClasses(String[] packageNames) {
		return Arrays.stream(packageNames)
				.flatMap(packageName -> getPackageClasses(packageName).stream())
				.collect(Collectors.toList());
	}

	private static List<Class<?>> getPackageClasses(String packageName) {
		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

		String path = packageName.replace('.', '/');

		return classLoader.resources(path)
				.map(url -> new File(url.getFile()))
				.flatMap(directory -> findClasses(directory, packageName).stream())
				.collect(Collectors.toList());
	}

	private static List<Class<?>> findClasses(File directory, String packageName) {
		List<Class<?>> classes = new ArrayList<>();
		if (!directory.exists()) {
			return classes;
		}

		File[] files = directory.listFiles();
		if (files == null) {
			return classes;
		}
		for (File file : files) {
			if (file.isDirectory()) {
				classes.addAll(findClasses(file, packageName + "." + file.getName()));
			}
			else if (file.getName().endsWith(".class")) {
				try {
					classes.add(Class.forName(packageName + '.' + file.getName()
							.substring(0, file.getName().length() - 6)));
				}
				catch (ClassNotFoundException e) {
					throw new BootException(e);
				}
			}
		}
		return classes;
	}
}
