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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.jena.atlas.lib.Lib;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.QueryBuildException;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphWrapper;
import org.apache.jena.sparql.core.GraphView;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingBuilder;
import org.apache.jena.sparql.engine.binding.BindingFactory;
import org.apache.jena.sparql.engine.iterator.QueryIterPlainWrapper;
import org.apache.jena.sparql.pfunction.PropFuncArg;
import org.apache.jena.sparql.pfunction.PropertyFunctionBase;
import org.apache.jena.sparql.util.IterLib;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The property function {@code vec:query}: nearest-neighbour search in SPARQL.
 *
 * <pre>
 *   ?s vec:query ( [predicate ...] vector [limit] )
 *   (?s ?score) vec:query ( [predicate ...] vector [limit] )
 * </pre>
 *
 * {@code vector} is a {@code vec:vector} or {@code vec:float32} literal or a variable bound to one, for example
 * another resource's embedding. Predicates narrow the search to some indexed predicates (default: all).
 * {@code limit} defaults to {@value #DEFAULT_LIMIT}. Results come best first; {@code ?score} is higher for closer
 * entries.
 * <p>
 * The search runs in the graph the pattern is evaluated in: the default graph at top level, {@code <g>} inside
 * {@code GRAPH <g>}, and every named graph for the union graph.
 * <p>
 * <b>Every hit is checked against the dataset</b> before it is returned: the quad it came from must still exist,
 * as this query's transaction sees it. That removes entries for deleted or uncommitted data, keeps readers on
 * their snapshot, and respects any view of the dataset (such as access control) that hides graphs. When checks
 * or a bound subject remove hits, the search is repeated with a larger limit, up to {@value #MAX_FETCH}.
 */
public class VectorQueryPF extends PropertyFunctionBase {

    private static final Logger log = LoggerFactory.getLogger(VectorQueryPF.class);

    // Both warnings can fire once per row (vec:query inside a join, vec:dimension() in a FILTER), so each is a
    // WARN the first time and a DEBUG after that.
    private static final AtomicBoolean warnedNoIndex = new AtomicBoolean();
    private static final AtomicBoolean warnedBadContextSetting = new AtomicBoolean();

    public static final int DEFAULT_LIMIT = 10;
    /** Most entries fetched for one call while trying to fill the limit. */
    public static final int MAX_FETCH = 10_000;

    @Override
    public void build(PropFuncArg argSubject, Node predicate, PropFuncArg argObject, ExecutionContext execCxt) {
        super.build(argSubject, predicate, argObject, execCxt);
        if (argSubject.isList()) {
            int n = argSubject.getArgListSize();
            if (n < 1 || n > 2)
                throw new QueryBuildException("vec:query subject is ?s or (?s ?score): " + argSubject);
        }
        List<Node> args = argObject.isList() ? argObject.getArgList() : List.of(argObject.getArg());
        int i = 0;
        while (i < args.size() && args.get(i).isURI())
            i++;
        int rest = args.size() - i;
        if (rest < 1 || rest > 2)
            throw new QueryBuildException("vec:query object is ([predicate ...] vector [limit]): " + argObject);
        if (rest == 2 && !(args.get(i + 1).isVariable() || isInteger(args.get(i + 1))))
            throw new QueryBuildException("vec:query limit must be an integer: " + args.get(i + 1));
    }

    @Override
    public QueryIterator exec(Binding binding, PropFuncArg argSubject, Node predicate, PropFuncArg argObject,
                              ExecutionContext execCxt) {
        VectorIndex index = findIndex(execCxt);
        if (index == null) {
            warnOnce(warnedNoIndex, "vec:query: no vector index (not a vector dataset and no {} in the context)",
                     VectorVocab.symVectorIndex);
            return IterLib.noResults(execCxt);
        }

        // Arguments, with variables replaced by their values.
        List<Node> args = argObject.isList() ? argObject.getArgList() : List.of(argObject.getArg());
        Set<String> predicates = new HashSet<>();
        int i = 0;
        while (i < args.size() && args.get(i).isURI())
            predicates.add(VectorKeys.encode(args.get(i++)));
        if (i >= args.size())
            return IterLib.noResults(execCxt);   // Predicates but no vector.
        Node vectorNode = Var.lookup(binding, args.get(i));
        if (!VectorLiterals.isVector(vectorNode))
            return IterLib.noResults(execCxt);   // Unbound or not a vector: no match, as for any other pattern.
        float[] query = VectorLiterals.parse(vectorNode);
        if (query.length != index.dimension())
            throw new VectorIndexException("Query vector of length " + query.length + ", the index holds " + index.dimension());
        int limit = DEFAULT_LIMIT;
        if (i + 1 < args.size()) {
            Node l = Var.lookup(binding, args.get(i + 1));
            if (!isInteger(l))
                return IterLib.noResults(execCxt);
            limit = Integer.parseInt(l.getLiteralLexicalForm());
        }
        if (limit <= 0)
            return IterLib.noResults(execCxt);

        Node subjectArg = argSubject.isList() ? argSubject.getArg(0) : argSubject.getArg();
        Node scoreArg = argSubject.isList() && argSubject.getArgListSize() > 1 ? argSubject.getArg(1) : null;
        Node boundSubject = Var.lookup(binding, subjectArg);
        boolean subjectFixed = !boundSubject.isVariable();

        // Scope: the graph this pattern runs in.
        Node graph = activeGraphName(execCxt.getActiveGraph());
        boolean union = Quad.isUnionGraph(graph);
        VectorFilter filter = new VectorFilter(union ? null : Set.of(VectorKeys.encode(graph)),
                                               predicates.isEmpty() ? null : predicates);

        DatasetGraph dsg = execCxt.getDataset();
        List<VectorHit> verified = new ArrayList<>();
        int fetch = Math.min(MAX_FETCH, subjectFixed ? Math.max(limit * 8, 100) : limit * 2);
        while (true) {
            List<VectorHit> hits = index.search(query, fetch, filter);
            verified.clear();
            for (VectorHit h : hits) {
                if (union && VectorKeys.DEFAULT_GRAPH.equals(h.graph()))
                    continue;
                if (subjectFixed && !VectorKeys.encode(boundSubject).equals(h.subject()))
                    continue;
                if (!stillInDataset(dsg, h))
                    continue;
                verified.add(h);
                if (verified.size() == limit)
                    break;
            }
            // Done when the limit is filled, the index has no more, or the cap is reached.
            if (verified.size() == limit || hits.size() < fetch || fetch >= MAX_FETCH)
                break;
            fetch = Math.min(MAX_FETCH, fetch * 4);
        }

        List<Binding> out = new ArrayList<>(verified.size());
        for (VectorHit h : verified) {
            BindingBuilder b = BindingFactory.builder(binding);
            if (!subjectFixed)
                b.add(Var.alloc(subjectArg), VectorKeys.decode(h.subject()));
            if (scoreArg != null) {
                Node score = NodeFactory.createLiteralDT(Float.toString(h.score()), XSDDatatype.XSDfloat);
                Node existing = Var.lookup(binding, scoreArg);
                if (!existing.isVariable()) {
                    if (!existing.sameValueAs(score))
                        continue;
                } else {
                    b.add(Var.alloc(scoreArg), score);
                }
            }
            out.add(b.build());
        }
        Iterator<Binding> it = out.iterator();
        return QueryIterPlainWrapper.create(it, execCxt);
    }

    /** Whether the quad a hit came from is in the dataset, as the current transaction sees it. */
    private static boolean stillInDataset(DatasetGraph dsg, VectorHit h) {
        Node g = VectorKeys.decode(h.graph());
        Node s = VectorKeys.decode(h.subject());
        Node p = VectorKeys.decode(h.predicate());
        Iterator<Quad> it = dsg.find(g, s, p, Node.ANY);
        try {
            while (it.hasNext()) {
                Quad q = it.next();
                if (VectorLiterals.isVector(q.getObject()) && h.id().equals(VectorKeys.id(g, s, p, q.getObject())))
                    return true;
            }
            return false;
        } finally {
            org.apache.jena.atlas.iterator.Iter.close(it);
        }
    }

    /** Name of the graph a pattern runs in: the default graph unless it is a named or union graph view. */
    private static Node activeGraphName(Graph g) {
        if (g instanceof GraphView view && view.getGraphName() != null)
            return VectorKeys.graphName(view.getGraphName());
        return Quad.defaultGraphIRI;
    }

    /** The index from the context, or from a {@link DatasetGraphVector} anywhere in the wrapper chain. */
    static VectorIndex findIndex(ExecutionContext execCxt) {
        return findIndex(execCxt.getContext(), execCxt.getDataset());
    }

    /** The index from the context symbol {@code vec:index}, or from a {@link DatasetGraphVector} in the wrapper chain. */
    static VectorIndex findIndex(org.apache.jena.sparql.util.Context context, DatasetGraph dataset) {
        Object fromContext = context == null ? null : context.get(VectorVocab.symVectorIndex);
        if (fromContext instanceof VectorIndex vi)
            return vi;
        if (fromContext != null)
            warnOnce(warnedBadContextSetting, "Context setting {} is a {}, not a VectorIndex", VectorVocab.symVectorIndex,
                     Lib.className(fromContext));
        DatasetGraph d = dataset;
        while (d != null) {
            if (d instanceof DatasetGraphVector v)
                return v.getVectorIndex();
            d = d instanceof DatasetGraphWrapper w ? w.getWrapped() : null;
        }
        return null;
    }

    /** Logs at WARN the first time this JVM meets the case {@code flag} tracks, and at DEBUG after that. */
    private static void warnOnce(AtomicBoolean flag, String format, Object... args) {
        if (flag.compareAndSet(false, true))
            log.warn(format + " (later occurrences are logged at DEBUG)", args);
        else
            log.debug(format, args);
    }

    private static boolean isInteger(Node n) {
        if (!n.isLiteral())
            return false;
        try {
            Integer.parseInt(n.getLiteralLexicalForm());
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }
}
