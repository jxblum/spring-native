package org.springframework.aot.beans.factory;

import java.io.IOException;
import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import org.springframework.aot.beans.factory.BeanDefinitionRegistrar.InstanceSupplierContext;
import org.springframework.beans.FatalBeanException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ContextAnnotationAutowireCandidateResolver;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.ReflectionUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link BeanDefinitionRegistrar}.
 *
 * @author Stephane Nicoll
 */
class BeanDefinitionRegistrarTests {

	@Test
	void beanDefinitionWithBeanClassDoesNotSetTargetType() {
		RootBeanDefinition beanDefinition = BeanDefinitionRegistrar.of("test", String.class).toBeanDefinition();
		assertThat(beanDefinition.getBeanClass()).isEqualTo(String.class);
		assertThat(beanDefinition.getTargetType()).isNull();
	}

	@Test
	void beanDefinitionWithResolvableTypeSetsTargetType() {
		ResolvableType targetType = ResolvableType.forClassWithGenerics(NumberHolder.class, Integer.class);
		RootBeanDefinition beanDefinition = BeanDefinitionRegistrar.of("test", targetType).toBeanDefinition();
		assertThat(beanDefinition.getTargetType()).isNotNull().isEqualTo(NumberHolder.class);
	}

	@Test
	void registerWithSimpleInstanceSupplier() {
		GenericApplicationContext context = new GenericApplicationContext();
		BeanDefinitionRegistrar.of("test", InjectionSample.class)
				.instanceSupplier(InjectionSample::new).register(context);
		assertContext(context, () -> {
			assertThat(context.containsBean("test")).isTrue();
			assertThat(context.getBean(InjectionSample.class)).isNotNull();
		});
	}

	@Test
	void registerWithSimpleInstanceSupplierThatThrowsRuntimeException() {
		GenericApplicationContext context = new GenericApplicationContext();
		Exception exception = new IllegalArgumentException("test exception");
		BeanDefinitionRegistrar.of("testBean", InjectionSample.class)
				.instanceSupplier(() -> {
					throw exception;
				}).register(context);
		assertThatThrownBy(context::refresh).isInstanceOf(BeanCreationException.class)
				.getRootCause().isEqualTo(exception);
	}

	@Test
	void registerWithSimpleInstanceSupplierThatThrowsCheckedException() {
		GenericApplicationContext context = new GenericApplicationContext();
		Exception exception = new IOException("test exception");
		BeanDefinitionRegistrar.of("testBean", InjectionSample.class)
				.instanceSupplier(() -> {
					throw exception;
				}).register(context);
		assertThatThrownBy(context::refresh).isInstanceOf(BeanCreationException.class)
				.getRootCause().isEqualTo(exception);
	}

	@Test
	void registerWithoutBeanNameFails() {
		GenericApplicationContext context = new GenericApplicationContext();
		BeanDefinitionRegistrar registrar = BeanDefinitionRegistrar.inner(InjectionSample.class)
				.instanceSupplier(InjectionSample::new);
		assertThatIllegalStateException().isThrownBy(() -> registrar.register(context))
				.withMessageContaining("Bean name not set.");
	}

	@Test
	@SuppressWarnings("unchecked")
	void registerWithCustomizer() {
		GenericApplicationContext context = new GenericApplicationContext();
		ThrowableConsumer<RootBeanDefinition> first = mock(ThrowableConsumer.class);
		ThrowableConsumer<RootBeanDefinition> second = mock(ThrowableConsumer.class);
		BeanDefinitionRegistrar.of("test", InjectionSample.class)
				.instanceSupplier(InjectionSample::new).customize(first).customize(second).register(context);
		assertContext(context, () -> {
			assertThat(context.containsBean("test")).isTrue();
			InOrder ordered = inOrder(first, second);
			ordered.verify(first).accept(any(RootBeanDefinition.class));
			ordered.verify(second).accept(any(RootBeanDefinition.class));
		});
	}

