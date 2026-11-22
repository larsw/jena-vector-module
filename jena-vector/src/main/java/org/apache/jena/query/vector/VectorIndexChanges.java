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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.jena.graph.Node;
import org.apache.jena.rdfpatch.RDFChanges;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns the changes of a transaction into index writes. It is an {@link RDFChanges} listener, the interface
 * RDF Patch uses, so the same code can index from a live dataset ({@link DatasetGraphVector}) or, later, by
 * replaying an RDF Patch log.
 * <p>
 * The ordering keeps one invariant: <b>the index holds every committed vector</b>.
 * <ul>
 * <li>Net additions are written in {@link #txnCommit}, which {@code DatasetGraphChanges} calls <em>before</em>
 *     the dataset commits. If the index write fails, the dataset transaction aborts.</li>
 * <li>Net deletions are applied by {@link #afterCommit}, once the dataset has committed. If that fails, the index
 *     keeps entries the dataset no longer has; {@code vec:query} drops them, and {@link VectorIndexer} cleans them up.</li>
 * </ul>
 * Entries for data that never committed (index written, dataset commit failed) are handled the same way.
 * <p>
 * Changes are kept per thread, because a Jena transaction belongs to one thread. Outside a transaction each
 * change is written at once.
 */
public final class VectorIndexChanges implements RDFChanges {

    private static final Logger log = LoggerFactory.getLogger(VectorIndexChanges.class);

    private final VectorIndex index;
    private final Set<Node> predicates;
    private final ThreadLocal<Map<String, Change>> pending = new ThreadLocal<>();

    /** One net change to an entry; a later change to the same id replaces an earlier one. */
    private sealed interface Change permits Upsert, Delete {}

    private record Upsert(VectorEntry entry) implements Change {}

    private record Delete(String id) implements Change {}

    public VectorIndexChanges(VectorIndex index, Set<Node> predicates) {
        if (predicates.isEmpty())
            throw new IllegalArgumentException("At least one vector predicate is needed");
        this.index = index;
        this.predicates = Set.copyOf(predicates);
    }

    public VectorIndex index() {
        return index;
    }

    public Set<Node> predicates() {
        return predicates;
    }

    /**
     * The entry for a quad, or null when the quad is not indexed (other predicate, or a subject that is not an IRI
     * or blank node). A malformed vector or one of the wrong length is an error, so the update fails.
     */
    public VectorEntry entryFor(Node g, Node s, Node p, Node o) {
        if (!predicates.contains(p))
            return null;
        if (!VectorKeys.indexable(s)) {
            log.warn("Not indexing a vector whose subject is not an IRI or blank node: {}", s);
            return null;
        }
        float[] v = VectorLiterals.parse(o);
        if (v.length != index.dimension())
            throw new VectorIndexException("Vector of length " + v.length + " for " + p + ", the index holds " + index.dimension());
        Node gn = VectorKeys.graphName(g);
        return new VectorEntry(VectorKeys.id(gn, s, p, o), VectorKeys.encode(gn), VectorKeys.encode(s), VectorKeys.encode(p), v);
    }

    @Override
    public void add(Node g, Node s, Node p, Node o) {
        VectorEntry e = entryFor(g, s, p, o);
        if (e == null)
            return;
        Map<String, Change> txn = pending.get();
        if (txn == null)
            index.upsert(List.of(e));
        else
            txn.put(e.id(), new Upsert(e));
    }

    @Override
    public void delete(Node g, Node s, Node p, Node o) {
        // A literal that is not a vector was never indexed, so there is nothing to remove.
        if (!predicates.contains(p) || !VectorKeys.indexable(s) || !VectorLiterals.isVector(o))
            return;
        String id = VectorKeys.id(g, s, p, o);
        Map<String, Change> txn = pending.get();
        if (txn == null)
            index.delete(List.of(id));
        else
            txn.put(id, new Delete(id));
    }

    @Override
    public void txnBegin() {
        pending.set(new LinkedHashMap<>());
    }

    /** Before the dataset commits: write the net additions. An exception aborts the dataset transaction. */
    @Override
    public void txnCommit() {
        Map<String, Change> txn = pending.get();
        if (txn == null)
            return;
        List<VectorEntry> upserts = new ArrayList<>();
        txn.values().forEach(c -> {
            if (c instanceof Upsert u)
                upserts.add(u.entry());
        });
        if (!upserts.isEmpty())
            index.upsert(upserts);
    }

    /** After the dataset committed: apply the net deletions. A failure is logged, not thrown: the data is committed. */
    public void afterCommit() {
        Map<String, Change> txn = pending.get();
        pending.remove();
        if (txn == null)
            return;
        List<String> deletes = new ArrayList<>();
        txn.values().forEach(c -> {
            if (c instanceof Delete d)
                deletes.add(d.id());
        });
        if (deletes.isEmpty())
            return;
        try {
            index.delete(deletes);
        } catch (RuntimeException ex) {
            log.warn("Could not remove {} entries from the vector index after commit; queries skip them, and a rebuild removes them",
                     deletes.size(), ex);
        }
    }

    @Override
    public void txnAbort() {
        pending.remove();
    }

    /** Drops whatever this thread had pending; called when a transaction ends without a commit. */
    public void discard() {
        pending.remove();
    }

    @Override
    public void header(String field, Node value) {}

    @Override
    public void addPrefix(Node gn, String prefix, String uriStr) {}

    @Override
    public void deletePrefix(Node gn, String prefix) {}

    @Override
    public void segment() {}

    @Override
    public void start() {}

    @Override
    public void finish() {}
}
