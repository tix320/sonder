package io.titix.sonder.internal;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author Tigran.Sargsyan on 24-Jan-19
 */
public class Config {

	private static final Pattern packagePattern = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)*[0-9a-zA-Z_]$");

	private static final Properties properties;

	private static final String BOOT_CONFIG_FILE = "boot-config.properties";

	private static final String CLIENT_BOOT_KEY = "client-service-packages";

	private static final String SERVER_BOOT_KEY = "server-service-packages";

	static {
		properties = new Properties();
		try {
			InputStream resource = ClassLoader.getSystemClassLoader().getResourceAsStream(BOOT_CONFIG_FILE);
			if (resource == null) {
				throw new BootException(BOOT_CONFIG_FILE + " file not found");
			}
			properties.load(resource);

		}
		catch (IOException e) {
			throw new BootException("Cannot read " + BOOT_CONFIG_FILE + " file");
		}
	}

	public static String[] getClientBootPackages() {
		return splitPackages(getConfigValue(CLIENT_BOOT_KEY));
	}

	public static String[] getServerBootPackages() {
		return splitPackages(getConfigValue(SERVER_BOOT_KEY));
	}

	static List<Class<?>> getPackageClasses(String[] packageNames) {
		return Arrays.stream(packageNames)
				.flatMap(packageName -> getPackageClasses(packageName).stream())
				.collect(Collectors.toList());
	}

	private static String getConfigValue(String key) {
		String property = properties.getProperty(key);
		if (property == null) {
			throw new BootException("Property '" + key + "' in " + BOOT_CONFIG_FILE + " is required");
		}
		return property;
	}

	private static String[] splitPackages(String packageNames) {
		String[] packages = packageNames.split(",");
		for (String packageName : packages) {
			if (!packagePattern.matcher(packageName).matches()) {
				throw new BootException("Invalid package name " + packageName);
			}
		}
		return packages;
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
