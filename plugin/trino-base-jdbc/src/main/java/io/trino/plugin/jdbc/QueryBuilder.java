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
package io.trino.plugin.jdbc;

import com.google.common.base.Joiner;
import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.airlift.log.Logger;
import io.airlift.slice.Slice;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.predicate.Domain;
import io.trino.spi.predicate.Range;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.predicate.ValueSet;
import io.trino.spi.type.Type;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.Iterables.getOnlyElement;
import static java.lang.String.format;
import static java.util.Collections.nCopies;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

public class QueryBuilder
{
    private static final Logger log = Logger.get(QueryBuilder.class);

    // not all databases support booleans, so use 1=1 and 1=0 instead
    private static final String ALWAYS_TRUE = "1=1";
    private static final String ALWAYS_FALSE = "1=0";

    private final JdbcClient client;

    public QueryBuilder(JdbcClient client)
    {
        this.client = requireNonNull(client, "jdbcClient is null");
    }

    /**
     * @deprecated Use #prepareSql and #prepareStatement instead.
     */
    @Deprecated
    public PreparedStatement buildSql(
            ConnectorSession session,
            Connection connection,
            RemoteTableName remoteTableName,
            Optional<List<List<JdbcColumnHandle>>> groupingSets,
            List<JdbcColumnHandle> columns,
            TupleDomain<ColumnHandle> tupleDomain,
            Optional<String> additionalPredicate,
            Function<String, String> sqlFunction)
            throws SQLException
    {
        PreparedQuery preparedQuery = prepareQuery(
                session,
                connection,
                new JdbcNamedRelationHandle(
                        // This dummy SchemaTableName is not used for anything here. It's provided only to implement the deprecated buildSql() method
                        new SchemaTableName(remoteTableName.getSchemaName().orElse(""), remoteTableName.getTableName()),
                        remoteTableName),
                groupingSets,
                columns,
                ImmutableMap.of(),
                tupleDomain,
                additionalPredicate);
        preparedQuery = preparedQuery.transformQuery(sqlFunction);
        return prepareStatement(session, connection, preparedQuery);
    }

    public PreparedQuery prepareQuery(
            ConnectorSession session,
            Connection connection,
            JdbcRelationHandle baseRelation,
            Optional<List<List<JdbcColumnHandle>>> groupingSets,
            List<JdbcColumnHandle> columns,
            Map<String, String> columnExpressions,
            TupleDomain<ColumnHandle> tupleDomain,
            Optional<String> additionalPredicate)
    {
        if (!tupleDomain.isNone()) {
            Map<ColumnHandle, Domain> domains = tupleDomain.getDomains().orElseThrow();
            columns.stream()
                    .filter(domains::containsKey)
                    .filter(column -> columnExpressions.containsKey(column.getColumnName()))
                    .findFirst()
                    .ifPresent(column -> { throw new IllegalArgumentException(format("Column %s has an expression and a constraint attached at the same time", column)); });
        }

        ImmutableList.Builder<QueryParameter> accumulator = ImmutableList.builder();

        String sql = "SELECT " + getProjection(columns, columnExpressions);
        if (baseRelation instanceof JdbcNamedRelationHandle) {
            sql += " FROM " + getRelation(((JdbcNamedRelationHandle) baseRelation).getRemoteTableName());
        }
        else if (baseRelation instanceof JdbcQueryRelationHandle) {
            PreparedQuery preparedQuery = ((JdbcQueryRelationHandle) baseRelation).getPreparedQuery();
            sql += " FROM (" + preparedQuery.getQuery() + ") o";
            accumulator.addAll(preparedQuery.getParameters());
        }
        else {
            throw new IllegalArgumentException("Unsupported relation: " + baseRelation);
        }

        List<String> clauses = toConjuncts(client, session, connection, tupleDomain, accumulator::add);
        if (additionalPredicate.isPresent()) {
            clauses = ImmutableList.<String>builder()
                    .addAll(clauses)
                    .add(additionalPredicate.get())
                    .build();
        }
        if (!clauses.isEmpty()) {
            sql += " WHERE " + Joiner.on(" AND ").join(clauses);
        }

        sql += getGroupBy(groupingSets);

        return new PreparedQuery(sql, accumulator.build());
    }

