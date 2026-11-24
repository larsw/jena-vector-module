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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.system.Txn;
import org.apache.jena.tdb2.DatabaseMgr;
import org.apache.jena.update.UpdateExecution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The SPARQL and transaction behaviour of a vector dataset over TDB2, run against any backend. A backend's test
 * extends this and supplies {@link #newIndex}. Ships in the test-jar.
 */
public abstract class AbstractVectorDatasetTest {

    protected static final String PREFIXES = """
            PREFIX vec: <http://jena.apache.org/vector#>
            PREFIX ex:  <http://example/>
            """;
    protected static final Node EMB = NodeFactory.createURI("http://example/embedding");
    protected static final Node OTHER = NodeFactory.createURI("http://example/other");

    protected DatasetGraph base;
    protected FaultyIndex index;
    protected DatasetGraphVector dsg;
    protected Dataset dataset;

    /** A new, empty index of the given dimension, cosine metric. */
    protected abstract VectorIndex newIndex(int dimension);

    @BeforeEach
    void setUp() {
        VectorQuery.init();
        base = DatabaseMgr.createDatasetGraph();
        index = new FaultyIndex(newIndex(3));
        dsg = VectorDatasetFactory.create(base, index, Set.of(EMB, OTHER));
        dataset = DatasetFactory.wrap(dsg);
    }

    @AfterEach
    void tearDown() {
        index.close();
    }

    protected void update(String body) {
        // Fuseki runs each update in a write transaction; do the same.
        Txn.executeWrite(dataset, () -> UpdateExecution.dataset(dataset).update(PREFIXES + body).execute());
    }

    /** The values of {@code var} in order. */
    protected List<String> select(String var, String body) {
        return Txn.calculateRead(dataset, () -> {
            List<String> out = new ArrayList<>();
            try (QueryExecution qe = QueryExecution.dataset(dataset).query(PREFIXES + body).build()) {
                ResultSet rs = qe.execSelect();
                while (rs.hasNext()) {
                    QuerySolution row = rs.next();
                    out.add(row.contains(var) ? row.get(var).toString() : null);
                }
            }
            return out;
        });
    }

    protected void loadThreeDocs() {
        update("""
                INSERT DATA {
                  ex:a ex:embedding "[1, 0, 0]"^^vec:vector .
                  ex:b ex:embedding "[0.9, 0.1, 0]"^^vec:vector .
                  ex:c ex:embedding "[0, 1, 0]"^^vec:vector .
                }""");
    }

    @Test
    public void insertedVectorsAreSearchableNearestFirst() {
        loadThreeDocs();
        assertEquals(List.of("http://example/a", "http://example/b"),
                     select("s", """
                             SELECT ?s ?score { (?s ?score) vec:query ("[1, 0, 0]"^^vec:vector 2) }"""));
    }

    @Test
    public void scoresAreFloatsHigherForCloser() {
        loadThreeDocs();
        String query = """
                SELECT ?v ?dt { (?s ?score) vec:query ("[1, 0, 0]"^^vec:vector 3) BIND(str(?score) AS ?v) BIND(datatype(?score) AS ?dt) }""";
        List<String> scores = select("v", query);
        assertEquals(3, scores.size());
        assertEquals(1.0f, Float.parseFloat(scores.get(0)), 1e-4);
        assertTrue(Float.parseFloat(scores.get(0)) >= Float.parseFloat(scores.get(1)));
        assertTrue(Float.parseFloat(scores.get(1)) >= Float.parseFloat(scores.get(2)));
        assertEquals("http://www.w3.org/2001/XMLSchema#float", select("dt", query).get(0));
    }

    @Test
    public void theQueryVectorCanComeFromTheData() {
        loadThreeDocs();
        assertEquals(List.of("http://example/c", "http://example/b"),
                     select("s", """
                             SELECT ?s { ex:c ex:embedding ?v . (?s ?score) vec:query (ex:embedding ?v 2) }"""));
    }

    @Test
    public void predicatesNarrowTheSearch() {
        loadThreeDocs();
        update("""
                INSERT DATA { ex:z ex:other "[1, 0, 0]"^^vec:vector }""");
        assertEquals(List.of("http://example/z"),
                     select("s", """
                             SELECT ?s { ?s vec:query (ex:other "[1, 0, 0]"^^vec:vector 5) }"""));
        assertEquals(4, select("s", """
                SELECT ?s { ?s vec:query ("[1, 0, 0]"^^vec:vector 5) }""").size());
    }

    @Test
    public void searchesRunInTheGraphOfThePattern() {
        loadThreeDocs();
        update("""
                INSERT DATA {
                  GRAPH ex:g1 { ex:g1doc ex:embedding "[1, 0, 0]"^^vec:vector }
                  GRAPH ex:g2 { ex:g2doc ex:embedding "[1, 0, 0]"^^vec:vector }
                }""");
        assertEquals(List.of("http://example/g1doc"),
                     select("s", """
                             SELECT ?s { GRAPH ex:g1 { ?s vec:query ("[1, 0, 0]"^^vec:vector 10) } }"""));
        assertEquals(List.of("http://example/a", "http://example/b", "http://example/c"),
                     select("s", """
                             SELECT ?s { ?s vec:query ("[1, 0, 0]"^^vec:vector 10) }"""));
        assertEquals(Set.of("http://example/g1doc", "http://example/g2doc"),
                     Set.copyOf(select("s", """
                             SELECT ?s { GRAPH <urn:x-arq:UnionGraph> { ?s vec:query ("[1, 0, 0]"^^vec:vector 10) } }""")));
    }

    @Test
    public void anAbortedTransactionLeavesTheIndexUnchanged() {
        Txn.executeWrite(dataset, () -> {}); // warm-up
        dataset.begin(org.apache.jena.query.TxnType.WRITE);
        dsg.add(Quad.defaultGraphIRI, NodeFactory.createURI("http://example/x"), EMB, VectorLiterals.literal(1, 0, 0));
        dataset.abort();
        dataset.end();
        assertEquals(0, index.count(VectorFilter.ANY));
    }

    @Test
    public void deletedVectorsLeaveTheIndex() {
        loadThreeDocs();
        update("""
                DELETE DATA { ex:a ex:embedding "[1, 0, 0]"^^vec:vector }""");
        assertEquals(2, index.count(VectorFilter.ANY));
        assertFalse(select("s", """
                SELECT ?s { ?s vec:query ("[1, 0, 0]"^^vec:vector 10) }""").contains("http://example/a"));
    }

    @Test
    public void droppingAGraphRemovesItsEntries() {
        update("""
                INSERT DATA { GRAPH ex:g1 { ex:a ex:embedding "[1, 0, 0]"^^vec:vector . ex:b ex:embedding "[0, 1, 0]"^^vec:vector } }""");
        assertEquals(2, index.count(VectorFilter.ANY));
        update("DROP GRAPH ex:g1");
        assertEquals(0, index.count(VectorFilter.ANY));
    }

    @Test
    public void aFailedIndexWriteAbortsTheUpdate() {
        index.failUpserts = true;
        assertThrows(RuntimeException.class, this::loadThreeDocs);
        index.failUpserts = false;
        assertEquals(0L, (long) Txn.calculateRead(dataset, () -> (long) base.getDefaultGraph().size()));
    }

    @Test
    public void entriesWithoutDataAreNeverReturned() {
        loadThreeDocs();
        // An entry for a quad the dataset never committed, nearest to the query.
        Node ghost = NodeFactory.createURI("http://example/ghost");
        Node lit = VectorLiterals.literal(1, 0, 0);
        index.upsert(List.of(new VectorEntry(VectorKeys.id(Quad.defaultGraphIRI, ghost, EMB, lit), VectorKeys.DEFAULT_GRAPH,
                                             VectorKeys.encode(ghost), VectorKeys.encode(EMB), new float[] {1, 0, 0})));
        assertEquals(List.of("http://example/a", "http://example/b"),
                     select("s", """
                             SELECT ?s { ?s vec:query ("[1, 0, 0]"^^vec:vector 2) }"""));
    }

    @Test
    public void aFailedDeleteIsHiddenFromQueriesAndRepairedByARebuild() {
        loadThreeDocs();
        index.failDeletes = true;
        update("""
                DELETE DATA { ex:a ex:embedding "[1, 0, 0]"^^vec:vector }""");   // commits; the index keeps the entry
        index.failDeletes = false;
        assertEquals(3, index.count(VectorFilter.ANY));
        assertEquals(List.of("http://example/b"), select("s", """
                SELECT ?s { ?s vec:query ("[1, 0, 0]"^^vec:vector 1) }"""));
        VectorIndexer.Check before = VectorIndexer.check(dsg);
        assertFalse(before.inSync(), before.toString());
        VectorIndexer.rebuild(dsg);
        assertTrue(VectorIndexer.check(dsg).inSync());
        assertEquals(2, index.count(VectorFilter.ANY));
    }

    @Test
    public void aRebuildIndexesDataLoadedAroundTheVectorDataset() {
        Txn.executeWrite(base, () -> base.add(Quad.defaultGraphIRI, NodeFactory.createURI("http://example/bulk"), EMB,
                                               VectorLiterals.literal(0, 0, 1)));
        assertEquals(List.of(), select("s", """
                SELECT ?s { ?s vec:query ("[0, 0, 1]"^^vec:vector 1) }"""));
        assertEquals(1, VectorIndexer.rebuild(dsg));
        assertEquals(List.of("http://example/bulk"), select("s", """
                SELECT ?s { ?s vec:query ("[0, 0, 1]"^^vec:vector 1) }"""));
    }

    @Test
    public void aVectorOfTheWrongLengthRejectsTheUpdate() {
        assertThrows(VectorIndexException.class, () -> update("""
                INSERT DATA { ex:a ex:embedding "[1, 0]"^^vec:vector }"""));
        assertEquals(0, index.count(VectorFilter.ANY));
    }

    @Test
    public void aBoundSubjectIsFoundEvenWhenNotAmongTheNearest() {
        loadThreeDocs();
        assertEquals(List.of("http://example/c"), select("s", """
                SELECT ?s { BIND(ex:c AS ?s) (?s ?score) vec:query ("[1, 0, 0]"^^vec:vector 1) }"""));
    }

    @Test
    public void blankNodeSubjectsRoundTrip() {
        update("""
                INSERT DATA { [] ex:embedding "[1, 0, 0]"^^vec:vector ; ex:label "anon" }""");
        assertEquals(List.of("anon"), select("l", """
                SELECT ?l { ?s vec:query ("[1, 0, 0]"^^vec:vector 1) . ?s ex:label ?l }"""));
    }

    @Test
    public void otherPredicatesAndNonVectorLiteralsAreIgnored() {
        update("""
                INSERT DATA { ex:a ex:label "not indexed" . ex:a ex:embedding2 "[1, 0, 0]"^^vec:vector }""");
        assertEquals(0, index.count(VectorFilter.ANY));
        assertThrows(VectorIndexException.class, () -> update("""
                INSERT DATA { ex:a ex:embedding "a string" }"""));
    }

    @Test
    public void float32LiteralsAreIndexedAndMatchJsonVectors() {
        Node a = VectorLiterals.float32Literal(1, 0, 0);
        Node c = VectorLiterals.float32Literal(0, 1, 0);
        Txn.executeWrite(dataset, () -> {
            dsg.add(Quad.defaultGraphIRI, NodeFactory.createURI("http://example/a"), EMB, a);
            dsg.add(Quad.defaultGraphIRI, NodeFactory.createURI("http://example/c"), EMB, c);
        });
        assertEquals(List.of("http://example/a"),
                     select("s", """
                             SELECT ?s { (?s ?score) vec:query ("[1, 0, 0]"^^vec:vector 1) }"""));
        String query = "SELECT ?s { ?s vec:query (\"" + c.getLiteralLexicalForm() + "\"^^vec:float32 1) }";
        assertEquals(List.of("http://example/c"), select("s", query));

        Txn.executeWrite(dataset, () -> dsg.delete(Quad.defaultGraphIRI, NodeFactory.createURI("http://example/c"), EMB, c));
        assertEquals(1, index.count(VectorFilter.ANY));
    }

    @Test
    public void deletingAnAbsentLiteralOfTheOtherDatatypeKeepsTheEntry() {
        // Entry ids come from the lexical form; the same text under vec:vector is not this vector.
        String lex = VectorLiterals.float32Literal(1, 0, 0).getLiteralLexicalForm();
        update("INSERT DATA { ex:a ex:embedding \"" + lex + "\"^^vec:float32 }");
        update("DELETE DATA { ex:a ex:embedding \"" + lex + "\"^^vec:vector }");
        assertEquals(1, index.count(VectorFilter.ANY));
        assertEquals(List.of("http://example/a"), select("s", """
                SELECT ?s { ?s vec:query ("[1, 0, 0]"^^vec:vector 1) }"""));
    }

    @Test
    public void theIndexSettingsCanBeAskedFor() {
        assertEquals(List.of("3"), select("d", "SELECT ?d { BIND(str(vec:dimension()) AS ?d) }"));
        assertEquals(List.of("cosine"), select("m", "SELECT ?m { BIND(vec:metric() AS ?m) }"));
    }

    @Test
    public void dropAllAndClearRemoveEveryEntry() {
        loadThreeDocs();
        update("INSERT DATA { GRAPH ex:g { ex:d ex:embedding \"[0, 0, 1]\"^^vec:vector } }");
        assertEquals(4, index.count(VectorFilter.ANY));
        update("DROP ALL");
        assertEquals(0, index.count(VectorFilter.ANY), "DROP ALL");
        loadThreeDocs();
        update("INSERT DATA { GRAPH ex:g { ex:d ex:embedding \"[0, 0, 1]\"^^vec:vector } }");
        update("CLEAR DEFAULT");
        assertEquals(1, index.count(VectorFilter.ANY), "CLEAR DEFAULT keeps the named graph's entry");
        assertEquals(List.of("http://example/d"), select("s", """
                SELECT ?s { GRAPH ex:g { ?s vec:query ("[0, 0, 1]"^^vec:vector 5) } }"""));
    }
}
