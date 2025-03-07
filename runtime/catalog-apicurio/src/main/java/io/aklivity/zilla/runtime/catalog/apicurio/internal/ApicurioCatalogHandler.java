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
package io.aklivity.zilla.runtime.catalog.apicurio.internal;

import static io.aklivity.zilla.runtime.catalog.apicurio.internal.CachedArtifactId.IN_PROGRESS;
import static io.aklivity.zilla.runtime.catalog.apicurio.internal.config.ApicurioOptionsConfigBuilder.CONTENT_ID;
import static io.aklivity.zilla.runtime.catalog.apicurio.internal.config.ApicurioOptionsConfigBuilder.LEGACY_ID_ENCODING;
import static org.agrona.BitUtil.SIZE_OF_BYTE;
import static org.agrona.BitUtil.SIZE_OF_INT;
import static org.agrona.BitUtil.SIZE_OF_LONG;

import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteOrder;
import java.text.MessageFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32C;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;
import jakarta.json.stream.JsonParsingException;

import org.agrona.DirectBuffer;
import org.agrona.collections.Int2ObjectCache;
import org.agrona.concurrent.UnsafeBuffer;

import io.aklivity.zilla.runtime.catalog.apicurio.internal.config.ApicurioOptionsConfig;
import io.aklivity.zilla.runtime.catalog.apicurio.internal.types.ApicurioPrefixFW;
import io.aklivity.zilla.runtime.engine.EngineContext;
import io.aklivity.zilla.runtime.engine.catalog.CatalogHandler;
import io.aklivity.zilla.runtime.engine.model.function.ValueConsumer;

public class ApicurioCatalogHandler implements CatalogHandler
{
    private static final String ARTIFACT_VERSION_PATH = "/apis/registry/v2/groups/{0}/artifacts/{1}/versions/{2}/meta";
    private static final String ARTIFACT_BY_GLOBAL_ID_PATH = "/apis/registry/v2/ids/globalIds/{0}";
    private static final String ARTIFACT_BY_CONTENT_ID_PATH = "/apis/registry/v2/ids/contentIds/{0}";
    private static final String ARTIFACT_META_PATH = "/apis/registry/v2/groups/{0}/artifacts/{1}/meta";
    private static final String VERSION_LATEST = "latest";
    private static final int MAX_PADDING_LENGTH = SIZE_OF_BYTE + SIZE_OF_LONG;
    private static final byte MAGIC_BYTE = 0x0;
    private static final long RESET_RETRY_DELAY_MS_DEFAULT = 0L;
    private static final long RETRY_INITIAL_DELAY_MS_DEFAULT = 1000L;

    private final ApicurioPrefixFW.Builder prefixRW = new ApicurioPrefixFW.Builder()
        .wrap(new UnsafeBuffer(new byte[5]), 0, 5);

    private final HttpClient client;
    private final String baseUrl;
    private final CRC32C crc32c;
    private final Int2ObjectCache<String> artifacts;
    private final Int2ObjectCache<CachedArtifactId> artifactIds;
    private final long maxAgeMillis;
    private final ApicurioEventContext event;
    private final long catalogId;
    private final String groupId;
    private final String useId;
    private final IdEncoder encodeId;
    private final int idSize;
    private final String artifactPath;
    private final ConcurrentMap<Integer, CompletableFuture<CachedArtifact>> cachedArtifacts;
    private final ConcurrentMap<Integer, CompletableFuture<CachedArtifactId>> cachedArtifactIds;


    public ApicurioCatalogHandler(
        ApicurioOptionsConfig config,
        EngineContext context,
        long catalogId)
    {
        this(config, context, catalogId, new ApicurioCache());
    }
    public ApicurioCatalogHandler(
        ApicurioOptionsConfig config,
        EngineContext context,
        long catalogId,
        ApicurioCache cache)
    {
        this.baseUrl = config.url;
        this.client = HttpClient.newHttpClient();
        this.crc32c = new CRC32C();
        this.artifacts = new Int2ObjectCache<>(1, 1024, i -> {});
        this.artifactIds = new Int2ObjectCache<>(1, 1024, i -> {});
        this.maxAgeMillis = config.maxAge.toMillis();
        this.groupId = config.groupId;
        this.useId = config.useId;
        this.encodeId = config.idEncoding.equals(LEGACY_ID_ENCODING) ? this::encodeLegacyId : this::encodeDefaultId;
        this.idSize = config.idEncoding.equals(LEGACY_ID_ENCODING) ? SIZE_OF_INT : SIZE_OF_LONG;
        this.artifactPath = useId.equals(CONTENT_ID) ?  ARTIFACT_BY_CONTENT_ID_PATH : ARTIFACT_BY_GLOBAL_ID_PATH;
        this.event = new ApicurioEventContext(context);
        this.catalogId = catalogId;
        this.cachedArtifacts = cache.artifacts;
        this.cachedArtifactIds = cache.artifactIds;
    }

