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

import io.lettuce.core.internal.LettuceAssert;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.CommandKeyword;

/**
 * Args for the {@literal XADD} command.
 *
 * @author Mark Paluch
 */
public class XAddArgs {

    private String id;
    private Long maxlen;

    /**
     * Limit results to {@code maxlen} entries.
     *
     * @param id must not be {@literal null}.
     * @return {@code this}
     */
    public XAddArgs id(String id) {
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
    public XAddArgs withMaxlen(long maxlen) {
        LettuceAssert.isTrue(maxlen > 0, "Maxlen must be greater 0");
        this.maxlen = maxlen;
        return this;
    }

    public <K, V> void build(CommandArgs<K, V> args) {
        if (maxlen != null) {
            args.add(CommandKeyword.MAXLEN).add(maxlen);
        }

        if (id != null) {
            args.add(id);
        } else {
            args.add("*");
        }
    }
}
