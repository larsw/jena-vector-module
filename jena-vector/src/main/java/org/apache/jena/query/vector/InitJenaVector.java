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

import org.apache.jena.sys.JenaSubsystemLifecycle;

/**
 * Jena subsystem start-up, found through {@code ServiceLoader}. Putting the jar on the classpath (of Fuseki,
 * too) is enough to make {@code vec:query} and the assemblers available. Backends start after this, at
 * {@link #LEVEL_BACKEND}.
 */
public class InitJenaVector implements JenaSubsystemLifecycle {

    /** After ARQ (30) and alongside jena-text (50). */
    public static final int LEVEL = 55;
    /** Level for backend modules, which register their index assemblers. */
    public static final int LEVEL_BACKEND = 56;

    @Override
    public void start() {
        VectorQuery.init();
    }

    @Override
    public void stop() {}

    @Override
    public int level() {
        return LEVEL;
    }
}
