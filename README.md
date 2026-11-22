# jena-vector (sketch)

Nearest-neighbour search in SPARQL for Apache Jena, backed by an external vector database. Vectors live in the
RDF data as literals; the module mirrors them into a vector index and adds a `vec:query` property function.
Qdrant is the first backend. Any other database plugs in behind one small interface, `VectorIndex`.

This is a sketch aimed at a possible contribution to Apache Jena, not a released library. It builds and its
tests pass against Jena 6.2.0 and Qdrant 1.20.2. The design and its open questions are in
[docs/DESIGN.md](docs/DESIGN.md).

## Modules

| Module | Contents | Depends on |
|---|---|---|
| `jena-vector` | `VectorIndex` interface, change capture (`DatasetGraphVector`), `vec:query`, assemblers, `VectorIndexer` (rebuild and check), an exact in-memory index, and the test suites every backend runs (test-jar) | jena-arq, jena-rdfpatch |
| `jena-vector-qdrant` | `QdrantVectorIndex` and the `vec:QdrantIndex` assembler | jena-vector, Qdrant Java client 1.20 |

## Data and queries

Vectors are literals of datatype `vec:vector`, written as a JSON array:

```turtle
PREFIX vec: <http://jena.apache.org/vector#>
PREFIX ex:  <http://example/>

ex:doc1 ex:embedding "[0.12, -0.03, 0.88]"^^vec:vector .
```

Search with `vec:query`:

```sparql
PREFIX vec: <http://jena.apache.org/vector#>
PREFIX ex:  <http://example/>

# The 10 nearest to a given vector, best first.
SELECT ?doc ?score {
  (?doc ?score) vec:query ("[0.1, 0.0, 0.9]"^^vec:vector 10)
}

# Neighbours of another document, restricted to one predicate, in one named graph.
SELECT ?doc {
  GRAPH ex:g1 {
    ex:doc1 ex:embedding ?v .
    ?doc vec:query (ex:embedding ?v 5)
  }
}
```

- Arguments are `([predicate ...] vector [limit])`; the limit defaults to 10.
- `?score` is an `xsd:float` and higher is closer for every metric (cosine, dot, Euclidean).
- The search runs in the graph the pattern is in: the default graph at top level, a named graph inside
  `GRAPH <g>`, or all named graphs for `<urn:x-arq:UnionGraph>`.
- Every hit is checked against the dataset before it is returned, so results never include deleted or
  uncommitted data.

## Configuration

In code:

```java
VectorIndex index = QdrantVectorIndex.connect(
        QdrantVectorIndex.Settings.of("localhost", 6334, "docs", 384, Metric.COSINE));
DatasetGraphVector dsg = VectorDatasetFactory.create(DatabaseMgr.connectDatasetGraph("DB"), index,
        Set.of(NodeFactory.createURI("http://example/embedding")));
```

As an assembler, which Fuseki reads ([examples/fuseki-qdrant.ttl](examples/fuseki-qdrant.ttl) is a complete
Fuseki configuration):

```turtle
ex:dataset a vec:VectorDataset ;
    vec:dataset   ex:tdb ;
    vec:index     ex:index ;
    vec:predicate ex:embedding .

ex:index a vec:QdrantIndex ;
    vec:host "localhost" ; vec:port 6334 ;
    vec:collection "docs" ; vec:dimension 384 ; vec:metric "cosine" .
```

Changing the vector database means changing the `ex:index` description. `vec:InMemoryIndex` (exact search,
not persistent) works the same way, for tests and small data.

Data loaded without going through the vector dataset (the TDB2 bulk loader, for example) is not indexed.
Run `VectorIndexer.rebuild(dsg)` after such a load. `VectorIndexer.check(dsg)` compares counts.

## Build

```
./mvnw install
```

The Qdrant tests start `qdrant/qdrant:v1.20.2` with Testcontainers, so Docker must be running. Current totals:
30 tests in `jena-vector`, 35 in `jena-vector-qdrant`.

## Adding a backend

Implement `VectorIndex` (seven methods), extend `VectorIndexContract` and `AbstractVectorDatasetTest` from the
`jena-vector` test-jar, and register an assembler for a new `vec:` type in a `JenaSubsystemLifecycle`. The Qdrant
module is the template. See [docs/DESIGN.md](docs/DESIGN.md#adding-a-backend).
