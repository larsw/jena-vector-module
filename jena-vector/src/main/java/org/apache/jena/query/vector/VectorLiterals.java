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

import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.datatypes.TypeMapper;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;

/**
 * Vector literals: {@code "[0.12, -0.5, 3]"^^vec:vector}, a JSON array of finite numbers.
 * <p>
 * The datatype is deliberately simple text, so the data stays readable and portable. Only literals of this
 * datatype are indexed, so an indexed predicate never picks up a string by accident.
 */
public final class VectorLiterals {
    private VectorLiterals() {}

    /** The {@code vec:vector} datatype. */
    public static final RDFDatatype datatype = TypeMapper.getInstance().getSafeTypeByName(VectorVocab.vectorDatatypeURI);

    /** Whether {@code n} is a {@code vec:vector} literal. */
    public static boolean isVector(Node n) {
        return n.isLiteral() && VectorVocab.vectorDatatypeURI.equals(n.getLiteralDatatypeURI());
    }

    /** Parses a vector literal. */
    public static float[] parse(Node n) {
        if (!isVector(n))
            throw new VectorIndexException("Not a vec:vector literal: " + n);
        return parse(n.getLiteralLexicalForm());
    }

    /** Parses the lexical form {@code [a, b, ...]}. */
    public static float[] parse(String lex) {
        String s = lex.strip();
        if (s.length() < 2 || s.charAt(0) != '[' || s.charAt(s.length() - 1) != ']')
            throw new VectorIndexException("A vector is written [n, n, ...]: " + lex);
        String body = s.substring(1, s.length() - 1).strip();
        if (body.isEmpty())
            throw new VectorIndexException("Empty vector: " + lex);
        String[] parts = body.split(",");
        float[] v = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                v[i] = Float.parseFloat(parts[i].strip());
            } catch (NumberFormatException ex) {
                throw new VectorIndexException("Not a number at position " + i + " in " + lex);
            }
            if (!Float.isFinite(v[i]))
                throw new VectorIndexException("Vectors hold finite numbers only: " + lex);
        }
        return v;
    }

    /** The literal for {@code v}. */
    public static Node literal(float... v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0)
                sb.append(", ");
            sb.append(v[i]);
        }
        return NodeFactory.createLiteralDT(sb.append(']').toString(), datatype);
    }
}