	@Test
	void registerWithCustomizerThatThrowsRuntimeException() {
		GenericApplicationContext context = new GenericApplicationContext();
		Exception exception = new RuntimeException("test exception");
		BeanDefinitionRegistrar registrar = BeanDefinitionRegistrar.of("test", InjectionSample.class)
				.instanceSupplier(InjectionSample::new).customize((bd) -> {
					throw exception;
				});
		assertThatThrownBy(() -> registrar.register(context)).isInstanceOf(FatalBeanException.class)
				.hasMessageContaining("Failed to create bean definition for bean with name 'test'")
				.hasMessageContaining("test exception")
				.hasCause(exception);
	}

	@Test
	void registerWithCustomizerThatThrowsCheckedException() {
		GenericApplicationContext context = new GenericApplicationContext();
		Exception exception = new IOException("test exception");
		BeanDefinitionRegistrar registrar = BeanDefinitionRegistrar.of("test", InjectionSample.class)
				.instanceSupplier(InjectionSample::new).customize((bd) -> {
					throw exception;
				});
		assertThatThrownBy(() -> registrar.register(context)).isInstanceOf(FatalBeanException.class)
				.hasMessageContaining("Failed to create bean definition for bean with name 'test'")
				.hasMessageContaining("test exception");
	}

	@Test
	void registerWithConstructorInstantiation() {
		GenericApplicationContext context = new GenericApplicationContext();
		BeanDefinitionRegistrar.of("test", ConstructorSample.class).withConstructor(ResourceLoader.class)
				.instanceSupplier((instanceContext) -> instanceContext.create(context, (attributes) ->
						new ConstructorSample(attributes.get(0)))).register(context);
		assertContext(context, () -> {
			assertThat(context.containsBean("test")).isTrue();
			assertThat(context.getBean(ConstructorSample.class).resourceLoader).isEqualTo(context);
		});
	}

	@Test
	void registerWithConstructorInstantiationThatThrowsRuntimeException() {
		GenericApplicationContext context = new GenericApplicationContext();
		Exception exception = new RuntimeException("test exception");
		BeanDefinitionRegistrar.of("test", ConstructorSample.class).withConstructor(ResourceLoader.class)
				.instanceSupplier((instanceContext) -> {
					throw exception;
				}).register(context);
		assertThatThrownBy(context::refresh).isInstanceOf(BeanCreationException.class)
				.getRootCause().isEqualTo(exception);
	}

	@Test
	void registerWithConstructorInstantiationThatThrowsCheckedException() {
		GenericApplicationContext context = new GenericApplicationContext();
		Exception exception = new IOException("test exception");
		BeanDefinitionRegistrar.of("test", ConstructorSample.class).withConstructor(ResourceLoader.class)
				.instanceSupplier((instanceContext) -> {
					throw exception;
				}).register(context);
		assertThatThrownBy(context::refresh).isInstanceOf(BeanCreationException.class)
				.getRootCause().isEqualTo(exception);
	}

	@Test
	void registerWithInjectedConstructorAndConstructorArgs() {
		GenericApplicationContext context = new GenericApplicationContext();
		context.getDefaultListableBeanFactory().setAutowireCandidateResolver(
				new ContextAnnotationAutowireCandidateResolver());
		context.registerBean("testBean", String.class, "test");
		context.registerBean("anotherBean", String.class, "another");
		BeanDefinitionRegistrar.of("test", MultiArgConstructorSample.class).withConstructor(String.class, Integer.class)
				.instanceSupplier((instanceContext) -> instanceContext.create(context, (attributes) ->
						new MultiArgConstructorSample(attributes.get(0), attributes.get(1))))
				.customize((bd) -> {
					ConstructorArgumentValues constructorArgumentValues = bd.getConstructorArgumentValues();
					constructorArgumentValues.addIndexedArgumentValue(0, new RuntimeBeanReference("anotherBean"));
					constructorArgumentValues.addIndexedArgumentValue(1, 42);
				})
				.register(context);
		assertContext(context, () -> {
			assertThat(context.containsBean("test")).isTrue();
			MultiArgConstructorSample bean = context.getBean(MultiArgConstructorSample.class);
			assertThat(bean.name).isEqualTo("another");
			assertThat(bean.counter).isEqualTo(42);
		});
	}

