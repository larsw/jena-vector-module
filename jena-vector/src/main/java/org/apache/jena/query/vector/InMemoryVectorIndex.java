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

package org.apache.jena.query.vector;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An exact, brute-force {@link VectorIndex} held in memory. The reference implementation the contract
 * suite is written against, and enough for tests and small datasets. Not persistent: rebuild it from the
 * dataset at start ({@link VectorIndexer#rebuild}).
 */
public final class InMemoryVectorIndex implements VectorIndex {

    private final int dimension;
    private final Metric metric;
    private final Map<String, VectorEntry> entries = new ConcurrentHashMap<>();

    public InMemoryVectorIndex(int dimension, Metric metric) {
        if (dimension < 1)
            throw new IllegalArgumentException("dimension must be positive: " + dimension);
        this.dimension = dimension;
        this.metric = metric;
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
    public void upsert(List<VectorEntry> batch) {
        for (VectorEntry e : batch) {
            if (e.vector().length != dimension)
                throw new VectorIndexException("Vector of length " + e.vector().length + ", index holds " + dimension);
        }
        batch.forEach(e -> entries.put(e.id(), new VectorEntry(e.id(), e.graph(), e.subject(), e.predicate(), e.vector().clone())));
    }

    @Override
    public void delete(Collection<String> ids) {
        ids.forEach(entries::remove);
    }

    @Override
    public List<VectorHit> search(float[] query, int limit, VectorFilter filter) {
        if (query.length != dimension)
            throw new VectorIndexException("Query vector of length " + query.length + ", index holds " + dimension);
        return entries.values().stream()
                .filter(e -> filter.matches(e.graph(), e.predicate()))
                .map(e -> new VectorHit(e.id(), e.graph(), e.subject(), e.predicate(), metric.score(query, e.vector())))
                .sorted(Comparator.comparingDouble(VectorHit::score).reversed().thenComparing(VectorHit::id))
                .limit(limit)
                .toList();
    }

    @Override
    public long count(VectorFilter filter) {
        return entries.values().stream().filter(e -> filter.matches(e.graph(), e.predicate())).count();
    }

    @Override
    public void clear() {
        entries.clear();
    }
}
