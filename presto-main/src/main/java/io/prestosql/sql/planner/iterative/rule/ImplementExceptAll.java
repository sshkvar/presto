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
package io.prestosql.sql.planner.iterative.rule;

import com.google.common.collect.ImmutableList;
import io.prestosql.matching.Captures;
import io.prestosql.matching.Pattern;
import io.prestosql.metadata.Metadata;
import io.prestosql.metadata.ResolvedFunction;
import io.prestosql.sql.planner.iterative.Rule;
import io.prestosql.sql.planner.plan.Assignments;
import io.prestosql.sql.planner.plan.ExceptNode;
import io.prestosql.sql.planner.plan.FilterNode;
import io.prestosql.sql.planner.plan.ProjectNode;
import io.prestosql.sql.tree.ArithmeticBinaryExpression;
import io.prestosql.sql.tree.ComparisonExpression;
import io.prestosql.sql.tree.Expression;
import io.prestosql.sql.tree.FunctionCall;
import io.prestosql.sql.tree.GenericLiteral;
import io.prestosql.sql.tree.QualifiedName;

import static com.google.common.base.Preconditions.checkState;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.sql.analyzer.TypeSignatureProvider.fromTypes;
import static io.prestosql.sql.planner.plan.Patterns.Except.distinct;
import static io.prestosql.sql.planner.plan.Patterns.except;
import static io.prestosql.sql.tree.ArithmeticBinaryExpression.Operator.SUBTRACT;
import static io.prestosql.sql.tree.ComparisonExpression.Operator.LESS_THAN_OR_EQUAL;
import static java.util.Objects.requireNonNull;

/**
 * Implement EXCEPT ALL using union, window and filter.
 * <p>
 * Transforms:
 * <pre>
 * - Except all
 *   output: a, b
 *     - Source1 (a1, b1)
 *     - Source2 (a2, b2)
 *     - Source3 (a3, b3)
 * </pre>
 * Into:
 * <pre>
 * - Project (prune helper symbols)
 *   output: a, b
 *     - Filter (row_number <= greatest(greatest(count1 - count2, 0) - count3, 0))
 *         - Window (partition by a, b)
 *           count1 <- count(marker1)
 *           count2 <- count(marker2)
 *           count3 <- count(marker3)
 *           row_number <- row_number()
 *               - Union
 *                 output: a, b, marker1, marker2, marker3
 *                   - Project (marker1 <- true, marker2 <- null, marker3 <- null)
 *                       - Source1 (a1, b1)
 *                   - Project (marker1 <- null, marker2 <- true, marker3 <- null)
 *                       - Source2 (a2, b2)
 *                   - Project (marker1 <- null, marker2 <- null, marker3 <- true)
 *                       - Source3 (a3, b3)
 * </pre>
 */
public class ImplementExceptAll
        implements Rule<ExceptNode>
{
    private static final Pattern<ExceptNode> PATTERN = except()
            .with(distinct().equalTo(false));

    private final Metadata metadata;

    public ImplementExceptAll(Metadata metadata)
    {
        this.metadata = requireNonNull(metadata, "metadata is null");
    }

    @Override
    public Pattern<ExceptNode> getPattern()
    {
        return PATTERN;
    }

    @Override
    public Result apply(ExceptNode node, Captures captures, Context context)
    {
        SetOperationNodeTranslator translator = new SetOperationNodeTranslator(metadata, context.getSymbolAllocator(), context.getIdAllocator());
        SetOperationNodeTranslator.TranslationResult result = translator.makeSetContainmentPlanForAll(node);

        // compute expected multiplicity for every row
        checkState(result.getCountSymbols().size() > 0, "ExceptNode translation result has no count symbols");
        ResolvedFunction greatest = metadata.resolveFunction(QualifiedName.of("greatest"), fromTypes(BIGINT, BIGINT));

        Expression count = result.getCountSymbols().get(0).toSymbolReference();
        for (int i = 1; i < result.getCountSymbols().size(); i++) {
            count = new FunctionCall(
                    greatest.toQualifiedName(),
                    ImmutableList.of(
                            new ArithmeticBinaryExpression(SUBTRACT, count, result.getCountSymbols().get(i).toSymbolReference()),
                            new GenericLiteral("BIGINT", "0")));
        }

        // filter rows so that expected number of rows remains
        Expression removeExtraRows = new ComparisonExpression(LESS_THAN_OR_EQUAL, result.getRowNumberSymbol().toSymbolReference(), count);
        FilterNode filter = new FilterNode(
                context.getIdAllocator().getNextId(),
                result.getPlanNode(),
                removeExtraRows);

        // prune helper symbols
        ProjectNode project = new ProjectNode(
                context.getIdAllocator().getNextId(),
                filter,
                Assignments.identity(node.getOutputSymbols()));

        return Result.ofPlanNode(project);
    }
}