    @Override
    public String resolve(
        int artifactId)
    {
        String artifact = null;
        if (artifactId != NO_SCHEMA_ID)
        {
            if (artifacts.containsKey(artifactId))
            {
                artifact = artifacts.get(artifactId);
            }
            else
            {
                AtomicInteger retryAttempts = new AtomicInteger();
                CompletableFuture<CachedArtifact> newFuture = new CompletableFuture<>();
                CompletableFuture<CachedArtifact> existing = cachedArtifacts.get(artifactId);
                if (existing != null && existing.isDone())
                {
                    try
                    {
                        CachedArtifact cachedArtifact = existing.get();
                        if (cachedArtifact != null)
                        {
                            retryAttempts = cachedArtifact.retryAttempts;
                        }
                    }
                    catch (Throwable ex)
                    {
                        existing.completeExceptionally(ex);
                    }
                }
                CompletableFuture<CachedArtifact> future = cachedArtifacts.merge(artifactId, newFuture, (v1, v2) ->
                    v1.getNow(CachedArtifact.IN_PROGRESS).artifact == null ? v2 : v1);
                if (future == newFuture)
                {
                    try
                    {
                        artifact = sendHttpRequest(MessageFormat.format(artifactPath, artifactId));
                        if (artifact == null)
                        {
                            if (retryAttempts.getAndIncrement() == 0)
                            {
                                event.onUnretrievableArtifactId(catalogId, artifactId);
                            }
                            newFuture.complete(new CachedArtifact(null, retryAttempts));
                        }
                        else
                        {
                            if (retryAttempts.getAndSet(0) > 0)
                            {
                                event.onRetrievableArtifactId(catalogId, artifactId);
                            }
                            newFuture.complete(new CachedArtifact(artifact, retryAttempts));
                        }
                    }
                    catch (Throwable ex)
                    {
                        newFuture.completeExceptionally(ex);
                    }
                }
                assert future != null;
                try
                {
                    artifact = future.get().artifact;
                    if (artifact != null)
                    {
                        artifacts.put(artifactId, artifact);
                    }
                }
                catch (Throwable ex)
                {
                    future.completeExceptionally(ex);
                }
            }
        }
        return artifact;
    }

    @Override
    public int resolve(
        String artifact,
        String version)
    {
        int artifactId = NO_SCHEMA_ID;

        int artifactKey = generateCRC32C(artifact, version);
        if (artifactIds.containsKey(artifactKey) && !artifactIds.get(artifactKey).expired(maxAgeMillis))
        {
            artifactId = artifactIds.get(artifactKey).id;
        }
        else
        {
            CachedArtifactId cachedArtifactId = null;
            AtomicInteger retryAttempts = new AtomicInteger();
            long retryAfter = RESET_RETRY_DELAY_MS_DEFAULT;
            CompletableFuture<CachedArtifactId> newFuture = new CompletableFuture<>();
            CompletableFuture<CachedArtifactId> existing = cachedArtifactIds.get(artifactKey);
            if (existing != null && existing.isDone())
            {
                try
                {
                    cachedArtifactId = existing.get();
                    if (cachedArtifactId != null)
                    {
                        retryAttempts = cachedArtifactId.retryAttempts;
                    }
                }
                catch (Throwable ex)
                {
                    existing.completeExceptionally(ex);
                }
            }
            CompletableFuture<CachedArtifactId> future = cachedArtifactIds.merge(artifactKey, newFuture, (v1, v2) ->
                v1.getNow(IN_PROGRESS).retry() &&
                    (v1.getNow(IN_PROGRESS).id == NO_SCHEMA_ID || v1.getNow(IN_PROGRESS).expired(maxAgeMillis)) ? v2 : v1);
            if (future == newFuture)
            {
                try
                {
                    String path = VERSION_LATEST.equals(version) ? MessageFormat.format(ARTIFACT_META_PATH, groupId, artifact) :
                        MessageFormat.format(ARTIFACT_VERSION_PATH, groupId, artifact, version);

                    String response = sendHttpRequest(path);
                    if (response == null)
                    {
                        if (retryAttempts.getAndIncrement() == 0)
                        {
                            retryAfter = RETRY_INITIAL_DELAY_MS_DEFAULT;
                            event.onUnretrievableArtifactSubjectVersion(catalogId, artifact, version);
                            if (cachedArtifactId != null && cachedArtifactId.id != NO_SCHEMA_ID)
                            {
                                event.onUnretrievableArtifactSubjectVersionStaleArtifact(catalogId,
                                    artifact, version, cachedArtifactId.id);
                            }
                        }

                        if (cachedArtifactId != null)
                        {
                            if (cachedArtifactId.retryAfter != RESET_RETRY_DELAY_MS_DEFAULT)
                            {
                                retryAfter = Math.min(cachedArtifactId.retryAfter << 1, maxAgeMillis);
                            }
                            newFuture.complete(new CachedArtifactId(cachedArtifactId.timestamp, cachedArtifactId.id,
                                retryAttempts, retryAfter));
                        }
                        else
                        {
                            newFuture.complete(new CachedArtifactId(System.currentTimeMillis(), NO_SCHEMA_ID,
                                retryAttempts, retryAfter));
                        }
                    }
                    else if (response != null)
                    {
                        if (retryAttempts.getAndSet(0) > 0)
                        {
                            event.onRetrievableArtifactSubjectVersion(catalogId, artifact, version);
                        }
                        newFuture.complete(new CachedArtifactId(System.currentTimeMillis(), resolveId(response),
                            retryAttempts, retryAfter));
                    }
                }
                catch (Throwable ex)
                {
                    newFuture.completeExceptionally(ex);
                }
            }
            assert future != null;
            try
            {
                cachedArtifactId = future.get();
                artifactId = cachedArtifactId.id;
                if (artifactId != NO_SCHEMA_ID)
                {
                    artifactIds.put(artifactKey, cachedArtifactId);
                }
            }
            catch (Throwable ex)
            {
                future.completeExceptionally(ex);
            }
        }
        return artifactId;
    }

