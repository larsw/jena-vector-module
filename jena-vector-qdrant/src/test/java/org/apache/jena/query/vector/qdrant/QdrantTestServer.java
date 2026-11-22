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

import java.util.UUID;

import org.apache.jena.query.vector.Metric;
import org.testcontainers.qdrant.QdrantContainer;

/** One Qdrant container for the whole test run; each test uses its own collection. */
final class QdrantTestServer {
    private QdrantTestServer() {}

    static final String IMAGE = "qdrant/qdrant:v1.20.2";
    static final int GRPC_PORT = 6334;

    private static QdrantContainer container;

    static synchronized QdrantContainer container() {
        if (container == null) {
            container = new QdrantContainer(IMAGE);
            container.start();
        }
        return container;
    }

    static String host() {
        return container().getHost();
    }

    static int port() {
        return container().getMappedPort(GRPC_PORT);
    }

    static QdrantVectorIndex newIndex(int dimension, Metric metric) {
        return QdrantVectorIndex.connect(QdrantVectorIndex.Settings.of(host(), port(), freshCollection(), dimension, metric));
    }

    static String freshCollection() {
        return "test-" + UUID.randomUUID();
    }
}
