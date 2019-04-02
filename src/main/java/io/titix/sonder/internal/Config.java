package io.titix.sonder.internal;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author Tigran.Sargsyan on 24-Jan-19
 */
public final class Config {

	private static final Pattern packagePattern = Pattern.compile(
			"^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)*[0-9a-zA-Z_]$");

	public static List<Class<?>> getPackageClasses(String[] packageNames) {
		return Arrays.stream(packageNames)
				.flatMap(packageName -> getPackageClasses(packageName).stream())
				.collect(Collectors.toList());
	}

	private static List<Class<?>> getPackageClasses(String packageName) {
		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

		if (!packagePattern.matcher(packageName).matches()) {
			throw new IllegalArgumentException("Invalid package name " + packageName);
		}

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
					classes.add(Class.forName(
							packageName + '.' + file.getName().substring(0, file.getName().length() - 6)));
				}
				catch (ClassNotFoundException e) {
					throw new InternalException(e);
				}
			}
		}
		return classes;
	}
}
