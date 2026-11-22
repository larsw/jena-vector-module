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
import java.util.List;

/**
 * The one interface a vector database has to implement. Everything Jena-specific (change capture,
 * transactions, SPARQL, checking results against the dataset) lives in this module, so a backend
 * only stores entries and answers nearest-neighbour searches.
 * <p>
 * The contract is deliberately small and idempotent, so a backend needs no transactions:
 * <ul>
 * <li>{@link #upsert} with an existing id replaces the entry; {@link #delete} of a missing id does nothing.</li>
 * <li>Writes are visible to the next {@link #search} or {@link #count} once the call returns.</li>
 * <li>{@link #search} applies the filter <em>inside</em> the search, so a selective filter still returns up to
 *     {@code limit} entries. A backend that can only filter afterwards must over-fetch.</li>
 * </ul>
 * Entries are plain strings and floats: graph, subject and predicate are encoded RDF terms
 * ({@link VectorKeys#encode}), and the id is derived from the whole quad ({@link VectorKeys#id}).
 * The index may hold entries the dataset no longer has; {@code vec:query} checks every hit
 * against the dataset, so such entries cost a little recall work but are never returned.
 * <p>
 * The contract suite in this module's test-jar ({@code VectorIndexContract}) checks an implementation.
 */
public interface VectorIndex extends AutoCloseable {

    /** Length of every vector this index holds. */
    int dimension();

    /** How {@link VectorHit#score()} is computed. */
    Metric metric();

    /** Adds or replaces entries, keyed by {@link VectorEntry#id()}. */
    void upsert(List<VectorEntry> entries);

    /** Removes entries by id. Ids not in the index are ignored. */
    void delete(Collection<String> ids);

    /**
     * The entries nearest to {@code query} that match {@code filter}, best first, at most {@code limit}.
     * Scores follow {@link Metric}: higher is closer for every metric.
     */
    List<VectorHit> search(float[] query, int limit, VectorFilter filter);

    /** Number of entries matching {@code filter}. Exact, not estimated. */
    long count(VectorFilter filter);

    /** Removes every entry. */
    void clear();

    @Override
    default void close() {}
}
