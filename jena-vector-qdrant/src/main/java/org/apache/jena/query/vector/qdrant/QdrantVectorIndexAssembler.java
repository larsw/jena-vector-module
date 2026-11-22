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

import java.time.Duration;

import org.apache.jena.assembler.Assembler;
import org.apache.jena.assembler.Mode;
import org.apache.jena.assembler.assemblers.AssemblerBase;
import org.apache.jena.assembler.exceptions.AssemblerException;
import org.apache.jena.query.vector.VectorVocab;
import org.apache.jena.query.vector.assembler.IndexSettings;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;

/**
 * Builds a {@link QdrantVectorIndex}:
 *
 * <pre>
 * &lt;#index&gt; a vec:QdrantIndex ;
 *     vec:host       "localhost" ;      # default localhost
 *     vec:port       6334 ;             # gRPC port, default 6334
 *     vec:tls        false ;            # default false
 *     vec:apiKeyEnv  "QDRANT_API_KEY" ; # name of an environment variable holding the key; optional
 *     vec:collection "documents" ;
 *     vec:dimension  384 ;
 *     vec:metric     "cosine" .
 * </pre>
 *
 * The API key is read from the environment so it never sits in a configuration file.
 */
public class QdrantVectorIndexAssembler extends AssemblerBase {

    public static final Resource QdrantIndex = ResourceFactory.createResource(VectorVocab.NS + "QdrantIndex");
    public static final Property pHost = VectorVocab.property("host");
    public static final Property pPort = VectorVocab.property("port");
    public static final Property pTls = VectorVocab.property("tls");
    public static final Property pApiKeyEnv = VectorVocab.property("apiKeyEnv");
    public static final Property pCollection = VectorVocab.property("collection");
    public static final Property pTimeoutSeconds = VectorVocab.property("timeoutSeconds");

    @Override
    public Object open(Assembler a, Resource root, Mode mode) {
        String collection = IndexSettings.string(root, pCollection, null);
        if (collection == null || collection.isBlank())
            throw new AssemblerException(root, "vec:QdrantIndex needs vec:collection");
        String apiKey = null;
        String apiKeyEnv = IndexSettings.string(root, pApiKeyEnv, null);
        if (apiKeyEnv != null) {
            apiKey = System.getenv(apiKeyEnv);
            if (apiKey == null)
                throw new AssemblerException(root, "Environment variable " + apiKeyEnv + " (vec:apiKeyEnv) is not set");
        }
        return QdrantVectorIndex.connect(new QdrantVectorIndex.Settings(
                IndexSettings.string(root, pHost, "localhost"),
                IndexSettings.integer(root, pPort, 6334),
                IndexSettings.bool(root, pTls, false),
                apiKey,
                collection,
                IndexSettings.dimension(root),
                IndexSettings.metric(root),
                Duration.ofSeconds(IndexSettings.integer(root, pTimeoutSeconds, 30))));
    }
}
