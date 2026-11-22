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

import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.query.ReadWrite;
import org.apache.jena.rdfpatch.system.DatasetGraphChanges;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.GraphView;

/**
 * A dataset that mirrors its vector predicates into a {@link VectorIndex}, the vector counterpart of
 * {@code jena-text}'s {@code DatasetGraphText}.
 * <p>
 * Change capture is {@code jena-rdfpatch}'s {@link DatasetGraphChanges}, which reports every add and delete plus
 * the transaction boundaries to {@link VectorIndexChanges}. This class adds the one hook it lacks, a call
 * after the base commit, where deletions are applied (see {@link VectorIndexChanges} for why).
 * <p>
 * Graphs are returned as views over this dataset, so updates made through a graph (SPARQL Update, the Graph
 * Store Protocol) are captured.
 * <p>
 * The index is published in the dataset context under {@link VectorVocab#symVectorIndex}. The context is shared
 * with the base dataset, because ARQ evaluates queries on the base ({@link #getBaseForQuery()}); a query on the
 * base dataset can therefore use {@code vec:query} too, and still gets only hits that exist in it.
 */
public class DatasetGraphVector extends DatasetGraphChanges {

    private final VectorIndexChanges changes;

    public DatasetGraphVector(DatasetGraph base, VectorIndex index, Set<Node> predicates) {
        this(base, new VectorIndexChanges(index, predicates));
    }

    private DatasetGraphVector(DatasetGraph base, VectorIndexChanges changes) {
        super(base, changes);
        this.changes = changes;
        // Queries run against the base dataset (getBaseForQuery), so vec:query finds the index through the
        // context, which this wrapper shares with the base. jena-text does the same.
        getContext().set(VectorVocab.symVectorIndex, changes.index());
    }

    public VectorIndex getVectorIndex() {
        return changes.index();
    }

    /** The predicates whose objects are indexed. */
    public Set<Node> getVectorPredicates() {
        return changes.predicates();
    }

    @Override
    public void commit() {
        boolean write = isInTransaction() && transactionMode() == ReadWrite.WRITE;
        super.commit();
        if (write)
            changes.afterCommit();
    }

    @Override
    public void abort() {
        try {
            super.abort();
        } finally {
            changes.discard();
        }
    }

    @Override
    public void end() {
        try {
            super.end();
        } finally {
            changes.discard();
        }
    }

    @Override
    public Graph getDefaultGraph() {
        return GraphView.createDefaultGraph(this);
    }

    @Override
    public Graph getGraph(Node graphNode) {
        return GraphView.createNamedGraph(this, graphNode);
    }

    @Override
    public Graph getUnionGraph() {
        return GraphView.createUnionGraph(this);
    }
}
