/*
 * Copyright 2014 Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onosproject.codec.impl;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.onosproject.codec.CodecContext;
import org.onosproject.codec.JsonCodec;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.Link;
import org.onosproject.net.device.DeviceService;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Link JSON codec.
 */
public class LinkCodec extends AnnotatedCodec<Link> {

    @Override
    public ObjectNode encode(Link link, CodecContext context) {
        checkNotNull(link, "Link cannot be null");
        DeviceService service = context.get(DeviceService.class);
        JsonCodec<ConnectPoint> codec = context.codec(ConnectPoint.class);
        ObjectNode result = context.mapper().createObjectNode();
        result.set("src", codec.encode(link.src(), context));
        result.set("dst", codec.encode(link.dst(), context));
        return annotate(result, link, context);
    }

}