    public PreparedStatement prepareStatement(
            ConnectorSession session,
            Connection connection,
            PreparedQuery preparedQuery)
            throws SQLException
    {
        log.debug("Preparing query: %s", preparedQuery.getQuery());
        PreparedStatement statement = client.getPreparedStatement(connection, preparedQuery.getQuery());

        List<QueryParameter> parameters = preparedQuery.getParameters();
        for (int i = 0; i < parameters.size(); i++) {
            QueryParameter parameter = parameters.get(i);
            int parameterIndex = i + 1;
            WriteFunction writeFunction = getWriteFunction(session, connection, parameter.getJdbcType(), parameter.getType());
            Class<?> javaType = writeFunction.getJavaType();
            Object value = parameter.getValue()
                    // The value must be present, since QueryBuilder never creates null parameters. Values coming from Domain's ValueSet are non-null, and
                    // nullable domains are handled explicitly, with SQL syntax.
                    .orElseThrow(() -> new VerifyException("Value is missing"));
            if (javaType == boolean.class) {
                ((BooleanWriteFunction) writeFunction).set(statement, parameterIndex, (boolean) value);
            }
            else if (javaType == long.class) {
                ((LongWriteFunction) writeFunction).set(statement, parameterIndex, (long) value);
            }
            else if (javaType == double.class) {
                ((DoubleWriteFunction) writeFunction).set(statement, parameterIndex, (double) value);
            }
            else if (javaType == Slice.class) {
                ((SliceWriteFunction) writeFunction).set(statement, parameterIndex, (Slice) value);
            }
            else {
                ((ObjectWriteFunction) writeFunction).set(statement, parameterIndex, value);
            }
        }

        return statement;
    }

    protected String getRelation(RemoteTableName remoteTableName)
    {
        return client.quoted(remoteTableName);
    }

    protected String getProjection(List<JdbcColumnHandle> columns, Map<String, String> columnExpressions)
    {
        if (columns.isEmpty()) {
            return "1 x";
        }
        return columns.stream()
                .map(jdbcColumnHandle -> {
                    String columnAlias = client.quoted(jdbcColumnHandle.getColumnName());
                    String expression = columnExpressions.get(jdbcColumnHandle.getColumnName());
                    if (expression == null) {
                        return columnAlias;
                    }
                    return format("%s AS %s", expression, columnAlias);
                })
                .collect(joining(", "));
    }

    private static Domain pushDownDomain(JdbcClient client, ConnectorSession session, Connection connection, JdbcColumnHandle column, Domain domain)
    {
        return client.toColumnMapping(session, connection, column.getJdbcTypeHandle())
                .orElseThrow(() -> new IllegalStateException(format("Unsupported type %s with handle %s", column.getColumnType(), column.getJdbcTypeHandle())))
                .getPredicatePushdownController().apply(session, domain).getPushedDown();
    }

    private List<String> toConjuncts(
            JdbcClient client,
            ConnectorSession session,
            Connection connection,
            TupleDomain<ColumnHandle> tupleDomain,
            Consumer<QueryParameter> accumulator)
    {
        if (tupleDomain.isNone()) {
            return ImmutableList.of(ALWAYS_FALSE);
        }
        ImmutableList.Builder<String> builder = ImmutableList.builder();
        for (Map.Entry<ColumnHandle, Domain> entry : tupleDomain.getDomains().get().entrySet()) {
            JdbcColumnHandle column = ((JdbcColumnHandle) entry.getKey());
            Domain domain = pushDownDomain(client, session, connection, column, entry.getValue());
            builder.add(toPredicate(session, connection, column, domain, accumulator));
        }
        return builder.build();
    }

    private String toPredicate(ConnectorSession session, Connection connection, JdbcColumnHandle column, Domain domain, Consumer<QueryParameter> accumulator)
    {
        if (domain.getValues().isNone()) {
            return domain.isNullAllowed() ? client.quoted(column.getColumnName()) + " IS NULL" : ALWAYS_FALSE;
        }

        if (domain.getValues().isAll()) {
            return domain.isNullAllowed() ? ALWAYS_TRUE : client.quoted(column.getColumnName()) + " IS NOT NULL";
        }

        String predicate = toPredicate(session, connection, column, domain.getValues(), accumulator);
        if (!domain.isNullAllowed()) {
            return predicate;
        }
        return format("(%s OR %s IS NULL)", predicate, client.quoted(column.getColumnName()));
    }

