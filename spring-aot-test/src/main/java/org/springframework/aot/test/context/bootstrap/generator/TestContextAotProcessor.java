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

package org.springframework.aot.test.context.bootstrap.generator;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import javax.lang.model.element.Modifier;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.CodeBlock.Builder;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;

import org.springframework.aot.context.bootstrap.generator.ApplicationContextAotProcessor;
import org.springframework.aot.context.bootstrap.generator.infrastructure.BootstrapClass;
import org.springframework.aot.context.bootstrap.generator.infrastructure.BootstrapWriterContext;
import org.springframework.aot.context.bootstrap.generator.infrastructure.nativex.NativeConfigurationRegistry;
import org.springframework.aot.test.context.bootstrap.generator.nativex.TestNativeConfigurationRegistrar;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.context.SmartContextLoader;

/**
 * A decorator of {@link ApplicationContextAotProcessor} that handles test contexts.
 *
 * @author Stephane Nicoll
 */
public class TestContextAotProcessor {

	private static final String TEST_BOOTSTRAP_CLASS_NAME = "TestContextBootstrapInitializer";

	private final TestContextConfigurationDescriptorFactory configurationDescriptorFactory;

	private final ApplicationContextAotProcessor contextProcessor;

	private final TestNativeConfigurationRegistrar testNativeConfigurationRegistrar;

	public TestContextAotProcessor(ClassLoader classLoader) {
		this.configurationDescriptorFactory = new TestContextConfigurationDescriptorFactory(classLoader);
		this.contextProcessor = new ApplicationContextAotProcessor(classLoader);
		this.testNativeConfigurationRegistrar = new TestNativeConfigurationRegistrar(classLoader);
	}

	/**
	 * Generate the test contexts for the specified {@code testClasses} and the mapping
	 * between these test classes and their {@link ApplicationContextInitializer}.
	 * @param testClasses the test classes to handle
	 * @param writerContext the writer context to use
	 */
	public void generateTestContexts(Iterable<Class<?>> testClasses, BootstrapWriterContext writerContext) {
		NativeConfigurationRegistry nativeConfigurationRegistry = writerContext.getNativeConfigurationRegistry();
		List<TestContextConfigurationDescriptor> descriptors = this.configurationDescriptorFactory.buildConfigurationDescriptors(testClasses);
		AtomicInteger count = new AtomicInteger();
		Supplier<String> fallbackClassName = () -> TEST_BOOTSTRAP_CLASS_NAME + count.getAndIncrement();
		Map<ClassName, TestContextConfigurationDescriptor> entries = new HashMap<>();
		for (TestContextConfigurationDescriptor descriptor : descriptors) {
			ClassName className = generateTestContext(writerContext, fallbackClassName, descriptor);
			descriptor.contributeNativeConfiguration(nativeConfigurationRegistry);
			nativeConfigurationRegistry.reflection().forGeneratedType(className)
					.withMethods(MethodSpec.constructorBuilder().build());
			entries.put(className, descriptor);
		}
		generateContextLoadersMapping(writerContext, entries);

		this.testNativeConfigurationRegistrar.processTestConfigurations(nativeConfigurationRegistry,
				entries.values().stream().map(TestContextConfigurationDescriptor::getContextConfiguration).collect(Collectors.toList()));
	}

	private void generateContextLoadersMapping(BootstrapWriterContext writerContext, Map<ClassName, TestContextConfigurationDescriptor> entries) {
		BootstrapWriterContext mainWriterContext = writerContext.fork(
				TEST_BOOTSTRAP_CLASS_NAME, (packageName) -> {
					ClassName mainClassName = ClassName.get(packageName, TEST_BOOTSTRAP_CLASS_NAME);
					return BootstrapClass.of(mainClassName, (type) -> type.addModifiers(Modifier.PUBLIC));
				});
		MethodSpec method = generateContextLoadersMappingMethod(entries);
		BootstrapClass boostrapClass = mainWriterContext.getMainBootstrapClass();
		boostrapClass.addMethod(method);
		writerContext.getNativeConfigurationRegistry().reflection()
				.forGeneratedType(boostrapClass.getClassName()).withMethods(method);
	}

	protected ClassName generateTestContext(BootstrapWriterContext writerContext, Supplier<String> fallbackClassName,
			TestContextConfigurationDescriptor descriptor) {
		GenericApplicationContext context = descriptor.parseTestContext();
		String className = determineClassName(descriptor.getTestClasses(), fallbackClassName);
		BootstrapWriterContext testWriterContext = writerContext.fork(className);
		this.contextProcessor.process(context, testWriterContext);
		BootstrapClass mainBootstrapClass = testWriterContext.getMainBootstrapClass();
		mainBootstrapClass.customizeType((type) -> type.addJavadoc(getClassLevelJavadoc(descriptor.getTestClasses())));
		return mainBootstrapClass.getClassName();
	}

	private MethodSpec generateContextLoadersMappingMethod(Map<ClassName, TestContextConfigurationDescriptor> entries) {
		Builder code = CodeBlock.builder();
		TypeName mapType = ParameterizedTypeName.get(ClassName.get(Map.class),
				ClassName.get(String.class), ParameterizedTypeName.get(Supplier.class, SmartContextLoader.class));
		code.addStatement("$T entries = new $T<>()", mapType, HashMap.class);
		entries.forEach((className, descriptor) ->
				descriptor.getTestClasses().forEach((testClass) -> {
					code.add("entries.put($S, ", testClass.getName());
					code.add(descriptor.writeTestContextLoaderInstanceSupplier(className));
					code.addStatement(")");
				}));
		code.addStatement("return entries");
		return MethodSpec.methodBuilder("getContextLoaders").returns(mapType)
				.addModifiers(Modifier.PUBLIC, Modifier.STATIC).addCode(code.build()).build();
	}

	private CodeBlock getClassLevelJavadoc(List<Class<?>> testClasses) {
		Builder code = CodeBlock.builder();
		code.add("AOT generated context for ");
		List<String> testClassNames = testClasses.stream().map(
						(testClass) -> String.format("{@code %s}", testClass.getSimpleName()))
				.collect(Collectors.toList());
		if (testClasses.size() == 1) {
			code.add(testClassNames.get(0));
		}
		else {
			int last = testClassNames.size() - 1;
			String lastSeparator = testClassNames.size() == 2 ? " and " : ", and ";
			code.add(String.join(lastSeparator,
					String.join(", ", testClassNames.subList(0, last)), testClassNames.get(last)));
		}
		code.add(".");
		return code.build();
	}

	private String determineClassName(List<Class<?>> testClasses, Supplier<String> fallback) {
		return (testClasses.size() == 1)
				? String.format("%sContextInitializer", testClasses.get(0).getSimpleName())
				: fallback.get();
	}
}
