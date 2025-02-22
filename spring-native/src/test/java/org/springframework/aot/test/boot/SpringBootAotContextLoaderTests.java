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

package org.springframework.aot.test.boot;

import java.util.function.Consumer;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.SpringBootTestContextBootstrapper;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.cache.DefaultCacheAwareContextLoaderDelegate;
import org.springframework.test.context.support.DefaultBootstrapContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SpringBootAotContextLoader}.
 *
 * @author Stephane Nicoll
 */
class SpringBootAotContextLoaderTests {

	@Test
	void loadContextUsesApplicationContextInitializer() {
		SpringBootAotContextLoader loader = new SpringBootAotContextLoader(TestApplicationContextInitializer.class);
		run(() -> loader.loadContext(createMergedContextConfiguration(SampleTest.class)), (context) -> {
			assertThat(context).hasNotFailed().hasBean("testBean").hasSingleBean(String.class);
			assertThat(context.getBean(String.class)).isEqualTo(TestApplicationContextInitializer.TEST_BEAN);
		});
	}

	@Test
	void loadContextUsesApplicationContextInitializerAndWebSettings() {
		SpringBootAotContextLoader loader = new SpringBootAotContextLoader(TestApplicationContextInitializer.class,
				WebApplicationType.SERVLET, WebEnvironment.MOCK);
		run(() -> loader.loadContext(createMergedContextConfiguration(SampleTest.class)), (context) -> {
			assertThat(context).hasNotFailed().hasBean("testBean").hasSingleBean(String.class);
			assertThat(context.getBean(String.class)).isEqualTo(TestApplicationContextInitializer.TEST_BEAN);
		});
	}

	@Test
	void loadContextUseTestProperties() {
		SpringBootAotContextLoader loader = new SpringBootAotContextLoader(TestApplicationContextInitializer.class);
		run(() -> loader.loadContext(createMergedContextConfiguration(SampleTest.class)), (context) -> {
			ConfigurableEnvironment environment = context.getEnvironment();
			assertThat(environment.containsProperty("test.property")).isTrue();
			assertThat(environment.getProperty("test.property")).isEqualTo("42");
		});
	}

	@Test
	void loadContextSetActiveProfiles() {
		SpringBootAotContextLoader loader = new SpringBootAotContextLoader(TestApplicationContextInitializer.class);
		run(() -> loader.loadContext(createMergedContextConfiguration(SampleProfileTest.class)), (context) ->
				assertThat(context.getEnvironment().getActiveProfiles()).containsOnly("profile1", "profile2"));
	}

	private void run(Supplier<ConfigurableApplicationContext> supplier, Consumer<AssertableApplicationContext> context) {
		try (ConfigurableApplicationContext ctx = supplier.get()) {
			context.accept(AssertableApplicationContext.get(() -> ctx));
		}
	}

	private MergedContextConfiguration createMergedContextConfiguration(Class<?> testClass) {
		SpringBootTestContextBootstrapper bootstrapper = new SpringBootTestContextBootstrapper();
		bootstrapper.setBootstrapContext(new DefaultBootstrapContext(testClass, new DefaultCacheAwareContextLoaderDelegate()));
		return bootstrapper.buildMergedContextConfiguration();
	}


	@SpringBootTest(properties = "test.property=42")
	static class SampleTest {

	}

	@DataJpaTest
	@ActiveProfiles({ "profile1", "profile2" })
	static class SampleProfileTest {

	}

	@SpringBootConfiguration
	static class SampleConfiguration {

	}

	static class TestApplicationContextInitializer implements ApplicationContextInitializer<GenericApplicationContext> {

		static String TEST_BEAN = "test";

		@Override
		public void initialize(GenericApplicationContext applicationContext) {
			applicationContext.registerBeanDefinition("testBean", BeanDefinitionBuilder
					.rootBeanDefinition(ResolvableType.forClass(String.class), () -> TEST_BEAN).getBeanDefinition());
		}

	}

}