	@Test
	void registerWithConstructorOnInnerClass() {
		GenericApplicationContext context = new GenericApplicationContext();
		context.registerBean(InnerClassSample.class);
		BeanDefinitionRegistrar.of("test", InnerClassSample.Inner.class).withConstructor(InnerClassSample.class, Environment.class)
				.instanceSupplier((instanceContext) -> instanceContext.create(context, (attributes) ->
						context.getBean(InnerClassSample.class).new Inner(attributes.get(1))))
				.register(context);
		assertContext(context, () -> {
			assertThat(context.containsBean("test")).isTrue();
			InnerClassSample.Inner bean = context.getBean(InnerClassSample.Inner.class);
			assertThat(bean.environment).isEqualTo(context.getEnvironment());
		});
	}

	@Test
	void registerWithInvalidConstructor() {
		assertThatThrownBy(() -> BeanDefinitionRegistrar.of("test", ConstructorSample.class).withConstructor(Object.class))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("No constructor with type(s) [java.lang.Object] found on")
				.hasMessageContaining(ConstructorSample.class.getName());
	}

	@Test
	void registerWithFactoryMethod() {
		GenericApplicationContext context = new GenericApplicationContext();
		BeanDefinitionRegistrar.of("configuration", ConfigurationSample.class).instanceSupplier(ConfigurationSample::new)
				.register(context);
		BeanDefinitionRegistrar.of("test", ConstructorSample.class)
				.withFactoryMethod(ConfigurationSample.class, "sampleBean", ResourceLoader.class)
				.instanceSupplier((instanceContext) -> instanceContext.create(context, (attributes)
						-> context.getBean(ConfigurationSample.class).sampleBean(attributes.get(0))))
				.register(context);
		assertContext(context, () -> {
			assertThat(context.containsBean("configuration")).isTrue();
			assertThat(context.containsBean("test")).isTrue();
			assertThat(context.getBean(ConstructorSample.class).resourceLoader).isEqualTo(context);
			RootBeanDefinition bd = (RootBeanDefinition) context.getBeanDefinition("test");
			assertThat(bd.getResolvedFactoryMethod()).isNotNull().isEqualTo(
					ReflectionUtils.findMethod(ConfigurationSample.class, "sampleBean", ResourceLoader.class));
		});
	}

	@Test
	void registerWithCreateShortcutWithoutFactoryMethod() {
		GenericApplicationContext context = new GenericApplicationContext();
		BeanDefinitionRegistrar.of("configuration", ConfigurationSample.class).instanceSupplier(ConfigurationSample::new)
				.register(context);
		BeanDefinitionRegistrar.of("test", ConstructorSample.class)
				.instanceSupplier((instanceContext) -> instanceContext.create(context, (attributes)
						-> context.getBean(ConfigurationSample.class).sampleBean(attributes.get(0))))
				.register(context);
		assertThatThrownBy(context::refresh).isInstanceOf(BeanCreationException.class)
				.hasMessageContaining("No factory method or constructor is set");
	}

	@Test
	void registerWithInjectedField() {
		GenericApplicationContext context = new GenericApplicationContext();
		BeanDefinitionRegistrar.of("test", InjectionSample.class).instanceSupplier((instanceContext) -> {
			InjectionSample bean = new InjectionSample();
			instanceContext.field("environment", Environment.class).invoke(context,
					(attributes) -> bean.environment = (attributes.get(0)));
			return bean;
		}).register(context);
		assertContext(context, () -> {
			assertThat(context.containsBean("test")).isTrue();
			assertThat(context.getBean(InjectionSample.class).environment).isEqualTo(context.getEnvironment());
		});
	}

	@Test
	void registerWithInvalidField() {
		GenericApplicationContext context = new GenericApplicationContext();
		assertThatThrownBy(() -> {
					BeanDefinitionRegistrar.of("test", InjectionSample.class).instanceSupplier((instanceContext) ->
							instanceContext.field("doesNotExist", Object.class).resolve(context)).register(context);
					context.refresh();
					context.getBean(InjectionSample.class);
				}
		).isInstanceOf(BeanCreationException.class)
				.hasMessageContaining("No field '%s' with type %s found", "doesNotExist", Object.class.getName())
				.hasMessageContaining(InjectionSample.class.getName());
	}

