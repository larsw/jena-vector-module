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

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.sparql.core.Quad;

/**
 * How RDF terms become the strings a {@link VectorIndex} stores, and how an entry's id is derived.
 * <p>
 * IRIs are written {@code <iri>} and blank nodes {@code _:label}, using the store's own label, which TDB2 keeps
 * stable. Subjects that are neither (RDF 1.2 triple terms) are not indexed.
 */
public final class VectorKeys {
    private VectorKeys() {}

    /** Encoded name of the default graph. */
    public static final String DEFAULT_GRAPH = encode(Quad.defaultGraphIRI);

    /** The graph name to store: {@link Quad#defaultGraphIRI} for every spelling of the default graph. */
    public static Node graphName(Node g) {
        return g == null || Quad.isDefaultGraph(g) ? Quad.defaultGraphIRI : g;
    }

    /** Whether a subject can be indexed. */
    public static boolean indexable(Node subject) {
        return subject.isURI() || subject.isBlank();
    }

    /** Encodes an IRI or blank node. */
    public static String encode(Node n) {
        if (n.isURI())
            return "<" + n.getURI() + ">";
        if (n.isBlank())
            return "_:" + n.getBlankNodeLabel();
        throw new VectorIndexException("Only IRIs and blank nodes can be indexed: " + n);
    }

    /** Reverses {@link #encode}. */
    public static Node decode(String s) {
        if (s.startsWith("<") && s.endsWith(">"))
            return NodeFactory.createURI(s.substring(1, s.length() - 1));
        if (s.startsWith("_:"))
            return NodeFactory.createBlankNode(s.substring(2));
        throw new VectorIndexException("Not an encoded term: " + s);
    }

    /**
     * The id of the entry for one quad: a name-based UUID of graph, subject, predicate and the literal's
     * lexical form. Two different literals for the same subject and predicate are two entries, so deleting one
     * triple deletes exactly its entry. Deterministic, so a rebuild or a retried write produces the same ids.
     */
    public static String id(Node g, Node s, Node p, Node o) {
        String key = encode(graphName(g)) + '\n' + encode(s) + '\n' + encode(p) + '\n' + o.getLiteralLexicalForm();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
