/*
 * Copyright 2019-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.aot.gradle;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;

import org.graalvm.buildtools.gradle.NativeImagePlugin;
import org.graalvm.buildtools.gradle.dsl.GraalVMExtension;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.file.DuplicatesStrategy;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFile;
import org.gradle.api.plugins.BasePlugin;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginConvention;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.testing.Test;
import org.gradle.jvm.toolchain.JavaToolchainService;
import org.gradle.jvm.toolchain.JavaToolchainSpec;
import org.gradle.util.GradleVersion;

import org.springframework.aot.build.BootstrapCodeGenerator;
import org.springframework.aot.gradle.dsl.SpringAotExtension;
import org.springframework.aot.gradle.tasks.GenerateAotSources;
import org.springframework.aot.gradle.tasks.GenerateAotTestSources;
import org.springframework.aot.test.build.GenerateTestBootstrapCommand;
import org.springframework.boot.gradle.plugin.SpringBootPlugin;
import org.springframework.boot.gradle.tasks.bundling.BootJar;
import org.springframework.boot.gradle.tasks.run.BootRun;
import org.springframework.nativex.utils.VersionExtractor;
import org.springframework.util.FileSystemUtils;

/**
 * {@link Plugin} that generates AOT sources using {@code spring-native-aot} and compiles them.
 *
 * @author Brian Clozel
 * @author Andy Wilkinson
 * @author Sam Brannen
 */
public class SpringAotGradlePlugin implements Plugin<Project> {

	public static final String DEBUG_SYSTEM_PROPERTY = "spring.aot.debug";

	public static final String DEBUG_PORT_SYSTEM_PROPERTY = "spring.aot.debug.port";

	public static final String EXTENSION_NAME = "springAot";

	public static final String AOT_MAIN_CONFIGURATION_NAME = "aotMain";

	public static final String AOT_MAIN_SOURCE_SET_NAME = "aotMain";

	public static final String AOT_TEST_CONFIGURATION_NAME = "aotTest";

	public static final String AOT_TEST_SOURCE_SET_NAME = "aotTest";

	public static final String GENERATE_MAIN_TASK_NAME = "generateAot";

	public static final String GENERATE_TEST_TASK_NAME = "generateTestAot";


