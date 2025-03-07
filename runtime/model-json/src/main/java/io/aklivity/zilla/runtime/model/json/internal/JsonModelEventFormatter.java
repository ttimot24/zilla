/*
 * Copyright 2021-2023 Aklivity Inc
 *
 * Licensed under the Aklivity Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 *   https://www.aklivity.io/aklivity-community-license/
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */
package io.aklivity.zilla.runtime.model.json.internal;

import org.agrona.DirectBuffer;

import io.aklivity.zilla.runtime.engine.Configuration;
import io.aklivity.zilla.runtime.engine.event.EventFormatterSpi;
import io.aklivity.zilla.runtime.model.json.internal.types.StringFW;
import io.aklivity.zilla.runtime.model.json.internal.types.event.EventFW;
import io.aklivity.zilla.runtime.model.json.internal.types.event.JsonModelEventExFW;
import io.aklivity.zilla.runtime.model.json.internal.types.event.JsonModelValidationFailedExFW;

public final class JsonModelEventFormatter implements EventFormatterSpi
{
    private static final String VALIDATION_FAILED = "VALIDATION_FAILED %s";

    private final EventFW eventRO = new EventFW();
    private final JsonModelEventExFW jsonModelEventExFW = new JsonModelEventExFW();

    JsonModelEventFormatter(
        Configuration config)
    {
    }

    public String format(
        DirectBuffer buffer,
        int index,
        int length)
    {
        final EventFW event = eventRO.wrap(buffer, index, index + length);
        final JsonModelEventExFW extension = jsonModelEventExFW
            .wrap(event.extension().buffer(), event.extension().offset(), event.extension().limit());
        String result = null;
        switch (extension.kind())
        {
        case VALIDATION_FAILED:
        {
            JsonModelValidationFailedExFW ex = extension.validationFailed();
            result = String.format(VALIDATION_FAILED, asString(ex.error()));
            break;
        }
        }
        return result;
    }

    private static String asString(
        StringFW stringFW)
    {
        String s = stringFW.asString();
        return s == null ? "" : s;
    }
}
