package com.gitlab.tixtix320.sonder.internal.common.util;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * @author Tigran.Sargsyan on 24-Jan-19
 */
public final class ClassFinder {

	private static final Pattern packagePattern = Pattern.compile(
			"^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)*[0-9a-zA-Z_]$");

	public static List<Class<?>> getPackageClasses(String... packageNames) {
		return Arrays.stream(packageNames)
				.flatMap(packageName -> getPackageClasses(packageName).stream())
				.collect(Collectors.toList());
	}

	public static List<Class<?>> getPackageClasses(String packageName) {
		if (!packagePattern.matcher(packageName).matches()) {
			throw new IllegalArgumentException("Invalid package name " + packageName);
		}

		String packageDirectory = packageName.replace('.', '/');
		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		Predicate<String> classFileFilter = classFileName -> classFileName.contains(packageDirectory);

		return classLoader.resources(packageDirectory)
				.flatMap(url -> getClassFileNamesFromResource(url, classFileFilter).stream())
				.map(ClassFinder::fileNameToClassName)
				.map(ClassFinder::getClassByName)
				.collect(Collectors.toList());
	}

	private static List<String> getClassFileNamesFromResource(URL url, Predicate<String> filter) {
		String resourceName = url.getFile();
		if (url.getProtocol().equals("jar")) {
			int jarExtensionIndex = resourceName.indexOf(".jar!");
			if (jarExtensionIndex == -1) {
				throw new IllegalStateException("wtf");
			}

			String jarPath = resourceName.substring(5, jarExtensionIndex + 4); // truncate file:

			JarFile jar;
			try {
				jar = new JarFile(jarPath);
			}
			catch (IOException e) {
				throw new RuntimeException(e);
			}

			return getClassFileNamesFromJarFile(jar, filter);
		}
		else {
			Path path;
			try {
				path = Path.of(url.toURI());
			}
			catch (URISyntaxException e) {
				throw new IllegalStateException(e);
			}

			return getClassFileNamesOfPath(path);
		}
	}

	private static List<String> getClassFileNamesFromJarFile(JarFile jar, Predicate<String> filter) {
		List<String> classes = new ArrayList<>();

		try {
			Enumeration<JarEntry> en = jar.entries();
			while (en.hasMoreElements()) {
				JarEntry entry = en.nextElement();
				if (entry.getName().endsWith(".class") && filter.test(entry.getName())) {
					classes.add(entry.getName());
				}
			}
		}
		catch (Exception e) {
			throw new RuntimeException("Failed to read classes from jar file: " + jar, e);
		}

		return classes;
	}

	private static List<String> getClassFileNamesOfPath(Path path) {
		List<Path> classFilePaths = getClassFilePathsOf(path);

		if (classFilePaths.size() > 0) {
			Path firstClassFilePath = classFilePaths.get(0);
			int nameCount = firstClassFilePath.getNameCount();
			int currentNameIndex = nameCount - 1;
			while (currentNameIndex != -1) {
				try {
					String classFileName = firstClassFilePath.subpath(currentNameIndex, nameCount).toString();
					String className = fileNameToClassName(classFileName);
					Class.forName(className);
					break;
				}
				catch (ClassNotFoundException ignored) {
					currentNameIndex--;
				}
			}

			if (currentNameIndex == -1) { // not found
				throw new IllegalStateException(String.format("Illegal class file path %s ", firstClassFilePath));
			}

			int classFileStartsPackagePartNameIndex = currentNameIndex;

			return classFilePaths.stream()
					.map(p -> p.subpath(classFileStartsPackagePartNameIndex, p.getNameCount()))
					.map(Path::toString)
					.collect(Collectors.toList());
		}
		else {
			return Collections.emptyList();
		}

	}

	private static List<Path> getClassFilePathsOf(Path path) {
		try {
			return Files.walk(path)
					.filter(p -> Files.isRegularFile(p))
					.filter(p -> p.toString().endsWith(".class"))
					.collect(Collectors.toList());
		}
		catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static String fileNameToClassName(String classFileName) {
		return classFileName.substring(0, classFileName.length() - 6)
				.replace(File.separatorChar, '.')
				.replace('/', '.');
	}

	private static Class<?> getClassByName(String className) {
		try {
			return Class.forName(className);
		}
		catch (ClassNotFoundException e) {
			throw new IllegalStateException(e);
		}
	}
}
