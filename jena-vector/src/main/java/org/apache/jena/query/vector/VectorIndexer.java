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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.jena.graph.Node;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.system.Txn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds and checks a vector index from the dataset, the counterpart of {@code jena-text}'s text indexer.
 * Needed after loading data around the vector dataset (for example with the TDB2 bulk loader), for an index
 * that is not persistent, and to clean up entries left by a failed delete.
 */
public final class VectorIndexer {
    private VectorIndexer() {}

    private static final Logger log = LoggerFactory.getLogger(VectorIndexer.class);

    public static final int BATCH = 500;

    /** Counts from {@link #check}: vector quads in the dataset and entries in the index. */
    public record Check(long inDataset, long inIndex) {
        /** Whether the counts agree. Equal counts are necessary for an index in sync, not proof of it. */
        public boolean inSync() {
            return inDataset == inIndex;
        }
    }

    /** Clears the index and fills it from the vector dataset's own index and predicates. */
    public static long rebuild(DatasetGraphVector dsg) {
        return rebuild(dsg, dsg.getVectorIndex(), dsg.getVectorPredicates());
    }

    /**
     * Clears {@code index} and fills it from every quad of {@code dsg} with one of {@code predicates}, in one read
     * transaction. Quads whose object is not a valid vector are skipped with a warning.
     *
     * @return entries written
     */
    public static long rebuild(DatasetGraph dsg, VectorIndex index, Set<Node> predicates) {
        index.clear();
        VectorIndexChanges mapper = new VectorIndexChanges(index, predicates);
        return read(dsg, () -> {
            long written = 0;
            List<VectorEntry> batch = new ArrayList<>(BATCH);
            for (Node p : predicates) {
                Iterator<Quad> it = dsg.find(Node.ANY, Node.ANY, p, Node.ANY);
                while (it.hasNext()) {
                    Quad q = it.next();
                    VectorEntry e;
                    try {
                        e = mapper.entryFor(q.getGraph(), q.getSubject(), p, q.getObject());
                    } catch (VectorIndexException ex) {
                        log.warn("Skipping {}: {}", q, ex.getMessage());
                        continue;
                    }
                    if (e == null)
                        continue;
                    batch.add(e);
                    if (batch.size() == BATCH) {
                        index.upsert(batch);
                        written += batch.size();
                        batch = new ArrayList<>(BATCH);
                    }
                }
            }
            if (!batch.isEmpty()) {
                index.upsert(batch);
                written += batch.size();
            }
            return written;
        });
    }

    /** Compares the number of vector quads in the dataset with the number of entries in the index. */
    public static Check check(DatasetGraphVector dsg) {
        return check(dsg, dsg.getVectorIndex(), dsg.getVectorPredicates());
    }

    /** As {@link #check(DatasetGraphVector)}, for any dataset, index and predicates. */
    public static Check check(DatasetGraph dsg, VectorIndex index, Set<Node> predicates) {
        long inDataset = read(dsg, () -> {
            long n = 0;
            for (Node p : predicates) {
                Iterator<Quad> it = dsg.find(Node.ANY, Node.ANY, p, Node.ANY);
                while (it.hasNext()) {
                    Quad q = it.next();
                    if (VectorLiterals.isVector(q.getObject()) && VectorKeys.indexable(q.getSubject()))
                        n++;
                }
            }
            return n;
        });
        Set<String> encoded = predicates.stream().map(VectorKeys::encode).collect(Collectors.toSet());
        return new Check(inDataset, index.count(new VectorFilter(null, encoded)));
    }

    private static <T> T read(DatasetGraph dsg, Supplier<T> action) {
        if (dsg.isInTransaction() || !dsg.supportsTransactions())
            return action.get();
        return Txn.calculateRead(dsg, action);
    }
}
