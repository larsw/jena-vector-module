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

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.sparql.core.DatasetGraph;

/** Creates vector datasets in code; the assembler ({@code vec:VectorDataset}) does the same from configuration. */
public final class VectorDatasetFactory {
    private VectorDatasetFactory() {}

    static {
        VectorQuery.init();
    }

    /** Wraps {@code base} so the objects of {@code predicates} are mirrored into {@code index}. */
    public static DatasetGraphVector create(DatasetGraph base, VectorIndex index, Set<Node> predicates) {
        return new DatasetGraphVector(base, index, predicates);
    }

    /** As {@link #create(DatasetGraph, VectorIndex, Set)}, for a {@link Dataset} and predicate IRIs. */
    public static Dataset create(Dataset base, VectorIndex index, String... predicateIRIs) {
        Set<Node> predicates = Arrays.stream(predicateIRIs).map(NodeFactory::createURI).collect(Collectors.toSet());
        return DatasetFactory.wrap(create(base.asDatasetGraph(), index, predicates));
    }
}
