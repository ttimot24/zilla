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
package io.aklivity.zilla.runtime.binding.asyncapi.internal.model;

import java.util.Map;

public class AsyncapiComponents
{
    public Map<String, AsyncapiSecurityScheme> securitySchemes;
    public Map<String, AsyncapiMessage> messages;
    public Map<String, AsyncapiSchema> schemas;
    public Map<String, AsyncapiTrait> messageTraits;
    public Map<String, AsyncapiVariable> serverVariables;
}