	@Test
	void registerWithInjectedMethod() {
		GenericApplicationContext context = new GenericApplicationContext();
		BeanDefinitionRegistrar.of("test", InjectionSample.class).instanceSupplier((instanceContext) -> {
			InjectionSample bean = new InjectionSample();
			instanceContext.method("setEnvironment", Environment.class).invoke(context,
					(attributes) -> bean.setEnvironment(attributes.get(0)));
			return bean;
		}).register(context);
		assertContext(context, () -> {
			assertThat(context.containsBean("test")).isTrue();
			assertThat(context.getBean(InjectionSample.class).environment).isEqualTo(context.getEnvironment());
		});
	}

	@Test
	void registerWithInvalidMethod() {
		GenericApplicationContext context = new GenericApplicationContext();
		assertThatThrownBy(() -> {
					BeanDefinitionRegistrar.of("test", InjectionSample.class).instanceSupplier((instanceContext) ->
							instanceContext.method("setEnvironment", Object.class).resolve(context)).register(context);
					context.refresh();
					context.getBean(ConstructorSample.class);
				}
		).isInstanceOf(BeanCreationException.class)
				.hasMessageContaining("No method '%s' with type(s) [%s] found", "setEnvironment", Object.class.getName())
				.hasMessageContaining(InjectionSample.class.getName());
	}

	@Test
	void registerWithInjectedMethodHandleAtValue() {
		GenericApplicationContext context = new GenericApplicationContext();
		context.setEnvironment(new MockEnvironment().withProperty("test.counter", "12"));
		BeanDefinitionRegistrar.of("test", InjectionSample.class).instanceSupplier((instanceContext) -> {
			InjectionSample bean = new InjectionSample();
			instanceContext.method("setNameAndCounter", String.class, Integer.class).invoke(context,
					(attributes) -> bean.setNameAndCounter(attributes.get(0), attributes.get(1)));
			return bean;
		}).register(context);
		assertContext(context, () -> {
			assertThat(context.containsBean("test")).isTrue();
			InjectionSample bean = context.getBean(InjectionSample.class);
			assertThat(bean.name).isEqualTo("test");
			assertThat(bean.counter).isEqualTo(12);
		});
	}

	@Test
	void innerBeanDefinitionWithClass() {
		RootBeanDefinition beanDefinition = BeanDefinitionRegistrar.inner(ConfigurationSample.class)
				.customize((bd) -> bd.setSynthetic(true)).toBeanDefinition();
		assertThat(beanDefinition).isNotNull();
		assertThat(beanDefinition.getResolvableType().resolve()).isEqualTo(ConfigurationSample.class);
		assertThat(beanDefinition.isSynthetic()).isTrue();
	}

	@Test
	void innerBeanDefinitionWithResolvableType() {
		RootBeanDefinition beanDefinition = BeanDefinitionRegistrar.inner(ResolvableType.forClass(ConfigurationSample.class))
				.customize((bd) -> bd.setDescription("test")).toBeanDefinition();
		assertThat(beanDefinition).isNotNull();
		assertThat(beanDefinition.getResolvableType().resolve()).isEqualTo(ConfigurationSample.class);
		assertThat(beanDefinition.getDescription()).isEqualTo("test");
	}

	@Test
	void innerBeanDefinitionHasInnerBeanNameInInstanceSupplier() {
		RootBeanDefinition beanDefinition = BeanDefinitionRegistrar.inner(String.class)
				.instanceSupplier((instanceContext) -> {
					Field field = ReflectionUtils.findField(InstanceSupplierContext.class, "beanName", String.class);
					ReflectionUtils.makeAccessible(field);
					return ReflectionUtils.getField(field, instanceContext);
				}).toBeanDefinition();
		assertThat(beanDefinition).isNotNull();
		String beanName = (String) beanDefinition.getInstanceSupplier().get();
		assertThat(beanName).isNotNull().startsWith("(inner bean)#");
	}