	@Override
	public void apply(final Project project) {
		project.getPluginManager().apply(NativeImagePlugin.class);

		project.getPlugins().withType(JavaPlugin.class, javaPlugin -> {
			GraalVMExtension graal = project.getExtensions().findByType(GraalVMExtension.class);

			// Create the "springAot" DSL extension for user configuration
			project.getExtensions().create(EXTENSION_NAME, SpringAotExtension.class, project.getObjects());

			// Automatically add the spring-native dependency to the implementation configuration
			// as it's required for hints
			addSpringNativeDependency(project);
			Path generatedFilesPath = createGeneratedSourcesFolder(project);

			// deprecation replaced with new API introduced in Gradle 7.1
			//noinspection deprecation
			SourceSetContainer sourceSets = project.getConvention().getPlugin(JavaPluginConvention.class).getSourceSets();

			// Create a detached configuration that holds dependencies for AOT generation
			SourceSet aotMainSourceSet = createAotMainSourceSet(sourceSets, generatedFilesPath);
			GenerateAotSources generateAotSources = createGenerateAotSourcesTask(project, sourceSets);
			project.getTasks().named(aotMainSourceSet.getCompileJavaTaskName(), JavaCompile.class, (aotCompileJava) -> {
				aotCompileJava.source(generateAotSources.getSourcesOutputDirectory());
			});
			project.getTasks().named(aotMainSourceSet.getProcessResourcesTaskName(), Copy.class, (aotProcessResources) -> {
				aotProcessResources.from(generateAotSources.getResourcesOutputDirectory());
				aotProcessResources.setDuplicatesStrategy(DuplicatesStrategy.INCLUDE);
			});
			Jar generatedSourcesJar = project.getTasks().create(aotMainSourceSet.getJarTaskName(), Jar.class, jar -> {
				jar.from(aotMainSourceSet.getOutput());
				jar.setGroup(BasePlugin.BUILD_GROUP);
				jar.getArchiveClassifier().convention("aot");
			});
			createAotMainConfiguration(project, aotMainSourceSet);

			// Generated+compiled sources must be used by 'bootRun' and packaged by 'bootJar'
			project.getPlugins().withType(SpringBootPlugin.class, springBootPlugin -> {
				Provider<RegularFile> generatedSources = generatedSourcesJar.getArchiveFile();
				project.getTasks().named(SpringBootPlugin.BOOT_JAR_TASK_NAME, BootJar.class, (bootJar) ->
						bootJar.classpath(generatedSources));
				project.getTasks().named("bootRun", BootRun.class, (bootRun) ->
						bootRun.classpath(generatedSources));
			});

			// Create a detached configuration that holds dependencies for AOT test generation
			SourceSet aotTestSourceSet = createAotTestSourceSet(sourceSets, generatedFilesPath);
			GenerateAotTestSources generateAotTestSources = createGenerateAotTestSourcesTask(project, sourceSets);
			project.getTasks().named(aotTestSourceSet.getCompileJavaTaskName(), JavaCompile.class, (aotTestCompileJava) -> {
				aotTestCompileJava.source(generateAotTestSources.getGeneratedSourcesOutputDirectory());
			});
			project.getTasks().named(aotTestSourceSet.getProcessResourcesTaskName(), Copy.class, (aotTestProcessResources) -> {
				aotTestProcessResources.from(generateAotTestSources.getGeneratedResourcesOutputDirectory());
				aotTestProcessResources.setDuplicatesStrategy(DuplicatesStrategy.INCLUDE);
			});
			Jar generatedTestSourcesJar = project.getTasks().create(aotTestSourceSet.getJarTaskName(), Jar.class, jar -> {
				jar.from(aotTestSourceSet.getOutput());
				jar.setGroup(JavaBasePlugin.VERIFICATION_GROUP);
				jar.getArchiveClassifier().convention("aot-test");
			});

			project.getTasks().withType(Test.class)
					.configureEach(test -> {
						// Prepend the generatedTestSourcesJar to the classpath so that generated code
						// overrides any types already in the classpath -- for example, the standard
						// SpringFactoriesLoader implementation in spring-core must be overridden by
						// the SpringFactoriesLoader implementation that uses StaticSpringFactories.
						FileCollection classpath = project.files(project.files(generatedTestSourcesJar.getArchiveFile()),
								test.getClasspath());
						test.setClasspath(classpath);
						test.systemProperty("spring.test.context.default.CacheAwareContextLoaderDelegate",
								"org.springframework.aot.test.AotCacheAwareContextLoaderDelegate");
					});

			project.getPlugins().withType(NativeImagePlugin.class, nativeImagePlugin -> {
				project.getTasks().named(NativeImagePlugin.NATIVE_COMPILE_TASK_NAME).configure(nativeCompile -> {
					nativeCompile.dependsOn(generatedSourcesJar);
				});
				graal.getBinaries().named(NativeImagePlugin.NATIVE_MAIN_EXTENSION).configure(options -> {
					 Provider<RegularFile> generatedSources = generatedSourcesJar.getArchiveFile();
					 options.classpath(generatedSources);
				});
				graal.getBinaries().named(NativeImagePlugin.NATIVE_TEST_EXTENSION).configure(options -> {
					Provider<RegularFile> generatedTestSources = generatedTestSourcesJar.getArchiveFile();
					options.classpath(generatedTestSources);
					options.runtimeArgs("-Dspring.test.context.default.CacheAwareContextLoaderDelegate=" +
							"org.springframework.aot.test.AotCacheAwareContextLoaderDelegate");
				});
			});

			// Ensure that Kotlin compilation depends on AOT source generation
			project.getPlugins().withId("org.jetbrains.kotlin.jvm", kotlinPlugin -> {
				project.getTasks().named("compileAotMainKotlin")
						.configure(compileKotlin -> compileKotlin.dependsOn(generateAotSources));
			});
		});
	}

	/**
	 * Add the spring-native dependency as 'implementation' dependency.
	 * This library contains annotations and required native substitutions.
	 */
	private void addSpringNativeDependency(Project project) {
		String springNativeVersion = VersionExtractor.forClass(BootstrapCodeGenerator.class);
		if (springNativeVersion != null) {
			project.getDependencies().add(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME,
					"org.springframework.experimental:spring-native:" + springNativeVersion);
		}
	}

