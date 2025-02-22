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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.aot.build.context.ResourceFile;
import org.springframework.aot.build.context.SourceFile;
import org.springframework.nativex.AotOptions;
import org.springframework.nativex.domain.proxies.ProxiesDescriptor;
import org.springframework.nativex.domain.proxies.ProxiesDescriptorJsonMarshaller;
import org.springframework.nativex.domain.reflect.JsonMarshaller;
import org.springframework.nativex.domain.reflect.ReflectionDescriptor;
import org.springframework.nativex.domain.resources.ResourcesDescriptor;
import org.springframework.nativex.domain.resources.ResourcesJsonMarshaller;
import org.springframework.nativex.domain.serialization.SerializationDescriptor;
import org.springframework.nativex.domain.serialization.SerializationDescriptorJsonMarshaller;
import org.springframework.nativex.support.Mode;
import org.springframework.nativex.type.TypeSystem;

/**
 * Generate code for bootstrapping Spring applications in a GraalVM native environment.
 * <p>For that, we are looking up {@link BootstrapContributor}, registered as Spring factories
 * and let them contribute to the bootstrap code.
 *
 * @author Brian Clozel
 * @author Sebastien Deleuze
 */
public class BootstrapCodeGenerator {

	private static final Log logger = LogFactory.getLog(BootstrapCodeGenerator.class);

	private final AotOptions aotOptions;

	private final Set<Pattern> resourcePatternCache = new HashSet<>();

	public BootstrapCodeGenerator(AotOptions aotOptions) {
		this.aotOptions = aotOptions;
	}

	public void generate(AotPhase aotPhase, ApplicationStructure structure) throws IOException {
		logger.debug("Starting code generation with classLoader: " + structure.getClassLoader());
		DefaultBuildContext buildContext = new DefaultBuildContext(structure);
		generate(structure.getSourcesPath(), structure.getResourcesPath(), structure.getResourceFolders(), buildContext, aotPhase);
	}