	@Test
	void beanFactoryWithUnresolvedGenericCanBeInjected() {
		GenericApplicationContext context = new AnnotationConfigApplicationContext();
		// See https://github.com/spring-projects/spring-framework/issues/27727
		context.registerBean(NumberHolderSample.class);
		BeanDefinitionRegistrar.of("factory", FactoryBean.class)
				.withFactoryMethod(GenericFactoryBeanConfiguration.class, "integerHolderFactory")
				.instanceSupplier(() -> new GenericFactoryBeanConfiguration().integerHolderFactory())
				.register(context);
		assertContext(context, () -> {
			NumberHolder<Integer> numberHolder = context.getBean(NumberHolderSample.class).numberHolder;
			assertThat(numberHolder).isNotNull();
			assertThat(numberHolder.number).isEqualTo(42);
		});
	}

	@Test
	void beanWithUnresolvedGenericCanBeInjected() {
		GenericApplicationContext context = new AnnotationConfigApplicationContext();
		// See https://github.com/spring-projects/spring-framework/issues/27727
		context.registerBean(NumberHolderSample.class);
		BeanDefinitionRegistrar.of("numberHolder", NumberHolder.class)
				.withFactoryMethod(GenericFactoryBeanConfiguration.class, "integerHolder")
				.instanceSupplier(() -> new GenericFactoryBeanConfiguration().integerHolder())
				.register(context);
		assertContext(context, () -> {
			NumberHolder<Integer> numberHolder = context.getBean(NumberHolderSample.class).numberHolder;
			assertThat(numberHolder).isNotNull();
			assertThat(numberHolder.number).isEqualTo(42);
		});
	}

	private void assertContext(GenericApplicationContext context, Runnable assertions) {
		context.getDefaultListableBeanFactory().setAutowireCandidateResolver(new ContextAnnotationAutowireCandidateResolver());
		if (!context.isRunning()) {
			context.refresh();
		}
		try (context) {
			assertions.run();
		}
	}

	static class ConfigurationSample {

		ConstructorSample sampleBean(ResourceLoader resourceLoader) {
			return new ConstructorSample(resourceLoader);
		}

	}

	static class ConstructorSample {
		private final ResourceLoader resourceLoader;

		ConstructorSample(ResourceLoader resourceLoader) {
			this.resourceLoader = resourceLoader;
		}
	}

	static class MultiArgConstructorSample {

		private final String name;

		private final Integer counter;

		public MultiArgConstructorSample(String name, Integer counter) {
			this.name = name;
			this.counter = counter;
		}

	}

	static class InjectionSample {

		private Environment environment;

		private String name;

		private Integer counter;

		void setEnvironment(Environment environment) {
			this.environment = environment;
		}

		void setNameAndCounter(@Value("${test.name:test}") String name, @Value("${test.counter:42}") Integer counter) {
			this.name = name;
			this.counter = counter;
		}

	}

	static class InnerClassSample {

		class Inner {

			private Environment environment;

			Inner(Environment environment) {
				this.environment = environment;
			}

		}

	}

	static class GenericFactoryBeanConfiguration {

		FactoryBean<NumberHolder<?>> integerHolderFactory() {
			return new GenericFactoryBean<>(integerHolder());
		}

		NumberHolder<?> integerHolder() {
			return new NumberHolder<>(42);
		}

	}

	static class GenericFactoryBean<T> implements FactoryBean<T> {

		private final T value;

		public GenericFactoryBean(T value) {
			this.value = value;
		}

		@Override
		public T getObject() {
			return this.value;
		}

		@Override
		public Class<?> getObjectType() {
			return this.value.getClass();
		}
	}

	static class NumberHolder<N extends Number> {

		private final N number;

		public NumberHolder(N number) {
			this.number = number;
		}

	}

	static class NumberHolderSample {

		@Autowired
		private NumberHolder<Integer> numberHolder;

	}

}
