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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * What every {@link VectorIndex} must do. A backend's test extends this and supplies {@link #createIndex}.
 * Ships in the test-jar.
 */
public abstract class VectorIndexContract {

    protected static final String G1 = "<http://example/g1>";
    protected static final String G2 = "<http://example/g2>";
    protected static final String P = "<http://example/embedding>";
    protected static final String Q = "<http://example/other>";

    private final List<VectorIndex> opened = new ArrayList<>();

    /** A new, empty index. Each test gets its own. */
    protected abstract VectorIndex createIndex(int dimension, Metric metric);

    protected VectorIndex index(int dimension, Metric metric) {
        VectorIndex index = createIndex(dimension, metric);
        opened.add(index);
        return index;
    }

    @AfterEach
    void closeIndexes() {
        opened.forEach(VectorIndex::close);
        opened.clear();
    }

    protected static VectorEntry entry(String graph, String subject, String predicate, float... v) {
        return new VectorEntry(UUID.randomUUID().toString(), graph, subject, predicate, v);
    }

    private static List<String> subjects(List<VectorHit> hits) {
        return hits.stream().map(VectorHit::subject).toList();
    }

    @Test
    public void searchReturnsNearestFirstWithinTheLimit() {
        VectorIndex index = index(3, Metric.COSINE);
        index.upsert(List.of(
                entry(G1, "<http://example/a>", P, 1, 0, 0),
                entry(G1, "<http://example/b>", P, 0.9f, 0.1f, 0),
                entry(G1, "<http://example/c>", P, 0, 1, 0),
                entry(G1, "<http://example/d>", P, 0, 0, 1)));
        List<VectorHit> hits = index.search(new float[] {1, 0, 0}, 2, VectorFilter.ANY);
        assertEquals(List.of("<http://example/a>", "<http://example/b>"), subjects(hits));
        assertTrue(hits.get(0).score() >= hits.get(1).score());
        assertEquals(1.0f, hits.get(0).score(), 1e-4);
    }

    @Test
    public void hitsCarryTheEntryFields() {
        VectorIndex index = index(2, Metric.COSINE);
        VectorEntry e = entry(G2, "_:b0", Q, 1, 1);
        index.upsert(List.of(e));
        VectorHit h = index.search(new float[] {1, 1}, 1, VectorFilter.ANY).get(0);
        assertEquals(e.id(), h.id());
        assertEquals(G2, h.graph());
        assertEquals("_:b0", h.subject());
        assertEquals(Q, h.predicate());
    }

    @Test
    public void upsertWithAnExistingIdReplacesTheEntry() {
        VectorIndex index = index(2, Metric.COSINE);
        String id = UUID.randomUUID().toString();
        index.upsert(List.of(new VectorEntry(id, G1, "<http://example/a>", P, new float[] {1, 0})));
        index.upsert(List.of(new VectorEntry(id, G1, "<http://example/a>", P, new float[] {0, 1})));
        assertEquals(1, index.count(VectorFilter.ANY));
        assertEquals(1.0f, index.search(new float[] {0, 1}, 1, VectorFilter.ANY).get(0).score(), 1e-4);
    }

    @Test
    public void deleteRemovesEntriesAndIgnoresUnknownIds() {
        VectorIndex index = index(2, Metric.COSINE);
        VectorEntry a = entry(G1, "<http://example/a>", P, 1, 0);
        VectorEntry b = entry(G1, "<http://example/b>", P, 0, 1);
        index.upsert(List.of(a, b));
        index.delete(List.of(a.id(), UUID.randomUUID().toString()));
        assertEquals(List.of("<http://example/b>"), subjects(index.search(new float[] {1, 0}, 10, VectorFilter.ANY)));
        index.delete(List.of());
        assertEquals(1, index.count(VectorFilter.ANY));
    }