    private String sendHttpRequest(
        String path)
    {
        HttpRequest httpRequest = HttpRequest
                .newBuilder(toURI(baseUrl, path))
                .GET()
                .build();
        // TODO: introduce interrupt/timeout for request to apicurio

        String responseBody;
        try
        {
            HttpResponse<String> httpResponse = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            responseBody = httpResponse.statusCode() == 200 ? httpResponse.body() : null;
        }
        catch (Exception ex)
        {
            responseBody = null;
        }
        return responseBody;
    }

    @Override
    public int resolve(
        DirectBuffer data,
        int index,
        int length)
    {
        int schemaId = NO_SCHEMA_ID;
        if (data.getByte(index) == MAGIC_BYTE)
        {
            schemaId = encodeId.encode(data, index + SIZE_OF_BYTE);
        }
        return schemaId;
    }

    @Override
    public int decode(
        long traceId,
        long bindingId,
        DirectBuffer data,
        int index,
        int length,
        ValueConsumer next,
        Decoder decoder)
    {
        int schemaId = NO_SCHEMA_ID;
        int progress = 0;
        int valLength = -1;
        if (data.getByte(index) == MAGIC_BYTE)
        {
            progress += SIZE_OF_BYTE;
            schemaId = encodeId.encode(data, index + progress);
            progress += idSize;
        }

        if (schemaId > NO_SCHEMA_ID)
        {
            valLength = decoder.accept(traceId, bindingId, schemaId, data, index + progress, length - progress, next);
        }
        return valLength;
    }

    @Override
    public int encode(
        long traceId,
        long bindingId,
        int schemaId,
        DirectBuffer data,
        int index,
        int length,
        ValueConsumer next,
        Encoder encoder)
    {
        ApicurioPrefixFW prefix = prefixRW.rewrap().schemaId(schemaId).build();
        next.accept(prefix.buffer(), prefix.offset(), prefix.sizeof());
        int valLength = encoder.accept(traceId, bindingId, schemaId, data, index, length, next);
        return valLength > 0 ? prefix.sizeof() + valLength : -1;
    }

    @Override
    public int encodePadding()
    {
        return MAX_PADDING_LENGTH;
    }

    private URI toURI(
        String baseUrl,
        String path)
    {
        return URI.create(baseUrl).resolve(path);
    }

    private int generateCRC32C(
        String subject,
        String version)
    {
        byte[] bytes = (subject + version).getBytes();
        crc32c.reset();
        crc32c.update(bytes, 0, bytes.length);
        return (int) crc32c.getValue();
    }

    private int resolveId(
        String response)
    {
        try
        {
            JsonReader reader = Json.createReader(new StringReader(response));
            JsonObject object = reader.readObject();

            return object.containsKey(useId) ? object.getInt(useId) : NO_SCHEMA_ID;
        }
        catch (JsonParsingException ex)
        {
            return NO_SCHEMA_ID;
        }
    }

    private int encodeDefaultId(
        DirectBuffer data,
        int index)
    {
        return (int) data.getLong(index, ByteOrder.BIG_ENDIAN);
    }

    private int encodeLegacyId(
        DirectBuffer data,
        int index)
    {
        return data.getInt(index, ByteOrder.BIG_ENDIAN);
    }

    @FunctionalInterface
    private interface IdEncoder
    {
        int encode(DirectBuffer data, int index);
    }
}
