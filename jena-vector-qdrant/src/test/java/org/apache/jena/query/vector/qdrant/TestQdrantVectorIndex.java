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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.apache.jena.query.vector.Metric;
import org.apache.jena.query.vector.VectorFilter;
import org.apache.jena.query.vector.VectorIndex;
import org.apache.jena.query.vector.VectorIndexContract;
import org.apache.jena.query.vector.VectorIndexException;
import org.junit.jupiter.api.Test;

/** The shared contract against a real Qdrant, plus what is specific to an external, persistent store. */
public class TestQdrantVectorIndex extends VectorIndexContract {

    @Override
    protected VectorIndex createIndex(int dimension, Metric metric) {
        return QdrantTestServer.newIndex(dimension, metric);
    }

    @Test
    public void reopeningTheCollectionKeepsItsEntries() {
        String collection = QdrantTestServer.freshCollection();
        var settings = QdrantVectorIndex.Settings.of(QdrantTestServer.host(), QdrantTestServer.port(), collection, 2, Metric.COSINE);
        try (QdrantVectorIndex first = QdrantVectorIndex.connect(settings)) {
            first.upsert(List.of(entry(G1, "<http://example/a>", P, 1, 0)));
        }
        try (QdrantVectorIndex second = QdrantVectorIndex.connect(settings)) {
            assertEquals(1, second.count(VectorFilter.ANY));
        }
    }

    @Test
    public void anExistingCollectionWithOtherSettingsIsRefused() {
        String collection = QdrantTestServer.freshCollection();
        String host = QdrantTestServer.host();
        int port = QdrantTestServer.port();
        QdrantVectorIndex.connect(QdrantVectorIndex.Settings.of(host, port, collection, 2, Metric.COSINE)).close();
        assertThrows(VectorIndexException.class,
                     () -> QdrantVectorIndex.connect(QdrantVectorIndex.Settings.of(host, port, collection, 3, Metric.COSINE)));
        assertThrows(VectorIndexException.class,
                     () -> QdrantVectorIndex.connect(QdrantVectorIndex.Settings.of(host, port, collection, 2, Metric.DOT)));
    }

    @Test
    public void writesLargerThanOneRequestAreSplit() {
        VectorIndex index = index(2, Metric.COSINE);
        var batch = new java.util.ArrayList<org.apache.jena.query.vector.VectorEntry>();
        for (int i = 0; i < QdrantVectorIndex.BATCH * 2 + 7; i++)
            batch.add(entry(G1, "<http://example/n" + i + ">", P, 1, i));
        index.upsert(batch);
        assertEquals(batch.size(), index.count(VectorFilter.ANY));
        index.delete(batch.stream().map(org.apache.jena.query.vector.VectorEntry::id).toList());
        assertEquals(0, index.count(VectorFilter.ANY));
    }
}
