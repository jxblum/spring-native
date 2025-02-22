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

package org.springframework.aot.build;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import org.springframework.boot.logging.LogFile;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.nativex.AotOptions;
import org.springframework.util.StringUtils;

@Command(mixinStandardHelpOptions = true,
		description = "Generate the Java source for the Spring Bootstrap class.")
public class GenerateBootstrapCommand implements Callable<Integer> {

	@Option(names = "--main-class", description = "The main class, auto-detected if not provided.")
	private String mainClass;

	@Option(names = "--application-class", description = "The application class, auto-detected if not provided.")
	private String applicationClass;

	@Option(names = "--mode", required = true, description = "The mode which could be native or native-agent")
	private String mode;

	@Option(names = {"--sources-out"}, required = true, description = "Output path for the generated sources.")
	private Path sourceOutputPath;

	@Option(names = {"--resources-out"}, required = true, description = "Output path for the generated resources.")
	private Path resourcesOutputPath;

	@Option(names = {"--classes"}, required = true, split = "${sys:path.separator}", description = "Paths to the application compiled classes.")
	private List<Path> classesPaths;

	@Option(names = {"--resources"}, required = true, split = "${sys:path.separator}", description = "Paths to the application compiled resources.")
	private Set<Path> resourcesPaths;

	@Option(names = {"--debug"}, description = "Enable debug logging.")
	private boolean isDebug;

	@Option(names = {"--remove-yaml"}, description = "Remove Yaml support.")
	private boolean removeYaml;

	@Option(names = {"--remove-jmx"}, description = "Remove JMX support.")
	private boolean removeJmx;

	@Option(names = {"--remove-xml"}, description = "Remove XML support.")
	private boolean removeXml;

	@Option(names = {"--remove-spel"}, description = "Remove SpEL support.")
	private boolean removeSpel;

	@Override
	public Integer call() throws Exception {
		AotOptions aotOptions = new AotOptions();
		aotOptions.setMode(this.mode);
		aotOptions.setDebugVerify(this.isDebug);
		aotOptions.setRemoveYamlSupport(this.removeYaml);
		aotOptions.setRemoveJmxSupport(this.removeJmx);
		aotOptions.setRemoveXmlSupport(this.removeXml);
		aotOptions.setRemoveSpelSupport(this.removeSpel);

		ConfigurableEnvironment environment = new StandardEnvironment();
		LogFile logFile = LogFile.get(environment);
		LoggingInitializationContext initializationContext = new LoggingInitializationContext(environment);
		ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
		LoggingSystem loggingSystem = LoggingSystem.get(classLoader);
		loggingSystem.initialize(initializationContext, null, logFile);
		if (this.isDebug) {
			loggingSystem.setLogLevel(null, LogLevel.DEBUG);
		}

		BootstrapCodeGenerator generator = new BootstrapCodeGenerator(aotOptions);
		String[] classPath = StringUtils.tokenizeToStringArray(System.getProperty("java.class.path"), File.pathSeparator);
		ApplicationStructure applicationStructure = new ApplicationStructure(this.sourceOutputPath, this.resourcesOutputPath, this.resourcesPaths,
				this.classesPaths, this.mainClass, this.applicationClass, Collections.emptyList(), Arrays.asList(classPath), classLoader);
		generator.generate(AotPhase.MAIN, applicationStructure);
		return 0;
	}

	public static void main(String[] args) throws IOException {
		int exitCode = new CommandLine(new GenerateBootstrapCommand()).execute(args);
		System.exit(exitCode);
	}
}
