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

import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.sparql.util.Symbol;

/**
 * The {@code vec:} vocabulary: the vector datatype, the property function, the context symbol and the
 * assembler terms. The namespace follows {@code jena-text}'s {@code http://jena.apache.org/text#}.
 */
public final class VectorVocab {
    private VectorVocab() {}

    public static final String NS = "http://jena.apache.org/vector#";

    /** Datatype of vector literals: a JSON array of numbers, {@code "[0.1, 0.2]"^^vec:vector}. */
    public static final String vectorDatatypeURI = NS + "vector";

    /**
     * Datatype of compact vector literals: base64 (RFC 4648, with padding) of the vector's IEEE 754 float32
     * values, little-endian, {@code "zczMPc3MTD4="^^vec:float32}. About a third the size of {@code vec:vector}
     * for embeddings, and exact.
     */
    public static final String float32DatatypeURI = NS + "float32";

    /** The property function {@code vec:query}. */
    public static final String queryPF = NS + "query";

    /** The functions {@code vec:dimension()} and {@code vec:metric()}: the settings of the dataset's index. */
    public static final String dimensionFunction = NS + "dimension";
    public static final String metricFunction = NS + "metric";

    /** Context symbol: a {@link VectorIndex} to use instead of finding one on the dataset. */
    public static final Symbol symVectorIndex = Symbol.create(NS + "index");

    // ---- Assembler

    /** A dataset whose vector predicates are mirrored into a {@link VectorIndex}. */
    public static final Resource VectorDataset = resource("VectorDataset");
    /** The exact, in-memory index in this module. */
    public static final Resource InMemoryIndex = resource("InMemoryIndex");

    /** The dataset being wrapped. */
    public static final Property pDataset = property("dataset");
    /** The index description. */
    public static final Property pIndex = property("index");
    /** A predicate whose objects are vectors; repeat for several. */
    public static final Property pPredicate = property("predicate");
    /** Vector length. */
    public static final Property pDimension = property("dimension");
    /** "cosine" (default), "dot" or "euclidean". */
    public static final Property pMetric = property("metric");

    private static Resource resource(String local) {
        return ResourceFactory.createResource(NS + local);
    }

    public static Property property(String local) {
        return ResourceFactory.createProperty(NS + local);
    }
}
