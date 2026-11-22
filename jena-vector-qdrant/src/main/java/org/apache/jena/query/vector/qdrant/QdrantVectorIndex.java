/*
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

package org.apache.jena.query.vector.qdrant;

import static io.qdrant.client.ConditionFactory.matchKeywords;
import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.QueryFactory.nearest;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;
import static io.qdrant.client.WithPayloadSelectorFactory.include;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import com.google.common.util.concurrent.ListenableFuture;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.DeletePoints;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.PointsIdsList;
import io.qdrant.client.grpc.Points.PointsSelector;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.UpsertPoints;
import io.qdrant.client.grpc.Points.WriteOrdering;
import io.qdrant.client.grpc.Points.WriteOrderingType;
import org.apache.jena.query.vector.Metric;
import org.apache.jena.query.vector.VectorEntry;
import org.apache.jena.query.vector.VectorFilter;
import org.apache.jena.query.vector.VectorHit;
import org.apache.jena.query.vector.VectorIndex;
import org.apache.jena.query.vector.VectorIndexException;

/**
 * {@link VectorIndex} on one Qdrant collection, over the gRPC client.
 * <p>
 * Each entry is a point whose id is the entry id (a UUID) and whose payload holds the encoded graph ({@value #GRAPH}),
 * subject ({@value #SUBJECT}) and predicate ({@value #PREDICATE}). Graph and predicate have keyword payload indexes,
 * so Qdrant applies a {@link VectorFilter} while it walks its HNSW graph rather than afterwards.
 * <p>
 * Writes use {@code wait=true} and strong write ordering, so they are applied when the call returns and later
 * searches see them. The collection is created on first use; an existing collection must have the same
 * dimension and distance.
 */
public final class QdrantVectorIndex implements VectorIndex {

    public static final String GRAPH = "g";
    public static final String SUBJECT = "s";
    public static final String PREDICATE = "p";

    /** Points per write request. */
    static final int BATCH = 256;

    private static final WriteOrdering STRONG = WriteOrdering.newBuilder().setType(WriteOrderingType.Strong).build();

    private final QdrantClient client;
    private final boolean ownsClient;
    private final String collection;
    private final int dimension;
    private final Metric metric;
    private final Duration timeout;

    /** Connection settings. {@code apiKey} may be null. */
    public record Settings(String host, int port, boolean tls, String apiKey, String collection, int dimension, Metric metric,
                           Duration timeout) {
        public static Settings of(String host, int port, String collection, int dimension, Metric metric) {
            return new Settings(host, port, false, null, collection, dimension, metric, Duration.ofSeconds(30));
        }
    }

    /** Connects to Qdrant, creating the collection if needed. The index owns the connection and closes it. */
    public static QdrantVectorIndex connect(Settings s) {
        QdrantGrpcClient.Builder grpc = QdrantGrpcClient.newBuilder(s.host(), s.port(), s.tls());
        if (s.apiKey() != null && !s.apiKey().isBlank())
            grpc.withApiKey(s.apiKey());
        grpc.withTimeout(s.timeout());
        return new QdrantVectorIndex(new QdrantClient(grpc.build()), true, s.collection(), s.dimension(), s.metric(), s.timeout());
    }

    /** Uses an existing client, which the caller keeps and closes. */
    public static QdrantVectorIndex open(QdrantClient client, String collection, int dimension, Metric metric) {
        return new QdrantVectorIndex(client, false, collection, dimension, metric, Duration.ofSeconds(30));
    }

    private QdrantVectorIndex(QdrantClient client, boolean ownsClient, String collection, int dimension, Metric metric,
                              Duration timeout) {
        if (dimension < 1)
            throw new IllegalArgumentException("dimension must be positive: " + dimension);
        this.client = client;
        this.ownsClient = ownsClient;
        this.collection = collection;
        this.dimension = dimension;
        this.metric = metric;
        this.timeout = timeout;
        try {
            ensureCollection();
        } catch (RuntimeException ex) {
            close();
            throw ex;
        }
    }

    private void ensureCollection() {
        if (await(client.collectionExistsAsync(collection))) {
            CollectionInfo info = await(client.getCollectionInfoAsync(collection));
            VectorParams params = info.getConfig().getParams().getVectorsConfig().getParams();
            if (params.getSize() != dimension || params.getDistance() != distance(metric))
                throw new VectorIndexException("Qdrant collection '" + collection + "' holds " + params.getSize() + "-dimensional "
                                               + params.getDistance() + " vectors, not " + dimension + "-dimensional " + distance(metric));
            return;
        }
        await(client.createCollectionAsync(collection,
                                           VectorParams.newBuilder().setSize(dimension).setDistance(distance(metric)).build()));
        for (String field : List.of(GRAPH, PREDICATE))
            await(client.createPayloadIndexAsync(collection, field, PayloadSchemaType.Keyword, null, true, WriteOrderingType.Strong,
                                                 timeout));
    }

