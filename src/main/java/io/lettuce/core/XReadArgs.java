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
package io.lettuce.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import io.lettuce.core.internal.LettuceAssert;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.CommandKeyword;

/**
 * Args for the {@literal XREAD} command.
 *
 * @author Mark Paluch
 */
public class XReadArgs {

    private Long block;
    private Long count;
    private Group group;
    private final List<String> streamOffsetId = new ArrayList<>();

    /**
     * Limit results to {@code maxlen} entries.
     *
     * @param id must not be {@literal null}.
     * @return {@code this}
     */
    public XReadArgs id(String id) {
        LettuceAssert.notNull(id, "Id must not be null");
        this.id = id;
        return this;
    }

    /**
     * Limit results to {@code maxlen} entries.
     *
     * @param count number greater 0
     * @return {@code this}
     */
    public XReadArgs withMaxlen(long maxlen) {
        LettuceAssert.isTrue(maxlen > 0, "Maxlen must be greater 0");
        this.maxlen = maxlen;
        return this;
    }

    public List<String> getStreamOffsetId() {
        return streamOffsetId;
    }

    public <K, V> void build(CommandArgs<K, V> args) {
        if (maxlen != null) {
            args.add(CommandKeyword.MAXLEN).add(maxlen);
        }

    }

    static class Group {

        final String name;
        final Duration ttl;

        public Group(String name, Duration ttl) {
            this.name = name;
            this.ttl = ttl;
        }
    }

    static class Retry {

        final Duration retry;
        final Duration expire;

        public Retry(Duration retry, Duration expire) {
            this.retry = retry;
            this.expire = expire;
        }
    }
}
