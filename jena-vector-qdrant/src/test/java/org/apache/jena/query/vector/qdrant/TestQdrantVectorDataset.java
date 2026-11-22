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

import org.apache.jena.query.vector.AbstractVectorDatasetTest;
import org.apache.jena.query.vector.Metric;
import org.apache.jena.query.vector.VectorIndex;

/** The SPARQL and transaction suite, with Qdrant as the index. */
public class TestQdrantVectorDataset extends AbstractVectorDatasetTest {
    @Override
    protected VectorIndex newIndex(int dimension) {
        return QdrantTestServer.newIndex(dimension, Metric.COSINE);
    }
}
