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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.jena.fuseki.main.FusekiServer;
import org.apache.jena.rdflink.RDFLink;
import org.apache.jena.rdflink.RDFLinkHTTP;
import org.junit.jupiter.api.Test;

/** The example Fuseki configuration, pointed at the test container, over HTTP. */
public class TestFusekiWithQdrant {

    @Test
    public void fusekiServesVectorQueriesFromTheExampleConfiguration() throws Exception {
        String config = Files.readString(Path.of("../examples/fuseki-qdrant.ttl"))
                .replace("tdb2:location \"DB-docs\"", "tdb2:location \"--mem--\"")
                .replace("vec:host       \"localhost\"", "vec:host \"" + QdrantTestServer.host() + "\"")
                .replace("vec:port       6334", "vec:port " + QdrantTestServer.port())
                .replace("vec:collection \"docs\"", "vec:collection \"" + QdrantTestServer.freshCollection() + "\"")
                .replace("vec:dimension  384", "vec:dimension 3");
        Path file = Files.createTempFile("fuseki-qdrant", ".ttl");
        Files.writeString(file, config);

        FusekiServer server = FusekiServer.create().port(0).parseConfigFile(file).build().start();
        try (RDFLink link = RDFLinkHTTP.service("http://localhost:" + server.getPort() + "/docs").build()) {
            link.update("""
                    PREFIX vec: <http://jena.apache.org/vector#> PREFIX ex: <http://example/>
                    INSERT DATA {
                      ex:a ex:embedding "[1, 0, 0]"^^vec:vector .
                      ex:b ex:embedding "[0, 1, 0]"^^vec:vector .
                      GRAPH ex:g { ex:c ex:embedding "[0, 1, 0.1]"^^vec:vector }
                    }""");
            List<String> found = new ArrayList<>();
            link.querySelect("""
                    PREFIX vec: <http://jena.apache.org/vector#>
                    SELECT ?d ?m { BIND(str(vec:dimension()) AS ?d) BIND(vec:metric() AS ?m) }""",
                             row -> found.add(row.get("d").getLiteralLexicalForm() + " " + row.get("m").getLiteralLexicalForm()));
            assertEquals(List.of("3 cosine"), found, "the index settings over HTTP");
            found.clear();
            link.querySelect("""
                    PREFIX vec: <http://jena.apache.org/vector#>
                    SELECT ?s ?score { (?s ?score) vec:query ("[0, 1, 0]"^^vec:vector 5) } ORDER BY DESC(?score)""",
                             row -> found.add(row.get("s").getURI()));
            assertEquals(List.of("http://example/b", "http://example/a"), found);

            link.update("""
                    PREFIX vec: <http://jena.apache.org/vector#> PREFIX ex: <http://example/>
                    DELETE DATA { ex:b ex:embedding "[0, 1, 0]"^^vec:vector }""");
            found.clear();
            link.querySelect("""
                    PREFIX vec: <http://jena.apache.org/vector#>
                    SELECT ?s { GRAPH ?g { ?s vec:query ("[0, 1, 0]"^^vec:vector 5) } }""",
                             row -> found.add(row.get("s").getURI()));
            assertEquals(List.of("http://example/c"), found);
        } finally {
            server.stop();
            Files.deleteIfExists(file);
        }
    }
}
