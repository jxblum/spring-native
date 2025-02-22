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

package org.springframework.nativex.hint;

import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Configure reflection or {@code *.class} resources access on specified types. Class references
 * via the {@link #types()} member are the preferred form of use but sometimes due to accessibility restrictions the type names
 * may need to be specified in the {@link #typeNames} member.
 *
 * See {@link #access()} for inferred type of access and how to customize it with {@link AccessBits}.
 * It can be important to limit it to only what is necessary for an optimal compiled image size.
 *
 * @see <a href="https://www.graalvm.org/reference-manual/native-image/Reflection/#manual-configuration">Manual configuration of reflection use in native images</a>
 * @author Andy Clement
 * @author Sebastien Deleuze
 */
@Repeatable(TypeHints.class)
@Retention(RetentionPolicy.RUNTIME)
public @interface TypeHint {

	/**
	 * Preferred (because typesafe) way to specify class references.
	 * @return the types
	 */
	Class<?>[] types() default {};

	/**
	 * Alternative way to specify class references, should be used when type visibility prevents using {@link Class}
	 * references, or for nested types which should be specified using a {@code $} separator (for example
	 * {@code com.example.Foo$Bar}).
	 * @return the type names
	 */
	String[] typeNames() default {};

	/**
	 * Access scope to be configured, See the various predefined ones defined in {@link AccessBits}.
	 *
	 * <p>Inferred when not specified with:
	 * <ul>
	 *     <li>{@link AccessBits#ANNOTATION} for annotations</li>
	 *     <li>{@link AccessBits#INTERFACE} for interfaces</li>
	 *     <li>{@link AccessBits#CLASS} for arrays</li>
	 *     <li>{@link AccessBits#LOAD_AND_CONSTRUCT} for other types</li>
	 * </ul>
	 *
	 * <p>It is possible to combine multiple accesses with bitwise OR operator, for example
	 * {@code access = AccessBits.LOAD_AND_CONSTRUCT | AccessBits.PUBLIC_METHODS}.
	 */
	int access() default 0;

	/**
	 * Specific method information for reflective invocation and metadata query, useful to reduce the footprint impact of the generated configuration.
	 * @return the methods information
	 */
	MethodHint[] methods() default {};

	/**
	 * Specific method information for reflective metadata query, useful to reduce the footprint impact of the generated configuration.
	 * @return the methods information
	 */
	MethodHint[] queriedMethods() default {};

	/**
	 * Specific fields information, useful to reduce the footprint impact of the generated configuration or to specify
	 * unsafe access.
	 * @return the fields information
	 */
	FieldHint[] fields() default {};
}
