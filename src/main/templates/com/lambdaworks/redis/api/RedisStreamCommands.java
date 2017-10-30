/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lambdaworks.redis.api;

import java.util.List;
import java.util.Map;

import com.lambdaworks.redis.*;
import com.lambdaworks.redis.XReadArgs.Stream;

/**
 * ${intent} for Streams.
 *
 * @param <K> Key type.
 * @param <V> Value type.
 * @author Mark Paluch
 * @since 4.5
 */
public interface RedisStreamCommands<K, V> {

    /**
     * Append a message to the stream {@code key}.
     *
     * @param key the stream key.
     * @param body message body.
     * @return simple-reply the message Id.
     */
    String xadd(K key, Map<K, V> body);

    /**
     * Append a message to the stream {@code key}.
     *
     * @param key the stream key.
     * @param args
     * @param body message body.
     * @return simple-reply the message Id.
     */
    String xadd(K key, XAddArgs args, Map<K, V> body);

    /**
     * Append a message to the stream {@code key}.
     *
     * @param key the stream key.
     * @param keysAndValues message body.
     * @return simple-reply the message Id.
     */
    String xadd(K key, Object... keysAndValues);

    /**
     * Append a message to the stream {@code key}.
     *
     * @param key the stream key.
     * @param args
     * @param keysAndValues message body.
     * @return simple-reply the message Id.
     */
    String xadd(K key, XAddArgs args, Object... keysAndValues);

    /**
     * Read messages from a stream within a specific {@link Range}.
     *
     * @param key the stream key.
     * @param range must not be {@literal null}.
     * @return List&lt;StreamMessage&gt; array-reply list with members of the resulting stream.
     */
    List<StreamMessage<K, V>> xrange(K key, Range<String> range);

    /**
     * Read messages from a stream within a specific {@link Range} applying a {@link Limit}.
     *
     * @param key the stream key.
     * @param range must not be {@literal null}.
     * @param limit must not be {@literal null}.
     * @return List&lt;StreamMessage&gt; array-reply list with members of the resulting stream.
     */
    List<StreamMessage<K, V>> xrange(K key, Range<String> range, Limit limit);

    /**
     * Read messages from one or more {@link Stream}s.
     *
     * @param streams the streams to read from.
     * @return List&lt;StreamMessage&gt; array-reply list with members of the resulting stream.
     */
    List<StreamMessage<K, V>> xread(Stream<K>... streams);

    /**
     * Read messages from one or more {@link Stream}s.
     *
     * @param streams the streams to read from.
     * @return List&lt;StreamMessage&gt; array-reply list with members of the resulting stream.
     */
    List<StreamMessage<K, V>> xread(XReadArgs args, Stream<K>... streams);
}
