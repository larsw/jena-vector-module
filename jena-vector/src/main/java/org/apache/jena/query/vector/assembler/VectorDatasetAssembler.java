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

package org.apache.jena.query.vector.assembler;

import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.assembler.exceptions.AssemblerException;
import org.apache.jena.graph.Node;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.vector.VectorDatasetFactory;
import org.apache.jena.query.vector.VectorIndex;
import org.apache.jena.query.vector.VectorVocab;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.assembler.DatasetAssembler;
import org.apache.jena.sparql.util.graph.GraphUtils;

/**
 * Builds a vector dataset:
 *
 * <pre>
 * &lt;#dataset&gt; a vec:VectorDataset ;
 *     vec:dataset   &lt;#tdb2&gt; ;
 *     vec:index     &lt;#index&gt; ;
 *     vec:predicate ex:embedding .
 * </pre>
 *
 * {@code vec:index} is any resource whose type has a registered assembler returning a {@link VectorIndex}, such
 * as {@code vec:InMemoryIndex} or {@code vec:QdrantIndex}. That is the only place the backend is named.
 */
public class VectorDatasetAssembler extends DatasetAssembler {

    @Override
    protected DatasetGraph createDataset(Assembler a, Resource root) {
        Resource baseRes = GraphUtils.getResourceValue(root, VectorVocab.pDataset);
        Resource indexRes = GraphUtils.getResourceValue(root, VectorVocab.pIndex);
        if (baseRes == null)
            throw new AssemblerException(root, "vec:VectorDataset needs vec:dataset");
        if (indexRes == null)
            throw new AssemblerException(root, "vec:VectorDataset needs vec:index");

        Set<Node> predicates = new LinkedHashSet<>();
        root.listProperties(VectorVocab.pPredicate).forEachRemaining((Statement s) -> {
            if (!s.getObject().isURIResource())
                throw new AssemblerException(root, "vec:predicate must be an IRI: " + s.getObject());
            predicates.add(s.getObject().asNode());
        });
        if (predicates.isEmpty())
            throw new AssemblerException(root, "vec:VectorDataset needs at least one vec:predicate");

        Dataset base = (Dataset) a.open(baseRes);
        Object index = a.open(indexRes);
        if (!(index instanceof VectorIndex vi))
            throw new AssemblerException(root, "vec:index does not describe a vector index: " + indexRes);
        return VectorDatasetFactory.create(base.asDatasetGraph(), vi, predicates);
    }
}
