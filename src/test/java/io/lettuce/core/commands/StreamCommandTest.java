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
package io.lettuce.core.commands;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import io.lettuce.core.*;
import io.lettuce.core.XReadArgs.Stream;

/**
 * @author Mark Paluch
 */
public class StreamCommandTest extends AbstractRedisClientTest {

    @Test
    public void xadd() {

        assertThat(redis.xadd(key, Collections.singletonMap("key", "value"))).endsWith("-0");
        assertThat(redis.xadd(key, "foo", "bar")).isNotEmpty();
    }

    @Test
    public void xaddMaxLen() {

        String id = redis.xadd(key, XAddArgs.Builder.maxlen(5), "foo", "bar");

        for (int i = 0; i < 5; i++) {
            redis.xadd(key, XAddArgs.Builder.maxlen(5), "foo", "bar");
        }

        List<StreamMessage<String, String>> messages = redis.xrange(key,
                Range.from(Range.Boundary.including(id), Range.Boundary.unbounded()));

        assertThat(messages).hasSize(5);
    }

    @Test
    public void xrange() {

        Map<String, String> body = new HashMap<>();
        body.put("key-1", "value-1");
        body.put("key-2", "value-2");

        for (int i = 0; i < 5; i++) {

            redis.xadd(key, body);
        }

        List<StreamMessage<String, String>> messages = redis.xrange(key, Range.unbounded());
        assertThat(messages).hasSize(5);

        StreamMessage<String, String> message = messages.get(0);

        assertThat(message.getId()).contains("-");
        assertThat(message.getStream()).isEqualTo(key);
        assertThat(message.getBody()).isEqualTo(body);

        assertThat(redis.xrange(key, Range.unbounded(), Limit.from(2))).hasSize(2);
    }

    @Test
    public void xread() {

        String initial1 = redis.xadd("stream-1", Collections.singletonMap("key1", "value1"));
        String initial2 = redis.xadd("stream-2", Collections.singletonMap("key2", "value2"));
        String message1 = redis.xadd("stream-1", Collections.singletonMap("key3", "value3"));
        String message2 = redis.xadd("stream-2", Collections.singletonMap("key4", "value4"));

        List<StreamMessage<String, String>> messages = redis.xread(Stream.from("stream-1", initial1),
                Stream.from("stream-2", initial2));

        StreamMessage<String, String> firstMessage = messages.get(0);

        assertThat(firstMessage.getId().equals(message1));
        assertThat(firstMessage.getStream().equals("stream-1"));
        assertThat(firstMessage.getBody()).containsEntry("key3", "value3");

        StreamMessage<String, String> secondMessage = messages.get(1);

        assertThat(secondMessage.getId().equals(message2));
        assertThat(secondMessage.getStream().equals("stream-2"));
        assertThat(secondMessage.getBody()).containsEntry("key4", "value4");
    }

    @Test
    public void xreadTransactional() {

        String initial1 = redis.xadd("stream-1", Collections.singletonMap("key1", "value1"));
        String initial2 = redis.xadd("stream-2", Collections.singletonMap("key2", "value2"));

        redis.multi();
        redis.xadd("stream-1", Collections.singletonMap("key3", "value3"));
        redis.xadd("stream-2", Collections.singletonMap("key4", "value4"));
        redis.xread(Stream.from("stream-1", initial1), Stream.from("stream-2", initial2));

        TransactionResult exec = redis.exec();
        
        String message1 = (String) exec.get(0);
        String message2 = (String) exec.get(1);
        List<StreamMessage<String, String>> messages = (List) exec.get(2);

        StreamMessage<String, String> firstMessage = messages.get(0);

        assertThat(firstMessage.getId().equals(message1));
        assertThat(firstMessage.getStream().equals("stream-1"));
        assertThat(firstMessage.getBody()).containsEntry("key3", "value3");

        StreamMessage<String, String> secondMessage = messages.get(1);

        assertThat(secondMessage.getId().equals(message2));
        assertThat(secondMessage.getStream().equals("stream-2"));
        assertThat(secondMessage.getBody()).containsEntry("key4", "value4");
    }
}
