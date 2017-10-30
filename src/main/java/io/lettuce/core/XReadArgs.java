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

    public static class Builder {

        /**
         * Utility constructor.
         */
        private Builder() {

        }

        public static XReadArgs block(long milliseconds) {
            return new XReadArgs().block(milliseconds);
        }

        public static XReadArgs count(long count) {
            return new XReadArgs().count(count);
        }

        public static XReadArgs group(Group group) {
            return new XReadArgs().group(group);
        }
    }

    /**
     * Wait up to {@code milliseconds} for a new stream message.
     *
     * @param milliseconds max time to wait.
     * @return {@code this}.
     */
    public XReadArgs block(long milliseconds) {
        this.block = milliseconds;
        return this;
    }

    /**
     * Limit read to {@code count} messages.
     *
     * @param count number of messages.
     * @return {@code this}.
     */
    public XReadArgs count(long count) {
        this.count = count;
        return this;
    }

    /**
     * Associate a consumer {@link Group} with this read.
     *
     * @param group the consumer group, must not be {@literal null}.
     * @return {@code this}.
     */
    public XReadArgs group(Group group) {
        LettuceAssert.notNull(group, "Group must not be null");
        this.group = group;
        return this;
    }

    public <K, V> void build(CommandArgs<K, V> args) {

        if (block != null) {
            args.add(CommandKeyword.BLOCK).add(block);
        }

        if (count != null) {
            args.add(CommandKeyword.COUNT).add(count);
        }

        if (group != null) {
            args.add(CommandKeyword.GROUP).add(group.name).add(group.ttl);
        }
    }

    /**
     * Value object representing a Stream consumer group.
     */
    public static class Group {

        final String name;
        final long ttl;

        private Group(String name, long ttl) {
            this.name = name;
            this.ttl = ttl;
        }

        /**
         * Create a new consumer group.
         *
         * @param name must not be {@literal null} or empty.
         * @param ttl must not be {@literal null}.
         * @return the consumer {@link Group} object.
         */
        public static Group from(String name, Duration ttl) {

            LettuceAssert.notEmpty(name, "Name must not be empty");
            LettuceAssert.notNull(ttl, "TTL must not be null");

            return new Group(name, ttl.toMillis());
        }

        /**
         * Create a new consumer group.
         *
         * @param name must not be {@literal null} or empty.
         * @param ttlMillis time to live in {@link java.util.concurrent.TimeUnit#MILLISECONDS}.
         * @return the consumer {@link Group} object.
         */
        public static Group from(String name, long ttlMillis) {

            LettuceAssert.notEmpty(name, "Name must not be empty");

            return new Group(name, ttlMillis);
        }
    }

    /**
     * Value object representing a Stream consumer group.
     */
    public static class Stream<K> {

        final K name;
        final String offset;

        private Stream(K name, String offset) {
            this.name = name;
            this.offset = offset;
        }

        /**
         * Read all new arriving elements from the stream identified by {@code name}.
         *
         * @param name must not be {@literal null}.
         * @return the {@link Stream} object without a specific offset.
         */
        public static <K> Stream<K> from(K name) {

            LettuceAssert.notNull(name, "Stream must not be null");

            return new Stream<>(name, "$");
        }

        /**
         * Read all arriving elements from the stream identified by {@code name} starting at {@code offset}.
         *
         * @param name must not be {@literal null}.
         * @param offset the stream offset.
         * @return the {@link Stream} object without a specific offset.
         */
        public static <K> Stream<K> from(K name, String offset) {

            LettuceAssert.notNull(name, "Stream must not be null");
            LettuceAssert.notEmpty(offset, "Offset must not be empty");

            return new Stream<>(name, offset);
        }
    }

}
