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

package org.apache.jena.query.vector.qdrant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.apache.jena.assembler.exceptions.AssemblerException;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.vector.DatasetGraphVector;
import org.apache.jena.query.vector.VectorVocab;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.sparql.core.assembler.AssemblerUtils;
import org.apache.jena.system.Txn;
import org.apache.jena.update.UpdateExecution;
import org.junit.jupiter.api.Test;

/** Configuration names the backend in one place: swapping vec:InMemoryIndex for vec:QdrantIndex is the whole change. */
public class TestQdrantAssembler {

    static String config(String indexDescription) {
        return """
                PREFIX vec:  <http://jena.apache.org/vector#>
                PREFIX tdb2: <http://jena.apache.org/2016/tdb#>
                PREFIX ex:   <http://example/>

                ex:dataset a vec:VectorDataset ;
                    vec:dataset   ex:tdb ;
                    vec:index     ex:index ;
                    vec:predicate ex:embedding .

                ex:tdb a tdb2:DatasetTDB2 ; tdb2:location "--mem--" .

                """ + indexDescription;
    }

    static Model model(String ttl) {
        Model m = ModelFactory.createDefaultModel();
        m.read(new StringReader(ttl), null, "TTL");
        return m;
    }

    @Test
    public void aQdrantIndexFromConfiguration() {
        String ttl = config("""
                ex:index a vec:QdrantIndex ;
                    vec:host "%s" ; vec:port %d ; vec:collection "%s" ;
                    vec:dimension 2 ; vec:metric "cosine" .
                """.formatted(QdrantTestServer.host(), QdrantTestServer.port(), QdrantTestServer.freshCollection()));
        Dataset ds = (Dataset) AssemblerUtils.build(model(ttl), VectorVocab.VectorDataset);
        DatasetGraphVector dsg = assertInstanceOf(DatasetGraphVector.class, ds.asDatasetGraph());
        assertInstanceOf(QdrantVectorIndex.class, dsg.getVectorIndex());
        Txn.executeWrite(ds, () -> UpdateExecution.dataset(ds).update("""
                PREFIX vec: <http://jena.apache.org/vector#> PREFIX ex: <http://example/>
                INSERT DATA { ex:a ex:embedding "[1, 0]"^^vec:vector . ex:b ex:embedding "[0, 1]"^^vec:vector }""").execute());
        List<String> found = Txn.calculateRead(ds, () -> {
            List<String> out = new ArrayList<>();
            try (QueryExecution qe = QueryExecution.dataset(ds).query("""
                    PREFIX vec: <http://jena.apache.org/vector#>
                    SELECT ?s { ?s vec:query ("[0, 1]"^^vec:vector 1) }""").build()) {
                qe.execSelect().forEachRemaining(r -> out.add(r.getResource("s").getURI()));
            }
            return out;
        });
        assertEquals(List.of("http://example/b"), found);
        dsg.getVectorIndex().close();
    }

    @Test
    public void aMissingCollectionIsAConfigurationError() {
        String ttl = config("""
                ex:index a vec:QdrantIndex ; vec:dimension 2 .
                """);
        assertThrows(AssemblerException.class, () -> AssemblerUtils.build(model(ttl), VectorVocab.VectorDataset));
    }

    @Test
    public void anUnsetApiKeyVariableIsAConfigurationError() {
        String ttl = config("""
                ex:index a vec:QdrantIndex ; vec:collection "x" ; vec:dimension 2 ;
                    vec:apiKeyEnv "JENA_VECTOR_TEST_SURELY_UNSET" .
                """);
        assertThrows(AssemblerException.class, () -> AssemblerUtils.build(model(ttl), VectorVocab.VectorDataset));
    }
}
