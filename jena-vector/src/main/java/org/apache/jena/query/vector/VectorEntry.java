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

import java.util.Objects;

/**
 * One indexed vector: the quad {@code (graph, subject, predicate, vector literal)} it came from, encoded
 * with {@link VectorKeys#encode}, and the parsed vector.
 *
 * @param id        {@link VectorKeys#id} of the quad; a UUID string, which every common vector database accepts
 * @param graph     encoded graph name; the default graph is {@link VectorKeys#DEFAULT_GRAPH}
 * @param subject   encoded subject
 * @param predicate encoded predicate
 * @param vector    the vector; owned by the entry, callers must not modify it
 */
public record VectorEntry(String id, String graph, String subject, String predicate, float[] vector) {
    public VectorEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(vector, "vector");
    }
}
