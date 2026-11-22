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

import org.apache.jena.assembler.Assembler;
import org.apache.jena.query.vector.InitJenaVector;
import org.apache.jena.query.vector.VectorQuery;
import org.apache.jena.sys.JenaSubsystemLifecycle;

/** Registers the {@code vec:QdrantIndex} assembler when this jar is on the classpath. */
public class InitJenaVectorQdrant implements JenaSubsystemLifecycle {

    @Override
    public void start() {
        VectorQuery.init();
        Assembler.general().implementWith(QdrantVectorIndexAssembler.QdrantIndex, new QdrantVectorIndexAssembler());
    }

    @Override
    public void stop() {}

    @Override
    public int level() {
        return InitJenaVector.LEVEL_BACKEND;
    }
}
