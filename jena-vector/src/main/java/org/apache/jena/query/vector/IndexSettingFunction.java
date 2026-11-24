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

import org.apache.jena.query.QueryBuildException;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.expr.ExprEvalException;
import org.apache.jena.sparql.expr.ExprList;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.Function;
import org.apache.jena.sparql.function.FunctionEnv;
import org.apache.jena.sparql.util.Context;

/**
 * {@code vec:dimension()} and {@code vec:metric()}: the dimension (an integer) and the metric ("cosine", "dot" or
 * "euclidean") of the dataset's vector index. A dataset without one makes them an error, so the variable stays
 * unbound. A client can ask a SPARQL endpoint whether it answers {@code vec:query}, and with what settings:
 * {@code SELECT ?d ?m { BIND(vec:dimension() AS ?d) BIND(vec:metric() AS ?m) }}.
 */
public class IndexSettingFunction implements Function {

    @Override
    public void build(String uri, ExprList args, Context context) {
        if (!VectorVocab.dimensionFunction.equals(uri) && !VectorVocab.metricFunction.equals(uri))
            throw new QueryBuildException("Not an index setting function: " + uri);
        if (!args.isEmpty())
            throw new QueryBuildException(uri + " takes no arguments");
    }

    @Override
    public NodeValue exec(Binding binding, ExprList args, String uri, FunctionEnv env) {
        VectorIndex index = VectorQueryPF.findIndex(env.getContext(), env.getDataset());
        if (index == null)
            throw new ExprEvalException("No vector index on this dataset");
        if (VectorVocab.dimensionFunction.equals(uri))
            return NodeValue.makeInteger(index.dimension());
        if (VectorVocab.metricFunction.equals(uri))
            return NodeValue.makeString(index.metric().name().toLowerCase(Locale.ROOT));
        throw new ExprEvalException("Not an index setting function: " + uri);
    }
}
