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

import java.util.Locale;

/**
 * How closeness is measured. Scores are always "higher is closer", so SPARQL can {@code ORDER BY DESC(?score)}
 * whatever the metric.
 */
public enum Metric {
    /** Cosine similarity, in [-1, 1]. */
    COSINE,
    /** Dot product. */
    DOT,
    /** Euclidean distance, negated so that higher is closer. */
    EUCLIDEAN;

    /** Parses "cosine", "dot" or "euclidean" (any case). */
    public static Metric parse(String s) {
        try {
            return valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new VectorIndexException("Unknown metric '" + s + "': expected cosine, dot or euclidean");
        }
    }

    /** The score of {@code b} relative to {@code a}, computed exactly. */
    public float score(float[] a, float[] b) {
        return switch (this) {
            case COSINE -> {
                double dot = 0, na = 0, nb = 0;
                for (int i = 0; i < a.length; i++) {
                    dot += (double) a[i] * b[i];
                    na += (double) a[i] * a[i];
                    nb += (double) b[i] * b[i];
                }
                yield na == 0 || nb == 0 ? 0f : (float) (dot / Math.sqrt(na * nb));
            }
            case DOT -> {
                double dot = 0;
                for (int i = 0; i < a.length; i++)
                    dot += (double) a[i] * b[i];
                yield (float) dot;
            }
            case EUCLIDEAN -> {
                double sum = 0;
                for (int i = 0; i < a.length; i++) {
                    double d = (double) a[i] - b[i];
                    sum += d * d;
                }
                yield (float) -Math.sqrt(sum);
            }
        };
    }
}
