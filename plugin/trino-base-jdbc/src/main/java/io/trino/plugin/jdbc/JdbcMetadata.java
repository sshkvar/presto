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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.airlift.slice.Slice;
import io.trino.plugin.jdbc.PredicatePushdownController.DomainPushdownResult;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.AggregateFunction;
import io.trino.spi.connector.AggregationApplicationResult;
import io.trino.spi.connector.Assignment;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ConnectorInsertTableHandle;
import io.trino.spi.connector.ConnectorMetadata;
import io.trino.spi.connector.ConnectorNewTableLayout;
import io.trino.spi.connector.ConnectorOutputMetadata;
import io.trino.spi.connector.ConnectorOutputTableHandle;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.ConnectorTableMetadata;
import io.trino.spi.connector.ConnectorTableProperties;
import io.trino.spi.connector.Constraint;
import io.trino.spi.connector.ConstraintApplicationResult;
import io.trino.spi.connector.LimitApplicationResult;
import io.trino.spi.connector.ProjectionApplicationResult;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.connector.SchemaTablePrefix;
import io.trino.spi.connector.SystemTable;
import io.trino.spi.connector.TableNotFoundException;
import io.trino.spi.connector.TableScanRedirectApplicationResult;
import io.trino.spi.expression.ConnectorExpression;
import io.trino.spi.expression.Variable;
import io.trino.spi.predicate.Domain;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.security.TrinoPrincipal;
import io.trino.spi.statistics.ComputedStatistics;
import io.trino.spi.statistics.TableStatistics;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.base.Functions.identity;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static io.trino.plugin.jdbc.JdbcMetadataSessionProperties.isAggregationPushdownEnabled;
import static io.trino.spi.StandardErrorCode.PERMISSION_DENIED;
import static java.util.Objects.requireNonNull;