    /** The filter is applied inside the search: a selective filter still fills the limit. */
    @Test
    public void graphFilterIsAppliedInsideTheSearch() {
        VectorIndex index = index(4, Metric.COSINE);
        List<VectorEntry> batch = new ArrayList<>();
        for (int i = 0; i < 300; i++)
            batch.add(entry(G1, "<http://example/near" + i + ">", P, 1, 0.001f * i, 0, 0));
        for (int i = 0; i < 3; i++)
            batch.add(entry(G2, "<http://example/far" + i + ">", P, 0, 0, 1, 0.1f * i));
        index.upsert(batch);
        List<VectorHit> hits = index.search(new float[] {1, 0, 0, 0}, 3, new VectorFilter(Set.of(G2), null));
        assertEquals(3, hits.size());
        hits.forEach(h -> assertEquals(G2, h.graph()));
    }

    @Test
    public void predicateFilterSelectsPredicates() {
        VectorIndex index = index(2, Metric.COSINE);
        index.upsert(List.of(entry(G1, "<http://example/a>", P, 1, 0), entry(G1, "<http://example/b>", Q, 1, 0)));
        assertEquals(List.of("<http://example/b>"),
                     subjects(index.search(new float[] {1, 0}, 10, new VectorFilter(null, Set.of(Q)))));
        assertEquals(1, index.count(new VectorFilter(Set.of(G1), Set.of(P))));
        assertEquals(0, index.count(new VectorFilter(Set.of(G2), null)));
    }

    @Test
    public void anEmptySetMatchesNothing() {
        VectorIndex index = index(2, Metric.COSINE);
        index.upsert(List.of(entry(G1, "<http://example/a>", P, 1, 0)));
        VectorFilter none = new VectorFilter(Set.of(), null);
        assertEquals(List.of(), index.search(new float[] {1, 0}, 10, none));
        assertEquals(0, index.count(none));
    }

    @Test
    public void clearRemovesEverything() {
        VectorIndex index = index(2, Metric.COSINE);
        index.upsert(List.of(entry(G1, "<http://example/a>", P, 1, 0), entry(G2, "<http://example/b>", Q, 0, 1)));
        index.clear();
        assertEquals(0, index.count(VectorFilter.ANY));
        assertEquals(List.of(), index.search(new float[] {1, 0}, 10, VectorFilter.ANY));
        index.upsert(List.of(entry(G1, "<http://example/c>", P, 1, 0)));
        assertEquals(1, index.count(VectorFilter.ANY));
    }

    @Test
    public void euclideanScoresAreNegatedDistances() {
        VectorIndex index = index(2, Metric.EUCLIDEAN);
        index.upsert(List.of(entry(G1, "<http://example/a>", P, 3, 4), entry(G1, "<http://example/b>", P, 1, 0)));
        List<VectorHit> hits = index.search(new float[] {0, 0}, 2, VectorFilter.ANY);
        assertEquals(List.of("<http://example/b>", "<http://example/a>"), subjects(hits));
        assertEquals(-1.0f, hits.get(0).score(), 1e-3);
        assertEquals(-5.0f, hits.get(1).score(), 1e-3);
    }

    @Test
    public void dotProductScores() {
        VectorIndex index = index(2, Metric.DOT);
        index.upsert(List.of(entry(G1, "<http://example/a>", P, 2, 0), entry(G1, "<http://example/b>", P, 1, 0)));
        List<VectorHit> hits = index.search(new float[] {3, 0}, 2, VectorFilter.ANY);
        assertEquals(List.of("<http://example/a>", "<http://example/b>"), subjects(hits));
        assertEquals(6.0f, hits.get(0).score(), 1e-3);
    }

    @Test
    public void vectorsOfTheWrongLengthAreRejected() {
        VectorIndex index = index(3, Metric.COSINE);
        assertThrows(VectorIndexException.class, () -> index.upsert(List.of(entry(G1, "<http://example/a>", P, 1, 0))));
        assertThrows(VectorIndexException.class, () -> index.search(new float[] {1, 0}, 1, VectorFilter.ANY));
    }

    @Test
    public void reportsItsDimensionAndMetric() {
        VectorIndex index = index(7, Metric.DOT);
        assertEquals(7, index.dimension());
        assertEquals(Metric.DOT, index.metric());
    }
}
