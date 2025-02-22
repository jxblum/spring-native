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

package org.springframework.aot.context.bootstrap.generator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.util.ClassUtils;

/**
 * A selector that discard {@link BeanDefinition} instances the bootstrap class is
 * replacing.
 *
 * @author Stephane Nicoll
 * @author Sebastien Deleuze
 */
public class DefaultBeanDefinitionSelector implements BeanDefinitionSelector {

	private final List<String> excludeTypes;

	private final Set<String> excludedBeanNames;

	public DefaultBeanDefinitionSelector(List<String> excludeTypes) {
		this.excludeTypes = new ArrayList<>(excludeTypes);
		this.excludedBeanNames = new HashSet<>();
		// Configuration processing is happening at build time.
		this.excludedBeanNames.add(AnnotationConfigUtils.CONFIGURATION_ANNOTATION_PROCESSOR_BEAN_NAME);
		this.excludedBeanNames.add(AnnotationConfigUtils.CONFIGURATION_BEAN_NAME_GENERATOR);

		// Event listener registrations is done at build time
		this.excludedBeanNames.add(AnnotationConfigUtils.EVENT_LISTENER_PROCESSOR_BEAN_NAME);
		this.excludedBeanNames.add(AnnotationConfigUtils.EVENT_LISTENER_FACTORY_BEAN_NAME);

		// Injection points are processed at build time
		this.excludedBeanNames.add(AnnotationConfigUtils.AUTOWIRED_ANNOTATION_PROCESSOR_BEAN_NAME);
		this.excludedBeanNames.add(AnnotationConfigUtils.COMMON_ANNOTATION_PROCESSOR_BEAN_NAME);

		// Only used during configuration class parsing
		this.excludedBeanNames.add("org.springframework.boot.autoconfigure.internalCachingMetadataReaderFactory");
	}

	@Override
	public Boolean select(String beanName, BeanDefinition beanDefinition) {
		String target = ClassUtils.getUserClass(beanDefinition.getResolvableType().toClass()).getName();
		return !this.excludedBeanNames.contains(beanName) && !this.excludeTypes.contains(target);
	}

}