public class JdbcMetadata
        implements ConnectorMetadata
{
    private static final String SYNTHETIC_COLUMN_NAME_PREFIX = "_pfgnrtd_";

    private final JdbcClient jdbcClient;
    private final boolean allowDropTable;

    private final AtomicReference<Runnable> rollbackAction = new AtomicReference<>();

    public JdbcMetadata(JdbcClient jdbcClient, boolean allowDropTable)
    {
        this.jdbcClient = requireNonNull(jdbcClient, "client is null");
        this.allowDropTable = allowDropTable;
    }

    @Override
    public boolean schemaExists(ConnectorSession session, String schemaName)
    {
        return jdbcClient.schemaExists(session, schemaName);
    }

    @Override
    public List<String> listSchemaNames(ConnectorSession session)
    {
        return ImmutableList.copyOf(jdbcClient.getSchemaNames(session));
    }

    @Override
    public JdbcTableHandle getTableHandle(ConnectorSession session, SchemaTableName tableName)
    {
        return jdbcClient.getTableHandle(session, tableName)
                .orElse(null);
    }

    @Override
    public Optional<SystemTable> getSystemTable(ConnectorSession session, SchemaTableName tableName)
    {
        return jdbcClient.getSystemTable(session, tableName);
    }

    @Override
    public Optional<ConstraintApplicationResult<ConnectorTableHandle>> applyFilter(ConnectorSession session, ConnectorTableHandle table, Constraint constraint)
    {
        JdbcTableHandle handle = (JdbcTableHandle) table;

        TupleDomain<ColumnHandle> oldDomain = handle.getConstraint();
        TupleDomain<ColumnHandle> newDomain = oldDomain.intersect(constraint.getSummary());

        TupleDomain<ColumnHandle> remainingFilter;
        if (newDomain.isNone()) {
            remainingFilter = TupleDomain.all();
        }
        else {
            Map<ColumnHandle, Domain> domains = newDomain.getDomains().orElseThrow();
            List<JdbcColumnHandle> columnHandles = domains.keySet().stream()
                    .map(JdbcColumnHandle.class::cast)
                    .collect(toImmutableList());
            List<ColumnMapping> columnMappings = jdbcClient.toColumnMappings(
                    session,
                    columnHandles.stream()
                            .map(JdbcColumnHandle::getJdbcTypeHandle)
                            .collect(toImmutableList()));

            Map<ColumnHandle, Domain> supported = new HashMap<>();
            Map<ColumnHandle, Domain> unsupported = new HashMap<>();
            for (int i = 0; i < columnHandles.size(); i++) {
                JdbcColumnHandle column = columnHandles.get(i);
                ColumnMapping mapping = columnMappings.get(i);
                DomainPushdownResult pushdownResult = mapping.getPredicatePushdownController().apply(session, domains.get(column));
                supported.put(column, pushdownResult.getPushedDown());
                unsupported.put(column, pushdownResult.getRemainingFilter());
            }

            newDomain = TupleDomain.withColumnDomains(supported);
            remainingFilter = TupleDomain.withColumnDomains(unsupported);
        }

        if (oldDomain.equals(newDomain)) {
            return Optional.empty();
        }

        handle = new JdbcTableHandle(
                handle.getRelationHandle(),
                newDomain,
                handle.getLimit(),
                handle.getColumns());

        return Optional.of(new ConstraintApplicationResult<>(handle, remainingFilter));
    }

    private JdbcTableHandle flushAttributesAsQuery(ConnectorSession session, JdbcTableHandle handle)
    {
        List<JdbcColumnHandle> columns = jdbcClient.getColumns(session, handle);
        PreparedQuery preparedQuery = jdbcClient.prepareQuery(session, handle, Optional.empty(), columns, ImmutableMap.of());
        return new JdbcTableHandle(
                new JdbcQueryRelationHandle(preparedQuery),
                TupleDomain.all(),
                OptionalLong.empty(),
                Optional.of(columns));
    }

    @Override
    public Optional<ProjectionApplicationResult<ConnectorTableHandle>> applyProjection(
            ConnectorSession session,
            ConnectorTableHandle table,
            List<ConnectorExpression> projections,
            Map<String, ColumnHandle> assignments)
    {
        JdbcTableHandle handle = (JdbcTableHandle) table;

        List<JdbcColumnHandle> newColumns = assignments.values().stream()
                .map(JdbcColumnHandle.class::cast)
                .collect(toImmutableList());

        if (handle.getColumns().isPresent() && containSameElements(newColumns, handle.getColumns().get())) {
            return Optional.empty();
        }

        return Optional.of(new ProjectionApplicationResult<>(
                new JdbcTableHandle(
                        handle.getRelationHandle(),
                        handle.getConstraint(),
                        handle.getLimit(),
                        Optional.of(newColumns)),
                projections,
                assignments.entrySet().stream()
                        .map(assignment -> new Assignment(
                                assignment.getKey(),
                                assignment.getValue(),
                                ((JdbcColumnHandle) assignment.getValue()).getColumnType()))
                        .collect(toImmutableList())));
    }

    @Override
    public Optional<AggregationApplicationResult<ConnectorTableHandle>> applyAggregation(
            ConnectorSession session,
            ConnectorTableHandle table,
            List<AggregateFunction> aggregates,
            Map<String, ColumnHandle> assignments,
            List<List<ColumnHandle>> groupingSets)
    {
        if (!isAggregationPushdownEnabled(session)) {
            return Optional.empty();
        }

        JdbcTableHandle handle = (JdbcTableHandle) table;

        // Global aggregation is represented by [[]]
        verify(!groupingSets.isEmpty(), "No grouping sets provided");

        if (groupingSets.size() > 1 && !jdbcClient.supportsGroupingSets()) {
            return Optional.empty();
        }

        if (!jdbcClient.supportsAggregationPushdown(session, handle, groupingSets)) {
            // JDBC client implementation prevents pushdown for the given table
            return Optional.empty();
        }

        if (handle.getLimit().isPresent()) {
            handle = flushAttributesAsQuery(session, handle);
        }

        List<JdbcColumnHandle> columns = jdbcClient.getColumns(session, handle);
        Map<String, JdbcColumnHandle> columnByName = columns.stream()
                .collect(toImmutableMap(JdbcColumnHandle::getColumnName, identity()));

        int syntheticNextIdentifier = 1;

        ImmutableList.Builder<JdbcColumnHandle> newColumns = ImmutableList.builder();
        ImmutableList.Builder<ConnectorExpression> projections = ImmutableList.builder();
        ImmutableList.Builder<Assignment> resultAssignments = ImmutableList.builder();
        ImmutableMap.Builder<String, String> expressions = ImmutableMap.builder();
        for (AggregateFunction aggregate : aggregates) {
            Optional<JdbcExpression> expression = jdbcClient.implementAggregation(session, aggregate, assignments);
            if (expression.isEmpty()) {
                return Optional.empty();
            }

            while (columnByName.containsKey(SYNTHETIC_COLUMN_NAME_PREFIX + syntheticNextIdentifier)) {
                syntheticNextIdentifier++;
            }

            String columnName = SYNTHETIC_COLUMN_NAME_PREFIX + syntheticNextIdentifier;
            JdbcColumnHandle newColumn = JdbcColumnHandle.builder()
                    .setColumnName(columnName)
                    .setJdbcTypeHandle(expression.get().getJdbcTypeHandle())
                    .setColumnType(aggregate.getOutputType())
                    .setComment(Optional.of("synthetic"))
                    .build();

            newColumns.add(newColumn);
            projections.add(new Variable(newColumn.getColumnName(), aggregate.getOutputType()));
            resultAssignments.add(new Assignment(newColumn.getColumnName(), newColumn, aggregate.getOutputType()));
            expressions.put(columnName, expression.get().getExpression());

            syntheticNextIdentifier++;
        }

        List<List<JdbcColumnHandle>> groupingSetsAsJdbcColumnHandles = groupingSets.stream()
                .map(groupingSet -> groupingSet.stream()
                        .map(JdbcColumnHandle.class::cast)
                        .collect(toImmutableList()))
                .collect(toImmutableList());

        List<JdbcColumnHandle> newColumnsList = newColumns.build();

        PreparedQuery preparedQuery = jdbcClient.prepareQuery(
                session,
                handle,
                Optional.of(groupingSetsAsJdbcColumnHandles),
                ImmutableList.<JdbcColumnHandle>builder()
                        .addAll(groupingSetsAsJdbcColumnHandles.stream()
                                .flatMap(List::stream)
                                .distinct()
                                .iterator())
                        .addAll(newColumnsList)
                        .build(),
                expressions.build());
        handle = new JdbcTableHandle(
                new JdbcQueryRelationHandle(preparedQuery),
                TupleDomain.all(),
                OptionalLong.empty(),
                Optional.of(newColumnsList));

        return Optional.of(new AggregationApplicationResult<>(handle, projections.build(), resultAssignments.build(), ImmutableMap.of()));
    }

    @Override
    public Optional<LimitApplicationResult<ConnectorTableHandle>> applyLimit(ConnectorSession session, ConnectorTableHandle table, long limit)
    {
        JdbcTableHandle handle = (JdbcTableHandle) table;

        if (!jdbcClient.supportsLimit()) {
            return Optional.empty();
        }

        if (handle.getLimit().isPresent() && handle.getLimit().getAsLong() <= limit) {
            return Optional.empty();
        }

        handle = new JdbcTableHandle(
                handle.getRelationHandle(),
                handle.getConstraint(),
                OptionalLong.of(limit),
                handle.getColumns());

        return Optional.of(new LimitApplicationResult<>(handle, jdbcClient.isLimitGuaranteed(session)));
    }

    @Override
    public Optional<TableScanRedirectApplicationResult> applyTableScanRedirect(ConnectorSession session, ConnectorTableHandle table)
    {
        JdbcTableHandle tableHandle = (JdbcTableHandle) table;
        return jdbcClient.getTableScanRedirection(session, tableHandle);
    }

    @Override
    public boolean usesLegacyTableLayouts()
    {
        return false;
    }

    @Override
    public ConnectorTableProperties getTableProperties(ConnectorSession session, ConnectorTableHandle table)
    {
        return new ConnectorTableProperties();
    }

    @Override
    public ConnectorTableMetadata getTableMetadata(ConnectorSession session, ConnectorTableHandle table)
    {
        JdbcTableHandle handle = (JdbcTableHandle) table;

        ImmutableList.Builder<ColumnMetadata> columnMetadata = ImmutableList.builder();
        for (JdbcColumnHandle column : jdbcClient.getColumns(session, handle)) {
            columnMetadata.add(column.getColumnMetadata());
        }
        SchemaTableName schemaTableName = handle.isNamedRelation()
                ? handle.getSchemaTableName()
                // TODO (https://github.com/trinodb/trino/issues/6694) SchemaTableName should not be required for synthetic ConnectorTableHandle
                : new SchemaTableName("_prepared", "query");
        return new ConnectorTableMetadata(schemaTableName, columnMetadata.build(), jdbcClient.getTableProperties(session, handle));
    }

    @Override
    public List<SchemaTableName> listTables(ConnectorSession session, Optional<String> schemaName)
    {
        return jdbcClient.getTableNames(session, schemaName);
    }

    @Override
    public Map<String, ColumnHandle> getColumnHandles(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        return jdbcClient.getColumns(session, (JdbcTableHandle) tableHandle).stream()
                .collect(toImmutableMap(columnHandle -> columnHandle.getColumnMetadata().getName(), identity()));
    }

    @Override
    public Map<SchemaTableName, List<ColumnMetadata>> listTableColumns(ConnectorSession session, SchemaTablePrefix prefix)
    {
        ImmutableMap.Builder<SchemaTableName, List<ColumnMetadata>> columns = ImmutableMap.builder();
        List<SchemaTableName> tables = prefix.toOptionalSchemaTableName()
                .<List<SchemaTableName>>map(ImmutableList::of)
                .orElseGet(() -> listTables(session, prefix.getSchema()));
        for (SchemaTableName tableName : tables) {
            try {
                jdbcClient.getTableHandle(session, tableName)
                        .ifPresent(tableHandle -> columns.put(tableName, getTableMetadata(session, tableHandle).getColumns()));
            }
            catch (TableNotFoundException e) {
                // table disappeared during listing operation
            }
        }
        return columns.build();
    }

    @Override
    public ColumnMetadata getColumnMetadata(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnHandle columnHandle)
    {
        return ((JdbcColumnHandle) columnHandle).getColumnMetadata();
    }

    @Override
    public void dropTable(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        if (!allowDropTable) {
            throw new TrinoException(PERMISSION_DENIED, "DROP TABLE is disabled in this catalog");
        }
        JdbcTableHandle handle = (JdbcTableHandle) tableHandle;
        verify(!handle.isSynthetic(), "Not a table reference: %s", handle);
        jdbcClient.dropTable(session, handle);
    }

    @Override
    public ConnectorOutputTableHandle beginCreateTable(ConnectorSession session, ConnectorTableMetadata tableMetadata, Optional<ConnectorNewTableLayout> layout)
    {
        JdbcOutputTableHandle handle = jdbcClient.beginCreateTable(session, tableMetadata);
        setRollback(() -> jdbcClient.rollbackCreateTable(session, handle));
        return handle;
    }

    @Override
    public void createTable(ConnectorSession session, ConnectorTableMetadata tableMetadata, boolean ignoreExisting)
    {
        jdbcClient.createTable(session, tableMetadata);
    }

    @Override
    public Optional<ConnectorOutputMetadata> finishCreateTable(ConnectorSession session, ConnectorOutputTableHandle tableHandle, Collection<Slice> fragments, Collection<ComputedStatistics> computedStatistics)
    {
        JdbcOutputTableHandle handle = (JdbcOutputTableHandle) tableHandle;
        jdbcClient.commitCreateTable(session, handle);
        return Optional.empty();
    }

    private void setRollback(Runnable action)
    {
        checkState(rollbackAction.compareAndSet(null, action), "rollback action is already set");
    }

    public void rollback()
    {
        Optional.ofNullable(rollbackAction.getAndSet(null)).ifPresent(Runnable::run);
    }

    @Override
    public ConnectorInsertTableHandle beginInsert(ConnectorSession session, ConnectorTableHandle tableHandle, List<ColumnHandle> columns)
    {
        verify(!((JdbcTableHandle) tableHandle).isSynthetic(), "Not a table reference: %s", tableHandle);
        List<JdbcColumnHandle> columnHandles = columns.stream()
                .map(JdbcColumnHandle.class::cast)
                .collect(toImmutableList());
        JdbcOutputTableHandle handle = jdbcClient.beginInsertTable(session, (JdbcTableHandle) tableHandle, columnHandles);
        setRollback(() -> jdbcClient.rollbackCreateTable(session, handle));
        return handle;
    }

    @Override
    public boolean supportsMissingColumnsOnInsert()
    {
        return true;
    }

    @Override
    public Optional<ConnectorOutputMetadata> finishInsert(ConnectorSession session, ConnectorInsertTableHandle tableHandle, Collection<Slice> fragments, Collection<ComputedStatistics> computedStatistics)
    {
        JdbcOutputTableHandle jdbcInsertHandle = (JdbcOutputTableHandle) tableHandle;
        jdbcClient.finishInsertTable(session, jdbcInsertHandle);
        return Optional.empty();
    }

    @Override
    public void setColumnComment(ConnectorSession session, ConnectorTableHandle table, ColumnHandle column, Optional<String> comment)
    {
        JdbcTableHandle tableHandle = (JdbcTableHandle) table;
        JdbcColumnHandle columnHandle = (JdbcColumnHandle) column;
        verify(!tableHandle.isSynthetic(), "Not a table reference: %s", tableHandle);
        jdbcClient.setColumnComment(session, tableHandle, columnHandle, comment);
    }

    @Override
    public void addColumn(ConnectorSession session, ConnectorTableHandle table, ColumnMetadata columnMetadata)
    {
        JdbcTableHandle tableHandle = (JdbcTableHandle) table;
        verify(!tableHandle.isSynthetic(), "Not a table reference: %s", tableHandle);
        jdbcClient.addColumn(session, tableHandle, columnMetadata);
    }

    @Override
    public void dropColumn(ConnectorSession session, ConnectorTableHandle table, ColumnHandle column)
    {
        JdbcTableHandle tableHandle = (JdbcTableHandle) table;
        JdbcColumnHandle columnHandle = (JdbcColumnHandle) column;
        verify(!tableHandle.isSynthetic(), "Not a table reference: %s", tableHandle);
        jdbcClient.dropColumn(session, tableHandle, columnHandle);
    }

    @Override
    public void renameColumn(ConnectorSession session, ConnectorTableHandle table, ColumnHandle column, String target)
    {
        JdbcTableHandle tableHandle = (JdbcTableHandle) table;
        JdbcColumnHandle columnHandle = (JdbcColumnHandle) column;
        verify(!tableHandle.isSynthetic(), "Not a table reference: %s", tableHandle);
        jdbcClient.renameColumn(session, tableHandle, columnHandle, target);
    }

    @Override
    public void renameTable(ConnectorSession session, ConnectorTableHandle table, SchemaTableName newTableName)
    {
        JdbcTableHandle tableHandle = (JdbcTableHandle) table;
        verify(!tableHandle.isSynthetic(), "Not a table reference: %s", tableHandle);
        jdbcClient.renameTable(session, tableHandle, newTableName);
    }

    @Override
    public TableStatistics getTableStatistics(ConnectorSession session, ConnectorTableHandle tableHandle, Constraint constraint)
    {
        JdbcTableHandle handle = (JdbcTableHandle) tableHandle;
        return jdbcClient.getTableStatistics(session, handle, constraint.getSummary());
    }

    @Override
    public void createSchema(ConnectorSession session, String schemaName, Map<String, Object> properties, TrinoPrincipal owner)
    {
        jdbcClient.createSchema(session, schemaName);
    }

    @Override
    public void dropSchema(ConnectorSession session, String schemaName)
    {
        jdbcClient.dropSchema(session, schemaName);
    }

    private static boolean containSameElements(Iterable<? extends ColumnHandle> first, Iterable<? extends ColumnHandle> second)
    {
        return ImmutableSet.copyOf(first).equals(ImmutableSet.copyOf(second));
    }
}
