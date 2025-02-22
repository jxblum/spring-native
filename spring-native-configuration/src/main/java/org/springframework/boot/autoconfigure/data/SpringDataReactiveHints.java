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

package org.springframework.boot.autoconfigure.data;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.data.repository.core.support.ReactiveRepositoryFactorySupport;
import org.springframework.data.repository.query.ReactiveQueryByExampleExecutor;
import org.springframework.nativex.hint.AccessBits;
import org.springframework.nativex.hint.TypeHint;


@TypeHint(types = { Flux.class, Mono.class }, access = AccessBits.CLASS)
@TypeHint(types = ReactiveRepositoryFactorySupport.class)
@TypeHint(types = ReactiveQueryByExampleExecutor.class, access = AccessBits.LOAD_AND_CONSTRUCT_AND_PUBLIC_METHODS)
	public class SpringDataReactiveHints {
}