	/**
	 * Recreate a folder for generated sources and resources, deleting files
	 * possibly contributed in previous builds.
	 */
	private Path createGeneratedSourcesFolder(Project project) {
		String buildPath = project.getBuildDir().getAbsolutePath();
		try {
			Path generatedSourcesFolder = Paths.get(buildPath, "generated");
			FileSystemUtils.deleteRecursively(generatedSourcesFolder);
			return Files.createDirectories(generatedSourcesFolder);
		}
		catch (IOException exc) {
			throw new GradleException("Failed to recreate folder '" + buildPath + "/generated/'", exc);
		}
	}

	private SourceSet createAotMainSourceSet(SourceSetContainer sourceSets, Path generatedFilesPath) {
		File aotSourcesDirectory = generatedFilesPath.resolve("sources").resolve(AOT_MAIN_SOURCE_SET_NAME).toFile();
		File aotResourcesDirectory = generatedFilesPath.resolve("resources").resolve(AOT_MAIN_SOURCE_SET_NAME).toFile();
		SourceSet aotMainSourceSet = sourceSets.create(AOT_MAIN_SOURCE_SET_NAME);
		aotMainSourceSet.setCompileClasspath(sourceSets.findByName(SourceSet.MAIN_SOURCE_SET_NAME).getRuntimeClasspath());
		aotMainSourceSet.getJava().setSrcDirs(Collections.singletonList(aotSourcesDirectory));
		aotMainSourceSet.getResources().setSrcDirs(Collections.singletonList(aotResourcesDirectory));
		return aotMainSourceSet;
	}


	private Configuration createAotMainConfiguration(Project project, SourceSet aotSourceSet) {
		Configuration aotConfiguration = project.getConfigurations().create(AOT_MAIN_CONFIGURATION_NAME);
		aotConfiguration.setCanBeConsumed(true);
		aotConfiguration.setCanBeResolved(true);
		TaskProvider<Jar> jarTask = project.getTasks().named(aotSourceSet.getJarTaskName(), Jar.class);
		aotConfiguration.getOutgoing().artifact(jarTask.get().getArchiveFile(), artifact -> artifact.builtBy(jarTask));
		return aotConfiguration;
	}

	private GenerateAotSources createGenerateAotSourcesTask(Project project, SourceSetContainer sourceSets) {
		SourceSet mainSourceSet = sourceSets.findByName(SourceSet.MAIN_SOURCE_SET_NAME);
		SourceSet aotSourceSet = sourceSets.findByName(AOT_MAIN_SOURCE_SET_NAME);
		GenerateAotSources generate = project.getTasks().create(GENERATE_MAIN_TASK_NAME, GenerateAotSources.class);
		generate.setMainSourceSetOutputDirectories(mainSourceSet.getOutput());
		generate.setClasspath(createAotGenerationConfiguration(project).plus(aotSourceSet.getCompileClasspath()));
		generate.setResourceInputDirectories(mainSourceSet.getResources());
		generate.getSourcesOutputDirectory().set(aotSourceSet.getJava().getSourceDirectories().getSingleFile());
		generate.getResourcesOutputDirectory().set(aotSourceSet.getResources().getSourceDirectories().getSingleFile());
		generate.setDebug(isDebug());
		generate.getDebugOptions().getPort().set(getDebugPort());
		generate.setGroup(BasePlugin.BUILD_GROUP);
		configureToolchainConvention(project, generate);
		return generate;
	}

	/**
	 * Create a detached configuration that holds the required dependencies for running
	 * the AOT source generation process.
	 */
	private Configuration createAotGenerationConfiguration(Project project) {
		Configuration detachedConfiguration = project.getConfigurations().detachedConfiguration();
		String springNativeVersion = VersionExtractor.forClass(BootstrapCodeGenerator.class);
		Dependency nativeConfigDependency = project.getDependencies().create("org.springframework.experimental:spring-native-configuration:" + springNativeVersion);
		Dependency aotDependency = project.getDependencies().create("org.springframework.experimental:spring-aot:" + springNativeVersion);
		detachedConfiguration.getDependencies().add(nativeConfigDependency);
		detachedConfiguration.getDependencies().add(aotDependency);
		return detachedConfiguration;
	}

