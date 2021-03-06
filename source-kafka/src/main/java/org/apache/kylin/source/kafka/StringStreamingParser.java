/*
 *
 *
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *
 *  contributor license agreements. See the NOTICE file distributed with
 *
 *  this work for additional information regarding copyright ownership.
 *
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *
 *  (the "License"); you may not use this file except in compliance with
 *
 *  the License. You may obtain a copy of the License at
 *
 *
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *
 *
 *  Unless required by applicable law or agreed to in writing, software
 *
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *
 *  See the License for the specific language governing permissions and
 *
 *  limitations under the License.
 *
 * /
 */

package org.apache.kylin.source.kafka;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;

import kafka.message.MessageAndOffset;

import org.apache.kylin.common.util.StreamingMessage;
import org.apache.kylin.metadata.model.TblColRef;

import com.google.common.collect.Lists;

/**
 */
public final class StringStreamingParser extends StreamingParser {

    public static final StringStreamingParser instance = new StringStreamingParser(null, null);

    private StringStreamingParser(List<TblColRef> allColumns, String propertiesStr) {
    }

    @Override
    public StreamingMessage parse(MessageAndOffset kafkaMessage) {
        final ByteBuffer payload = kafkaMessage.message().payload();
        byte[] bytes = new byte[payload.limit()];
        payload.get(bytes);
        return new StreamingMessage(Lists.newArrayList(new String(bytes).split(",")), kafkaMessage.offset(), kafkaMessage.offset(), Collections.<String, Object> emptyMap());
    }

    @Override
    public boolean filter(StreamingMessage streamingMessage) {
        return true;
    }
}
