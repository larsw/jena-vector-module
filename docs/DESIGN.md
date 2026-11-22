# jena-vector: design

Status: sketch, 2026-11-22. Builds against Jena 6.2.0 and Qdrant 1.20.2; all tests pass.

## Goal

Let SPARQL find the resources whose embeddings are nearest to a vector, with the vectors kept in the RDF data
and the nearest-neighbour index in an external vector database, without tying Jena to one database.

Not goals, for now: computing embeddings (vectors arrive in the data, and the query supplies one), hybrid
text-and-vector ranking, and federating over vector collections Jena did not fill.

## Shape

The structure follows `jena-text`, which already keeps an external Lucene index in step with a dataset:

| Concern | jena-text | jena-vector |
|---|---|---|
| Backend interface | `TextIndex` | `VectorIndex` |
| Dataset wrapper that captures changes | `DatasetGraphText` | `DatasetGraphVector` |
| SPARQL | `text:query` property function | `vec:query` property function |
| Configuration | `text:TextDataset`, `text:TextIndexLucene` | `vec:VectorDataset`, `vec:QdrantIndex`, `vec:InMemoryIndex` |
| Rebuild | `textindexer` | `VectorIndexer.rebuild` |

Two modules keep the database dependency out of the core: `jena-vector` has no vector database dependency, and
`jena-vector-qdrant` is the only code that knows about Qdrant.

## The backend interface

```java
public interface VectorIndex extends AutoCloseable {
    int dimension();
    Metric metric();
    void upsert(List<VectorEntry> entries);
    void delete(Collection<String> ids);
    List<VectorHit> search(float[] query, int limit, VectorFilter filter);
    long count(VectorFilter filter);
    void clear();
}
```

Kept thin on purpose, so that any database can implement it:

- **Strings and floats only.** An entry is an id, the encoded graph, subject and predicate, and the vector. No
  Jena types cross the interface.
- **Idempotent writes and no transactions.** Most vector databases have none. The core gets its consistency
  from write ordering and from checking results (below), not from the backend.
- **Deterministic ids.** An entry's id is a name-based UUID of graph, subject, predicate and the literal's
  lexical form. A retried write or a rebuild produces the same id, and deleting one triple removes exactly
  its entry. UUIDs suit Qdrant, which accepts only UUIDs or integers as point ids, and the other databases.
- **A filter of equality on two fields: graph and predicate.** Every vector database can apply that *inside*
  its nearest-neighbour search. That matters: a database that searches first and filters afterwards returns
  fewer than `limit` results when the filter is selective. The contract suite has a test for exactly that
  (`graphFilterIsAppliedInsideTheSearch`).
- **Scores are higher-is-closer** for every metric; Euclidean distance is negated. SPARQL can always
  `ORDER BY DESC(?score)`.

`VectorIndexContract` (13 tests) and `AbstractVectorDatasetTest` (19 tests, SPARQL over TDB2) ship in the
test-jar. A backend passes both, as the in-memory and Qdrant indexes do.

## Vector literals and settings

Two datatypes hold vectors. `vec:vector` is a JSON array, readable and easy to write by hand. `vec:float32` is
base64 of the IEEE 754 float32 values, little-endian: exact, about a third the size for embeddings, and
what a program that already holds a `float[]` writes. Both parse to the same vector, so the query's datatype
need not match the data's. A literal of any other datatype on an indexed predicate is ignored.

`vec:dimension()` and `vec:metric()` return the settings of the dataset's index, found the same way as for
`vec:query`. On a dataset without one they are an error, so the variable stays unbound. Without them a client
cannot tell an endpoint without jena-vector from an empty index: ARQ reads an unregistered `vec:query` as an
ordinary triple pattern, which matches nothing.

## Change capture

`DatasetGraphVector` extends `jena-rdfpatch`'s `DatasetGraphChanges`, the wrapper RDF Delta uses. It reports
every add and delete, and the transaction begin, commit and abort, to an `RDFChanges` listener. The listener
here is `VectorIndexChanges`. Because it is an `RDFChanges`, the same code could index by replaying an RDF Patch
log, which is the natural route to asynchronous indexing (see open questions).

Changes are buffered per thread (a Jena transaction belongs to one thread) as net changes per entry id, so an
add followed by a delete of the same triple in one transaction cancels out.

Graphs are returned as `GraphView`s over the wrapper, so updates made through a graph, such as SPARQL Update and
the Graph Store Protocol, go through change capture. `DROP GRAPH` and `CLEAR` arrive as one delete per triple.

## Consistency

The vector database cannot join the TDB2 transaction. The design keeps one invariant instead:

> **The index holds every committed vector, and query results contain only data the reader's transaction can see.**

It rests on two choices.

**1. Additions before the commit, deletions after.** `DatasetGraphChanges` calls the listener's `txnCommit`
*before* the base commit, and aborts the base if the listener throws. Net additions are written to the index
there. Net deletions wait for a hook this module adds, after the base commit succeeds.

**2. Every search hit is checked against the dataset.** `vec:query` looks up the quad behind each hit, in the
query's own transaction, and drops hits whose quad is gone. The index is a source of candidates, never of facts.

What each failure leaves:

| Failure | Result | Cleared by |
|---|---|---|
| Index write fails before commit | Dataset transaction aborts; the update fails | Nothing to clear |
| Dataset commit fails (or crash) after the index write | Index has entries for data that never committed | Dropped by the check on every query; removed by a rebuild |
| Index delete fails after commit | Index keeps entries for deleted data | Same |
| Data loaded around the wrapper (bulk loader) | Index misses entries | `VectorIndexer.rebuild` |

