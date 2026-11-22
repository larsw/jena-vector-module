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

package org.apache.jena.query.vector.assembler;

import org.apache.jena.assembler.exceptions.AssemblerException;
import org.apache.jena.query.vector.Metric;
import org.apache.jena.query.vector.VectorVocab;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;

/** Reads the settings every index description shares, for backend assemblers to reuse. */
public final class IndexSettings {
    private IndexSettings() {}

    /** {@code vec:dimension}, required. */
    public static int dimension(Resource root) {
        Statement s = root.getProperty(VectorVocab.pDimension);
        if (s == null || !s.getObject().isLiteral())
            throw new AssemblerException(root, "The index needs vec:dimension");
        int d = s.getInt();
        if (d < 1)
            throw new AssemblerException(root, "vec:dimension must be positive: " + d);
        return d;
    }

    /** {@code vec:metric}, default cosine. */
    public static Metric metric(Resource root) {
        String m = string(root, VectorVocab.pMetric, null);
        return m == null ? Metric.COSINE : Metric.parse(m);
    }

    /** A string property, or {@code dflt} when absent. */
    public static String string(Resource root, Property p, String dflt) {
        Statement s = root.getProperty(p);
        return s == null ? dflt : s.getString();
    }

    /** An integer property, or {@code dflt} when absent. */
    public static int integer(Resource root, Property p, int dflt) {
        Statement s = root.getProperty(p);
        return s == null ? dflt : s.getInt();
    }

    /** A boolean property, or {@code dflt} when absent. */
    public static boolean bool(Resource root, Property p, boolean dflt) {
        Statement s = root.getProperty(p);
        return s == null ? dflt : s.getBoolean();
    }
}
