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
import java.util.regex.Pattern;

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
        // Limit -1 keeps trailing empty parts, so "[1, 2,]" is rejected rather than read as [1, 2].
        String[] parts = body.split(",", -1);
        float[] v = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].strip();
            if (!JSON_NUMBER.matcher(part).matches())
                throw new VectorIndexException("Not a JSON number at position " + i + " in " + abbreviate(lex));
            v[i] = Float.parseFloat(part);
            if (!Float.isFinite(v[i]))
                throw new VectorIndexException("Vectors hold finite numbers only (position " + i + "): " + abbreviate(lex));
        }
        return v;
    }

    /**
     * A JSON number (RFC 8259). {@link Float#parseFloat} alone also takes Java forms such as {@code 1f},
     * {@code 0x1p3}, {@code +1} and {@code .5}, which other JSON readers of the same data reject.
     */
    private static final Pattern JSON_NUMBER = Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");

    /** Parses the lexical form of a {@code vec:float32}: base64 of little-endian float32 values. */
    public static float[] parseFloat32(String lex) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(lex.strip());
        } catch (IllegalArgumentException ex) {
            throw new VectorIndexException("Not base64 (" + ex.getMessage() + "): " + abbreviate(lex), ex);
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

    /** The {@code vec:vector} literal for {@code v}, which must be non-empty and finite. */
    public static Node literal(float... v) {
        requireValid(v);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0)
                sb.append(", ");
            sb.append(v[i]);
        }
        return NodeFactory.createLiteralDT(sb.append(']').toString(), datatype);
    }

    /** The {@code vec:float32} literal for {@code v}, which must be non-empty and finite. */
    public static Node float32Literal(float... v) {
        requireValid(v);
        ByteBuffer buf = ByteBuffer.allocate(v.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : v)
            buf.putFloat(f);
        return NodeFactory.createLiteralDT(Base64.getEncoder().encodeToString(buf.array()), float32Datatype);
    }

    /** Rejects what {@link #parse} would reject, so a literal built here can always be indexed. */
    private static void requireValid(float[] v) {
        if (v.length == 0)
            throw new VectorIndexException("Empty vector");
        for (int i = 0; i < v.length; i++) {
            if (!Float.isFinite(v[i]))
                throw new VectorIndexException("Vectors hold finite numbers only (position " + i + ")");
        }
    }

    private static String abbreviate(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "...";
    }
}
