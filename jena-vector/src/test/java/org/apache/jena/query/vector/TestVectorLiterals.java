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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.jena.graph.NodeFactory;
import org.junit.jupiter.api.Test;

public class TestVectorLiterals {

    @Test
    public void float32RoundTripsExactly() {
        float[] v = {0.1f, -2.5f, 3.4028235e38f, 1.4e-45f};
        assertArrayEquals(v, VectorLiterals.parse(VectorLiterals.float32Literal(v)));
    }

    @Test
    public void float32IsLittleEndianBase64() {
        // 1.0f is 0x3F800000: little-endian bytes 00 00 80 3F.
        assertEquals("AACAPw==", VectorLiterals.float32Literal(1f).getLiteralLexicalForm());
        assertArrayEquals(new float[] {1f}, VectorLiterals.parseFloat32("AACAPw=="));
    }

    @Test
    public void bothDatatypesAreVectors() {
        assertTrue(VectorLiterals.isVector(VectorLiterals.literal(1, 2)));
        assertTrue(VectorLiterals.isVector(VectorLiterals.float32Literal(1, 2)));
        assertFalse(VectorLiterals.isVector(NodeFactory.createLiteralString("[1, 2]")));
    }

    @Test
    public void malformedFloat32IsRejected() {
        assertThrows(VectorIndexException.class, () -> VectorLiterals.parseFloat32("not base64!"));
        assertThrows(VectorIndexException.class, () -> VectorLiterals.parseFloat32(""));
        assertThrows(VectorIndexException.class, () -> VectorLiterals.parseFloat32("AACA"));   // 3 bytes
        assertThrows(VectorIndexException.class, () -> VectorLiterals.parseFloat32("AADAfw=="));   // NaN
    }

    @Test
    public void aDatasetWithoutAnIndexLeavesTheSettingsUnbound() {
        VectorQuery.init();
        org.apache.jena.query.Dataset ds = org.apache.jena.query.DatasetFactory.createTxnMem();
        try (org.apache.jena.query.QueryExecution qe = org.apache.jena.query.QueryExecution.dataset(ds)
                .query("PREFIX vec: <http://jena.apache.org/vector#> SELECT ?d { BIND(vec:dimension() AS ?d) }").build()) {
            assertFalse(qe.execSelect().next().contains("d"));
        }
    }
}
