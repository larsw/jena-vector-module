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

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.datatypes.TypeMapper;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;

/**
 * Vector literals, in either of two datatypes:
 * <ul>
 * <li>{@code "[0.12, -0.5, 3]"^^vec:vector}, a JSON array of finite numbers, readable and portable;</li>
 * <li>{@code "zczMPc3MTD4="^^vec:float32}, base64 of little-endian float32 values, compact and exact, for
 * embeddings written by programs.</li>
 * </ul>
 * Only literals of these datatypes are indexed, so an indexed predicate never picks up a string by accident.
 * Both encode the same thing: a vector given in one datatype finds vectors stored in the other.
 */
public final class VectorLiterals {
    private VectorLiterals() {}

    /** The {@code vec:vector} datatype. */
    public static final RDFDatatype datatype = TypeMapper.getInstance().getSafeTypeByName(VectorVocab.vectorDatatypeURI);

    /** The {@code vec:float32} datatype. */
    public static final RDFDatatype float32Datatype = TypeMapper.getInstance().getSafeTypeByName(VectorVocab.float32DatatypeURI);

    /** Whether {@code n} is a vector literal of either datatype. */
    public static boolean isVector(Node n) {
        if (!n.isLiteral())
            return false;
        String dt = n.getLiteralDatatypeURI();
        return VectorVocab.vectorDatatypeURI.equals(dt) || VectorVocab.float32DatatypeURI.equals(dt);
    }

    /** Parses a vector literal of either datatype. */
    public static float[] parse(Node n) {
        if (!isVector(n))
            throw new VectorIndexException("Not a vec:vector or vec:float32 literal: " + n);
        String lex = n.getLiteralLexicalForm();
        return VectorVocab.float32DatatypeURI.equals(n.getLiteralDatatypeURI()) ? parseFloat32(lex) : parse(lex);
    }

    /** Parses the lexical form {@code [a, b, ...]} of a {@code vec:vector}. */
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

    /** Parses the lexical form of a {@code vec:float32}: base64 of little-endian float32 values. */
    public static float[] parseFloat32(String lex) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(lex.strip());
        } catch (IllegalArgumentException ex) {
            throw new VectorIndexException("Not base64: " + abbreviate(lex));
        }
        if (bytes.length == 0)
            throw new VectorIndexException("Empty vector");
        if (bytes.length % Float.BYTES != 0)
            throw new VectorIndexException("A vec:float32 holds a whole number of 4-byte floats, not " + bytes.length + " bytes");
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] v = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < v.length; i++) {
            v[i] = buf.getFloat();
            if (!Float.isFinite(v[i]))
                throw new VectorIndexException("Vectors hold finite numbers only (position " + i + ")");
        }
        return v;
    }

    /** The {@code vec:vector} literal for {@code v}. */
    public static Node literal(float... v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0)
                sb.append(", ");
            sb.append(v[i]);
        }
        return NodeFactory.createLiteralDT(sb.append(']').toString(), datatype);
    }

    /** The {@code vec:float32} literal for {@code v}. */
    public static Node float32Literal(float... v) {
        ByteBuffer buf = ByteBuffer.allocate(v.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : v)
            buf.putFloat(f);
        return NodeFactory.createLiteralDT(Base64.getEncoder().encodeToString(buf.array()), float32Datatype);
    }

    private static String abbreviate(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "...";
    }
}