    private static Distance distance(Metric m) {
        return switch (m) {
            case COSINE -> Distance.Cosine;
            case DOT -> Distance.Dot;
            case EUCLIDEAN -> Distance.Euclid;
        };
    }

    /** The Qdrant collection name. */
    public String collection() {
        return collection;
    }

    @Override
    public int dimension() {
        return dimension;
    }

    @Override
    public Metric metric() {
        return metric;
    }

    @Override
    public void upsert(List<VectorEntry> entries) {
        for (VectorEntry e : entries) {
            if (e.vector().length != dimension)
                throw new VectorIndexException("Vector of length " + e.vector().length + ", index holds " + dimension);
        }
        for (int from = 0; from < entries.size(); from += BATCH) {
            List<PointStruct> points = new ArrayList<>();
            for (VectorEntry e : entries.subList(from, Math.min(entries.size(), from + BATCH))) {
                points.add(PointStruct.newBuilder()
                        .setId(id(UUID.fromString(e.id())))
                        .setVectors(vectors(e.vector()))
                        .putAllPayload(Map.of(GRAPH, value(e.graph()), SUBJECT, value(e.subject()), PREDICATE, value(e.predicate())))
                        .build());
            }
            await(client.upsertAsync(UpsertPoints.newBuilder()
                    .setCollectionName(collection).addAllPoints(points).setWait(true).setOrdering(STRONG).build(), timeout));
        }
    }

    @Override
    public void delete(Collection<String> ids) {
        List<PointId> all = ids.stream().map(s -> id(UUID.fromString(s))).toList();
        for (int from = 0; from < all.size(); from += BATCH) {
            PointsIdsList batch = PointsIdsList.newBuilder().addAllIds(all.subList(from, Math.min(all.size(), from + BATCH))).build();
            await(client.deleteAsync(DeletePoints.newBuilder()
                    .setCollectionName(collection).setPoints(PointsSelector.newBuilder().setPoints(batch))
                    .setWait(true).setOrdering(STRONG).build(), timeout));
        }
    }

    @Override
    public List<VectorHit> search(float[] query, int limit, VectorFilter filter) {
        if (query.length != dimension)
            throw new VectorIndexException("Query vector of length " + query.length + ", index holds " + dimension);
        if (limit <= 0 || filter.matchesNothing())
            return List.of();
        QueryPoints.Builder q = QueryPoints.newBuilder()
                .setCollectionName(collection)
                .setQuery(nearest(query))
                .setLimit(limit)
                .setWithPayload(include(List.of(GRAPH, SUBJECT, PREDICATE)));
        Filter f = toFilter(filter);
        if (f != null)
            q.setFilter(f);
        List<ScoredPoint> points = await(client.queryAsync(q.build(), timeout));
        List<VectorHit> hits = new ArrayList<>(points.size());
        for (ScoredPoint p : points) {
            Map<String, Value> payload = p.getPayloadMap();
            // Qdrant reports the Euclidean distance; the interface wants higher-is-closer.
            float score = metric == Metric.EUCLIDEAN ? -p.getScore() : p.getScore();
            hits.add(new VectorHit(p.getId().getUuid(), payload.get(GRAPH).getStringValue(), payload.get(SUBJECT).getStringValue(),
                                   payload.get(PREDICATE).getStringValue(), score));
        }
        return hits;
    }

    @Override
    public long count(VectorFilter filter) {
        if (filter.matchesNothing())
            return 0;
        Filter f = toFilter(filter);
        return await(client.countAsync(collection, f == null ? Filter.getDefaultInstance() : f, true, timeout));
    }

    @Override
    public void clear() {
        await(client.deleteAsync(DeletePoints.newBuilder()
                .setCollectionName(collection).setPoints(PointsSelector.newBuilder().setFilter(Filter.getDefaultInstance()))
                .setWait(true).setOrdering(STRONG).build(), timeout));
    }

    @Override
    public void close() {
        if (ownsClient)
            client.close();
    }

    /** The Qdrant filter for a {@link VectorFilter}, or null when it matches everything. */
    static Filter toFilter(VectorFilter filter) {
        Filter.Builder b = Filter.newBuilder();
        if (filter.graphs() != null)
            b.addMust(matchKeywords(GRAPH, List.copyOf(filter.graphs())));
        if (filter.predicates() != null)
            b.addMust(matchKeywords(PREDICATE, List.copyOf(filter.predicates())));
        return b.getMustCount() == 0 ? null : b.build();
    }

    private <T> T await(ListenableFuture<T> future) {
        try {
            return future.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            throw new VectorIndexException("Qdrant (" + collection + "): " + cause.getMessage(), cause);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new VectorIndexException("Interrupted waiting for Qdrant", ex);
        } catch (TimeoutException ex) {
            future.cancel(true);
            throw new VectorIndexException("Qdrant (" + collection + ") did not answer within " + timeout, ex);
        }
    }
}