	private SourceSet createAotTestSourceSet(SourceSetContainer sourceSets, Path generatedFilesPath) {
		File aotTestSourcesDirectory = generatedFilesPath.resolve("sources").resolve(AOT_TEST_SOURCE_SET_NAME).toFile();
		File aotTestResourcesDirectory = generatedFilesPath.resolve("resources").resolve(AOT_TEST_SOURCE_SET_NAME).toFile();
		SourceSet aotTestSourceSet = sourceSets.create(AOT_TEST_SOURCE_SET_NAME);
		aotTestSourceSet.setCompileClasspath(sourceSets.findByName(SourceSet.TEST_SOURCE_SET_NAME).getRuntimeClasspath());
		aotTestSourceSet.getJava().setSrcDirs(Collections.singletonList(aotTestSourcesDirectory));
		aotTestSourceSet.getResources().setSrcDirs(Collections.singletonList(aotTestResourcesDirectory));
		return aotTestSourceSet;
	}

	private GenerateAotTestSources createGenerateAotTestSourcesTask(Project project, SourceSetContainer sourceSets) {
		SourceSet testSourceSet = sourceSets.findByName(SourceSet.TEST_SOURCE_SET_NAME);
		SourceSet aotTestSourceSet = sourceSets.findByName(AOT_TEST_SOURCE_SET_NAME);
		GenerateAotTestSources generate = project.getTasks().create(GENERATE_TEST_TASK_NAME, GenerateAotTestSources.class);
		generate.setTestSourceSetOutputDirectories(testSourceSet.getOutput());
		generate.getGeneratedSourcesOutputDirectory().set(aotTestSourceSet.getJava().getSourceDirectories().getSingleFile());
		generate.getGeneratedResourcesOutputDirectory().set(aotTestSourceSet.getResources().getSourceDirectories().getSingleFile());
		generate.setClasspath(createAotTestGenerationConfiguration(project).plus(aotTestSourceSet.getCompileClasspath()));
		generate.setResourceInputDirectories(testSourceSet.getResources());
		generate.setDebug(isDebug());
		generate.getDebugOptions().getPort().set(getDebugPort());
		generate.setGroup(JavaBasePlugin.VERIFICATION_GROUP);
		configureToolchainConvention(project, generate);
		return generate;
	}

	/**
	 * Create a detached configuration that holds the required dependencies for running
	 * the AOT test source generation process.
	 */
	private Configuration createAotTestGenerationConfiguration(Project project) {
		Configuration detachedConfiguration = project.getConfigurations().detachedConfiguration();
		String springNativeVersion = VersionExtractor.forClass(GenerateTestBootstrapCommand.class);
		Dependency nativeConfigDependency = project.getDependencies().create("org.springframework.experimental:spring-native-configuration:" + springNativeVersion);
		Dependency aotDependency = project.getDependencies().create("org.springframework.experimental:spring-aot:" + springNativeVersion);
		Dependency aotTestDependency = project.getDependencies().create("org.springframework.experimental:spring-aot-test:" + springNativeVersion);
		detachedConfiguration.getDependencies().addAll(Arrays.asList(nativeConfigDependency, aotDependency, aotTestDependency));
		return detachedConfiguration;
	}

	private boolean isDebug() {
		return Boolean.parseBoolean(System.getProperty(DEBUG_SYSTEM_PROPERTY));
	}

	private Integer getDebugPort() {
		try {
			return Integer.parseInt(System.getProperty(DEBUG_PORT_SYSTEM_PROPERTY));
		}
		catch (NumberFormatException exc) {
			return 5005;
		}
	}

	private void configureToolchainConvention(Project project, JavaExec generateTask) {
		if (isGradle67OrLater()) {
			JavaToolchainSpec toolchain = project.getExtensions().getByType(JavaPluginExtension.class).getToolchain();
			JavaToolchainService toolchainService = project.getExtensions().getByType(JavaToolchainService.class);
			generateTask.getJavaLauncher().convention(toolchainService.launcherFor(toolchain));
		}
	}

	private boolean isGradle67OrLater() {
		return GradleVersion.current().getBaseVersion().compareTo(GradleVersion.version("6.7")) >= 0;
	}

}
