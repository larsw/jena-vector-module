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

import java.util.Set;

/**
 * Restricts a search or count to entries in some graphs and with some predicates. A null set means
 * "any"; an empty set matches nothing. Values are encoded terms ({@link VectorKeys#encode}).
 * <p>
 * Kept to equality on two fields on purpose: every vector database can apply that inside its search,
 * which is what keeps filtered results from coming back short.
 */
public record VectorFilter(Set<String> graphs, Set<String> predicates) {

    /** Matches every entry. */
    public static final VectorFilter ANY = new VectorFilter(null, null);

    public VectorFilter {
        graphs = graphs == null ? null : Set.copyOf(graphs);
        predicates = predicates == null ? null : Set.copyOf(predicates);
    }

    /** Whether an entry with this graph and predicate passes. */
    public boolean matches(String graph, String predicate) {
        return (graphs == null || graphs.contains(graph)) && (predicates == null || predicates.contains(predicate));
    }

    /** Whether nothing can match, so a backend can skip the call. */
    public boolean matchesNothing() {
        return (graphs != null && graphs.isEmpty()) || (predicates != null && predicates.isEmpty());
    }
}