The check also gives snapshot isolation, since a reader never sees entries for data newer than its
transaction. It also respects any view of the dataset that hides graphs, such as Fuseki's access control.

Recall can be lost only by data loaded around the wrapper, and `VectorIndexer.check` detects that by comparing
counts.

`jena-text` commits the dataset, then the index, with the comment "Hard to do atomically". It has no check on
results, so its failures show up in query answers. This module puts its failures where the check can hide them.

## Query semantics

`(?s ?score) vec:query ([predicate ...] vector [limit])`

- **Vector:** a `vec:vector` literal, or a variable bound to one (for example `ex:doc1 ex:embedding ?v` earlier in
  the pattern). An unbound or non-vector argument matches nothing, like any other pattern.
- **Graph scope:** the active graph of the pattern becomes the graph filter. For the default graph that is the
  default graph only; for `GRAPH <g>` it is `<g>`; for the union graph, every named graph. Under `GRAPH ?g`, ARQ
  evaluates the pattern once per named graph, which means one search per graph (see open questions).
- **Checks and widening:** hits that fail the dataset check, or that do not match a bound subject, are dropped.
  When that leaves fewer than `limit`, the search is repeated with four times the limit, up to 10,000 entries.
- **Finding the index:** from the context symbol `vec:index`. `DatasetGraphVector` puts its index there,
  because ARQ evaluates queries on the base dataset (`getBaseForQuery`) and a wrapper shares its context with
  its base. `jena-text` does the same.

## Qdrant backend

- One collection per index, created on first use with the configured size and distance. An existing collection
  with another size or distance is refused.
- Payload: `g`, `s`, `p`, the encoded terms. `g` and `p` get keyword payload indexes, so Qdrant applies the
  filter during its HNSW search.
- Writes use `wait=true` and strong write ordering, in requests of 256 points. When the call returns, the write
  is applied and visible to later searches.
- The API key is read from an environment variable named in the configuration (`vec:apiKeyEnv`), never from the
  configuration file itself.
- `clear()` deletes by an empty filter, which keeps the collection and its settings.

## Adding a backend

1. Implement `VectorIndex`. Apply `VectorFilter` inside the search, or over-fetch if the database cannot.
2. Extend `VectorIndexContract` and `AbstractVectorDatasetTest` from the `jena-vector` test-jar, and make them pass.
3. Write an assembler for a new `vec:` type, reusing `IndexSettings` for `vec:dimension` and `vec:metric`.
4. Register it in a `JenaSubsystemLifecycle` at `InitJenaVector.LEVEL_BACKEND`, listed in
   `META-INF/services/org.apache.jena.sys.JenaSubsystemLifecycle`.

Likely next backends, from the earlier research:
- **pgvector:** its transactions could make additions and deletions exact with Postgres two-phase commit.
- **Milvus:** for very large collections.
- **OpenSearch:** for sites that already run it.

## Open questions

- **Reindex command.** `VectorIndexer` is a library call. A `vectorindexer --desc config.ttl` command, like
  `textindexer`, is the obvious addition.
- **Reconciling without a full rebuild.** Counts detect a missing entry but not which one. Finding it needs a way
  to list ids, an eighth method on the interface (most databases have a scroll API).
- **Asynchronous indexing.** For write-heavy datasets, index from an RDF Patch log instead of inside the
  transaction. `VectorIndexChanges` is already an `RDFChanges` listener, so the work is a log consumer plus a
  watermark. The check on results keeps queries correct while the index lags.
- **`GRAPH ?g` cost.** One search per named graph is correct but slow with many graphs. A single search over all
  named graphs, binding `?g` from the hit, would need ARQ to evaluate the property function outside the
  per-graph loop.
- **Large deletes.** `DROP GRAPH` deletes entry by entry. Deleting by filter (`g = <graph>`) would be one request,
  but needs the wrapper to see the drop as one operation.
- **Embeddings.** Vectors come from outside. An optional embedding interface, turning a text argument into a
  vector, would make `vec:query ("a question")` possible; it belongs outside the core.
- **Multi-node Qdrant.** Strong write ordering covers one node. A replicated collection would also need read
  consistency set on queries.
- **Union default graph.** With TDB2's `unionDefaultGraph`, the "default graph" filter should become "all named
  graphs". Not handled yet.
- **Triple-term subjects** (RDF 1.2) are skipped with a warning.

## Test coverage

| Suite | Runs against | Covers |
|---|---|---|
| `VectorIndexContract` (13) | in-memory, Qdrant | ordering, scores per metric, replace, delete, filter inside the search, counts, clear, dimension checks, zero vectors under cosine |
| `AbstractVectorDatasetTest` (19) | in-memory, Qdrant | `vec:float32` literals, `vec:dimension()`/`vec:metric()`, `DROP ALL`/`CLEAR`, SPARQL search, predicates, graph scope incl. union, vector from data, bound subject, abort, delete, drop graph, failed index write aborts, phantom entries never returned, failed delete hidden and repaired by rebuild, rebuild after bulk load, wrong dimension, blank nodes, ignored predicates |
| `TestVectorAssembler` (2) | in-memory | configuration builds a working dataset; missing predicate is an error |
| `TestVectorLiterals` (5) | none | `vec:float32` round trip and byte order, malformed literals, settings unbound without an index |
| `TestQdrantVectorIndex` (+3) | Qdrant | reopening keeps data, mismatched collection refused, batches split |
| `TestQdrantAssembler` (3) | Qdrant | configuration, missing collection, unset API key variable |
| `TestFusekiWithQdrant` (1) | Fuseki + Qdrant | `examples/fuseki-qdrant.ttl` served over HTTP: index settings, update, query, delete, `GRAPH ?g` |