	/**
	 * Generate bootstrap code for the specified phase.
	 *
	 * @param sourcesPath the root path generated source files should be written to
	 * @param resourcesPath the root path generated resource files should be written to
	 * @param resourceFolders paths to folders containing project main resources
	 * @param buildContext the build context for this application
	 * @param aotPhase the {@link AotPhase} to use
	 * @throws IOException if an I/O error is thrown when opening the resource folders
	 */
	private void generate(Path sourcesPath, Path resourcesPath, Set<Path> resourceFolders, DefaultBuildContext buildContext, AotPhase aotPhase) throws IOException {
		// TODO temporary whilst migrating the inferencing to Aot land
		TypeSystem.setDefaultAotOptions(aotOptions);

		ServiceLoader<BootstrapContributor> contributors = ServiceLoader.load(BootstrapContributor.class);
		for (BootstrapContributor contributor : contributors) {
			if (contributor.supportsAotPhase(aotPhase)) {
				logger.debug("Executing Contributor: " + contributor.getClass().getName());
				contributor.contribute(buildContext, this.aotOptions);
			}
		}

		buildResourcePatternCache(buildContext.getResourcesDescriptor());

		if (!resourceFolders.isEmpty()) {
			logger.debug("Processing resource folders: " + resourceFolders);
			for (Path resourceFolder : resourceFolders) {
				int resourceFolderLen = resourceFolder.toString().length() + 1;
				if (Files.exists(resourceFolder)) {
					Files.walk(resourceFolder).filter(p -> !p.toFile().isDirectory()).forEach(p -> {
						String resourcePattern = p.toString().substring(resourceFolderLen);
						String platformNormalisedResourcePattern = resourcePattern.replace("\\", "/");
						if (!platformNormalisedResourcePattern.startsWith("META-INF/native-image")) {

							if (matchesPatternInCache(platformNormalisedResourcePattern))
								return;

							logger.debug("Resource pattern: " + platformNormalisedResourcePattern);
							// TODO recognize resource bundles?
							// TODO escape the patterns (add leading trailing Q and E sequences...)
							buildContext.describeResources(crd -> crd.add(platformNormalisedResourcePattern));
						}
					});
				}
			}
		}

		logger.debug("Writing generated sources to: " + sourcesPath);
		for (SourceFile sourceFile : buildContext.getSourceFiles()) {
			sourceFile.writeTo(sourcesPath);
		}
		for (ResourceFile resourceFile : buildContext.getResourceFiles()) {
			resourceFile.writeTo(resourcesPath);
		}

		Path graalVMConfigPath = resourcesPath.resolve(ResourceFile.NATIVE_CONFIG_PATH);
		Files.createDirectories(graalVMConfigPath);
		if (this.aotOptions.toMode() == Mode.NATIVE) {
			// reflect-config.json
			ReflectionDescriptor reflectionDescriptor = buildContext.getReflectionDescriptor();
			if (!reflectionDescriptor.isEmpty()) {
				Path reflectConfigPath = graalVMConfigPath.resolve(Paths.get("reflect-config.json"));
				JsonMarshaller.write(reflectionDescriptor, Files.newOutputStream(reflectConfigPath));
			}
			// proxy-config.json
			ProxiesDescriptor proxiesDescriptor = buildContext.getProxiesDescriptor();
			if (!proxiesDescriptor.isEmpty()) {
				Path proxiesConfigPath = graalVMConfigPath.resolve(Paths.get("proxy-config.json"));
				ProxiesDescriptorJsonMarshaller.write(proxiesDescriptor, Files.newOutputStream(proxiesConfigPath));
			}
			// resource-config.json
			ResourcesDescriptor resourcesDescriptor = buildContext.getResourcesDescriptor();
			if (!resourcesDescriptor.isEmpty()) {
				Path resourceConfigPath = graalVMConfigPath.resolve(Paths.get("resource-config.json"));
				ResourcesJsonMarshaller.write(resourcesDescriptor, Files.newOutputStream(resourceConfigPath));
			}
			// serialization-config.json
			SerializationDescriptor serializationDescriptor = buildContext.getSerializationDescriptor();
			if (!serializationDescriptor.isEmpty()) {
				Path serializationConfigPath = graalVMConfigPath.resolve(Paths.get("serialization-config.json"));
				SerializationDescriptorJsonMarshaller.write(serializationDescriptor, Files.newOutputStream(serializationConfigPath));
			}
			// jni-config.json
			ReflectionDescriptor jniReflectionDescriptor = buildContext.getJNIReflectionDescriptor();
			if (!jniReflectionDescriptor.isEmpty()) {
				Path jniReflectionConfigPath = graalVMConfigPath.resolve(Paths.get("jni-config.json"));
				JsonMarshaller.write(jniReflectionDescriptor, Files.newOutputStream(jniReflectionConfigPath));
			}
		}
	}

	/**
	 * Quick optimisation to help us avoid adding entries which are likely already included
	 * e.g. via CommonWebInfos @ResourceHint for "^templates/.*". If a pattern looks like
	 * it's a simple regex to include all contents in a folder, compile and add to a cache
	 * so we can test against it later
	 *
	 * @param resourcesDescriptor the descriptor of resource patterns to potentially cache
	 */
	private void buildResourcePatternCache(ResourcesDescriptor resourcesDescriptor) {
		for (String pattern : resourcesDescriptor.getPatterns()) {
			if (pattern.startsWith("^") && pattern.endsWith(".*")) {
				this.resourcePatternCache.add(Pattern.compile(pattern));
			}
		}
	}

	private boolean matchesPatternInCache(String resourcePattern) {
		for (Pattern patt : this.resourcePatternCache) {
			if (patt.matcher(resourcePattern).matches())
				return true;
		}
		return false;
	}

}
