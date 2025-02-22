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

package org.springframework.aot.maven;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.toolchain.Toolchain;
import org.apache.maven.toolchain.ToolchainManager;
import org.sonatype.plexus.build.incremental.BuildContext;

import org.springframework.boot.loader.tools.JavaExecutable;
import org.springframework.boot.loader.tools.RunProcess;
import org.springframework.util.FileSystemUtils;

/**
 * @author Brian Clozel
 */
abstract class AbstractBootstrapMojo extends AbstractMojo {

	static final String DEFAULT_COMPILER_PLUGIN_VERSION = "3.8.1";

	@Parameter(defaultValue = "${project}", readonly = true, required = true)
	protected MavenProject project;

	@Parameter(defaultValue = "${session}", readonly = true, required = true)
	protected MavenSession session;

	@Component
	protected BuildContext buildContext;

	@Component
	protected BuildPluginManager pluginManager;

	@Parameter(defaultValue = "${plugin.artifacts}")
	protected List<Artifact> pluginArtifacts;

	@Component
	private ToolchainManager toolchainManager;

	@Parameter(defaultValue = "${project.build.directory}")
	protected File buildDir;

	@Parameter(property = "spring.aot.debug")
	protected String debug;

	protected static String asClasspathArgument(List<String> elements) {
		StringBuilder classpath = new StringBuilder();
		for (String element : elements) {
			if (classpath.length() > 0) {
				classpath.append(File.pathSeparator);
			}
			classpath.append(element);
		}
		return classpath.toString();
	}

	protected static Optional<Artifact> findJarFile(List<Artifact> artifacts, String groupId, String artifactId) {
		return artifacts.stream()
				.filter(artifact -> artifact.getGroupId().equals(groupId)
						&& artifact.getArtifactId().equals(artifactId))
				.findFirst();
	}

	protected static void prependDependency(Artifact artifact, List<String> classpathElements) {
		classpathElements.add(1, artifact.getFile().getAbsolutePath());
	}

	protected String getLogLevel() {
		if (getLog().isDebugEnabled()) {
			return "DEBUG";
		}
		else if (getLog().isInfoEnabled()) {
			return "INFO";
		}
		else if (getLog().isWarnEnabled()) {
			return "WARN";
		}
		else {
			return "ERROR";
		}
	}

	protected void recreateGeneratedSourcesFolder(File generatedSourcesFolder) throws MojoFailureException {
		try {
			FileSystemUtils.deleteRecursively(generatedSourcesFolder);
			Files.createDirectories(generatedSourcesFolder.toPath());
		}
		catch (IOException exc) {
			throw new MojoFailureException("Build failed during Spring AOT code generation", exc);
		}
	}

	protected void forkJvm(File workingDirectory, List<String> args, Map<String, String> environmentVariables)
			throws MojoExecutionException {
		try {
			RunProcess runProcess = new RunProcess(workingDirectory, getJavaExecutable());
			Runtime.getRuntime().addShutdownHook(new Thread(new RunProcessKiller(runProcess)));
			int exitCode = runProcess.run(true, args, environmentVariables);
			if (exitCode == 0 || exitCode == 130) {
				return;
			}
			throw new MojoExecutionException("Application finished with exit code: " + exitCode);
		}
		catch (Exception ex) {
			throw new MojoExecutionException("Could not exec java", ex);
		}
	}

	protected String getJavaExecutable() {
		Toolchain toolchain = this.toolchainManager.getToolchainFromBuildContext("jdk", this.session);
		String javaExecutable = (toolchain != null) ? toolchain.findTool("java") : null;
		return (javaExecutable != null) ? javaExecutable : new JavaExecutable().toString();
	}
	
	protected static final class RunProcessKiller implements Runnable {

		private final RunProcess runProcess;

		RunProcessKiller(RunProcess runProcess) {
			this.runProcess = runProcess;
		}

		@Override
		public void run() {
			this.runProcess.kill();
		}

	}

}