    private String toPredicate(ConnectorSession session, Connection connection, JdbcColumnHandle column, ValueSet valueSet, Consumer<QueryParameter> accumulator)
    {
        checkArgument(!valueSet.isNone(), "none values should be handled earlier");

        if (!valueSet.isDiscreteSet()) {
            ValueSet complement = valueSet.complement();
            if (complement.isDiscreteSet()) {
                return format("NOT (%s)", toPredicate(session, connection, column, complement, accumulator));
            }
        }

        JdbcTypeHandle jdbcType = column.getJdbcTypeHandle();
        Type type = column.getColumnType();
        WriteFunction writeFunction = getWriteFunction(session, connection, jdbcType, type);

        List<String> disjuncts = new ArrayList<>();
        List<Object> singleValues = new ArrayList<>();
        for (Range range : valueSet.getRanges().getOrderedRanges()) {
            checkState(!range.isAll()); // Already checked
            if (range.isSingleValue()) {
                singleValues.add(range.getLow().getValue());
            }
            else {
                List<String> rangeConjuncts = new ArrayList<>();
                if (!range.getLow().isLowerUnbounded()) {
                    switch (range.getLow().getBound()) {
                        case ABOVE:
                            rangeConjuncts.add(toPredicate(column, jdbcType, type, writeFunction, ">", range.getLow().getValue(), accumulator));
                            break;
                        case EXACTLY:
                            rangeConjuncts.add(toPredicate(column, jdbcType, type, writeFunction, ">=", range.getLow().getValue(), accumulator));
                            break;
                        case BELOW:
                            throw new IllegalArgumentException("Low marker should never use BELOW bound");
                        default:
                            throw new AssertionError("Unhandled bound: " + range.getLow().getBound());
                    }
                }
                if (!range.getHigh().isUpperUnbounded()) {
                    switch (range.getHigh().getBound()) {
                        case ABOVE:
                            throw new IllegalArgumentException("High marker should never use ABOVE bound");
                        case EXACTLY:
                            rangeConjuncts.add(toPredicate(column, jdbcType, type, writeFunction, "<=", range.getHigh().getValue(), accumulator));
                            break;
                        case BELOW:
                            rangeConjuncts.add(toPredicate(column, jdbcType, type, writeFunction, "<", range.getHigh().getValue(), accumulator));
                            break;
                        default:
                            throw new AssertionError("Unhandled bound: " + range.getHigh().getBound());
                    }
                }
                // If rangeConjuncts is null, then the range was ALL, which should already have been checked for
                checkState(!rangeConjuncts.isEmpty());
                if (rangeConjuncts.size() == 1) {
                    disjuncts.add(getOnlyElement(rangeConjuncts));
                }
                else {
                    disjuncts.add("(" + Joiner.on(" AND ").join(rangeConjuncts) + ")");
                }
            }
        }

        // Add back all of the possible single values either as an equality or an IN predicate
        if (singleValues.size() == 1) {
            disjuncts.add(toPredicate(column, jdbcType, type, writeFunction, "=", getOnlyElement(singleValues), accumulator));
        }
        else if (singleValues.size() > 1) {
            for (Object value : singleValues) {
                accumulator.accept(new QueryParameter(jdbcType, type, Optional.of(value)));
            }
            String values = Joiner.on(",").join(nCopies(singleValues.size(), writeFunction.getBindExpression()));
            disjuncts.add(client.quoted(column.getColumnName()) + " IN (" + values + ")");
        }

        checkState(!disjuncts.isEmpty());
        if (disjuncts.size() == 1) {
            return getOnlyElement(disjuncts);
        }
        return "(" + Joiner.on(" OR ").join(disjuncts) + ")";
    }

    private String toPredicate(JdbcColumnHandle column, JdbcTypeHandle jdbcType, Type type, WriteFunction writeFunction, String operator, Object value, Consumer<QueryParameter> accumulator)
    {
        accumulator.accept(new QueryParameter(jdbcType, type, Optional.of(value)));
        return format("%s %s %s", client.quoted(column.getColumnName()), operator, writeFunction.getBindExpression());
    }

    private WriteFunction getWriteFunction(ConnectorSession session, Connection connection, JdbcTypeHandle jdbcType, Type type)
    {
        WriteFunction writeFunction = client.toColumnMapping(session, connection, jdbcType)
                .orElseThrow(() -> new VerifyException(format("Unsupported type %s with handle %s", type, jdbcType)))
                .getWriteFunction();
        verify(writeFunction.getJavaType() == type.getJavaType(), "Java type mismatch: %s, %s", writeFunction, type);
        return writeFunction;
    }

    private String getGroupBy(Optional<List<List<JdbcColumnHandle>>> groupingSets)
    {
        if (groupingSets.isEmpty()) {
            return "";
        }

        verify(!groupingSets.get().isEmpty());
        if (groupingSets.get().size() == 1) {
            List<JdbcColumnHandle> groupingSet = getOnlyElement(groupingSets.get());
            if (groupingSet.isEmpty()) {
                // global aggregation
                return "";
            }
            return " GROUP BY " + groupingSet.stream()
                    .map(JdbcColumnHandle::getColumnName)
                    .map(client::quoted)
                    .collect(joining(", "));
        }
        return " GROUP BY GROUPING SETS " +
                groupingSets.get().stream()
                        .map(groupingSet -> groupingSet.stream()
                                .map(JdbcColumnHandle::getColumnName)
                                .map(client::quoted)
                                .collect(joining(", ", "(", ")")))
                        .collect(joining(", ", "(", ")"));
    }
}
