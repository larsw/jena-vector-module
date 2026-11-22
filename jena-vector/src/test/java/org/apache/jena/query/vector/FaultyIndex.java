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

import java.util.Collection;
import java.util.List;

/** Wraps an index and fails writes on demand, to test the failure paths. */
public class FaultyIndex implements VectorIndex {

    private final VectorIndex delegate;
    public volatile boolean failUpserts = false;
    public volatile boolean failDeletes = false;

    public FaultyIndex(VectorIndex delegate) {
        this.delegate = delegate;
    }

    @Override
    public int dimension() {
        return delegate.dimension();
    }

    @Override
    public Metric metric() {
        return delegate.metric();
    }

    @Override
    public void upsert(List<VectorEntry> entries) {
        if (failUpserts)
            throw new VectorIndexException("injected upsert failure");
        delegate.upsert(entries);
    }

    @Override
    public void delete(Collection<String> ids) {
        if (failDeletes)
            throw new VectorIndexException("injected delete failure");
        delegate.delete(ids);
    }

    @Override
    public List<VectorHit> search(float[] query, int limit, VectorFilter filter) {
        return delegate.search(query, limit, filter);
    }

    @Override
    public long count(VectorFilter filter) {
        return delegate.count(filter);
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    @Override
    public void close() {
        delegate.close();
    }
}
