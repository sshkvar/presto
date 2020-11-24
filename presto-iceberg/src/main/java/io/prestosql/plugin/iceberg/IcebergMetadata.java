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
package io.prestosql.plugin.iceberg;

import com.google.common.base.Splitter;
import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.airlift.json.JsonCodec;
import io.airlift.log.Logger;
import io.airlift.slice.Slice;
import io.prestosql.plugin.base.classloader.ClassLoaderSafeSystemTable;
import io.prestosql.plugin.hive.HdfsEnvironment;
import io.prestosql.plugin.hive.HdfsEnvironment.HdfsContext;
import io.prestosql.plugin.hive.HiveSchemaProperties;
import io.prestosql.plugin.hive.HiveWrittenPartitions;
import io.prestosql.plugin.hive.TableAlreadyExistsException;
import io.prestosql.plugin.hive.authentication.HiveIdentity;
import io.prestosql.plugin.hive.metastore.Column;
import io.prestosql.plugin.hive.metastore.Database;
import io.prestosql.plugin.hive.metastore.HiveMetastore;
import io.prestosql.plugin.hive.metastore.HivePrincipal;
import io.prestosql.plugin.hive.metastore.PrincipalPrivileges;
import io.prestosql.plugin.hive.metastore.Table;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.connector.CatalogSchemaName;
import io.prestosql.spi.connector.ColumnHandle;
import io.prestosql.spi.connector.ColumnMetadata;
import io.prestosql.spi.connector.ConnectorInsertTableHandle;
import io.prestosql.spi.connector.ConnectorMaterializedViewDefinition;
import io.prestosql.spi.connector.ConnectorMetadata;
import io.prestosql.spi.connector.ConnectorNewTableLayout;
import io.prestosql.spi.connector.ConnectorOutputMetadata;
import io.prestosql.spi.connector.ConnectorOutputTableHandle;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.connector.ConnectorTableHandle;
import io.prestosql.spi.connector.ConnectorTableMetadata;
import io.prestosql.spi.connector.ConnectorTableProperties;
import io.prestosql.spi.connector.Constraint;
import io.prestosql.spi.connector.ConstraintApplicationResult;
import io.prestosql.spi.connector.MaterializedViewFreshness;
import io.prestosql.spi.connector.MaterializedViewNotFoundException;
import io.prestosql.spi.connector.SchemaNotFoundException;
import io.prestosql.spi.connector.SchemaTableName;
import io.prestosql.spi.connector.SchemaTablePrefix;
import io.prestosql.spi.connector.SystemTable;
import io.prestosql.spi.connector.TableNotFoundException;
import io.prestosql.spi.predicate.Domain;
import io.prestosql.spi.predicate.TupleDomain;
import io.prestosql.spi.security.PrestoPrincipal;
import io.prestosql.spi.statistics.ComputedStatistics;
import io.prestosql.spi.statistics.TableStatistics;
import io.prestosql.spi.type.TypeManager;
import org.apache.hadoop.fs.Path;
import org.apache.iceberg.AppendFiles;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.PartitionField;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.PartitionSpecParser;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.TypeUtil;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.types.Types.NestedField;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;

import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.prestosql.plugin.hive.HiveErrorCode.HIVE_INVALID_METADATA;
import static io.prestosql.plugin.hive.HiveMetadata.PRESTO_QUERY_ID_NAME;
import static io.prestosql.plugin.hive.HiveMetadata.STORAGE_TABLE;
import static io.prestosql.plugin.hive.HiveMetadata.TABLE_COMMENT;
import static io.prestosql.plugin.hive.HiveType.HIVE_STRING;
import static io.prestosql.plugin.hive.ViewReaderUtil.PRESTO_VIEW_FLAG;
import static io.prestosql.plugin.hive.ViewReaderUtil.PrestoViewReader.decodeMaterializedViewData;
import static io.prestosql.plugin.hive.ViewReaderUtil.encodeMaterializedViewData;
import static io.prestosql.plugin.hive.metastore.MetastoreUtil.buildInitialPrivilegeSet;
import static io.prestosql.plugin.hive.metastore.StorageFormat.VIEW_STORAGE_FORMAT;
import static io.prestosql.plugin.hive.util.HiveWriteUtils.getTableDefaultLocation;
import static io.prestosql.plugin.iceberg.ExpressionConverter.toIcebergExpression;
import static io.prestosql.plugin.iceberg.IcebergErrorCode.ICEBERG_INVALID_METADATA;
import static io.prestosql.plugin.iceberg.IcebergSchemaProperties.getSchemaLocation;
import static io.prestosql.plugin.iceberg.IcebergTableProperties.FILE_FORMAT_PROPERTY;
import static io.prestosql.plugin.iceberg.IcebergTableProperties.PARTITIONING_PROPERTY;
import static io.prestosql.plugin.iceberg.IcebergTableProperties.getFileFormat;
import static io.prestosql.plugin.iceberg.IcebergTableProperties.getPartitioning;
import static io.prestosql.plugin.iceberg.IcebergTableProperties.getTableLocation;
import static io.prestosql.plugin.iceberg.IcebergUtil.getColumns;
import static io.prestosql.plugin.iceberg.IcebergUtil.getDataPath;
import static io.prestosql.plugin.iceberg.IcebergUtil.getFileFormat;
import static io.prestosql.plugin.iceberg.IcebergUtil.getIcebergTable;
import static io.prestosql.plugin.iceberg.IcebergUtil.getTableComment;
import static io.prestosql.plugin.iceberg.IcebergUtil.isIcebergTable;
import static io.prestosql.plugin.iceberg.PartitionFields.parsePartitionFields;
import static io.prestosql.plugin.iceberg.PartitionFields.toPartitionFields;
import static io.prestosql.plugin.iceberg.TableType.DATA;
import static io.prestosql.plugin.iceberg.TypeConverter.toIcebergType;
import static io.prestosql.plugin.iceberg.TypeConverter.toPrestoType;
import static io.prestosql.spi.StandardErrorCode.ALREADY_EXISTS;
import static io.prestosql.spi.StandardErrorCode.INVALID_SCHEMA_PROPERTY;
import static io.prestosql.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.prestosql.spi.StandardErrorCode.SCHEMA_NOT_EMPTY;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static java.util.Collections.singletonList;
import static java.util.Objects.requireNonNull;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static org.apache.hadoop.hive.metastore.TableType.VIRTUAL_VIEW;
import static org.apache.iceberg.BaseMetastoreTableOperations.ICEBERG_TABLE_TYPE_VALUE;
import static org.apache.iceberg.BaseMetastoreTableOperations.TABLE_TYPE_PROP;
import static org.apache.iceberg.TableMetadata.newTableMetadata;
import static org.apache.iceberg.TableProperties.DEFAULT_FILE_FORMAT;
import static org.apache.iceberg.TableProperties.DEFAULT_FILE_FORMAT_DEFAULT;
import static org.apache.iceberg.Transactions.createTableTransaction;

public class IcebergMetadata
        implements ConnectorMetadata
{
    private static final Logger log = Logger.get(IcebergMetadata.class);
    public static final String DEPENDS_ON_TABLES = "dependsOnTables";
    private final HiveMetastore metastore;
    private final HdfsEnvironment hdfsEnvironment;
    private final TypeManager typeManager;
    private final JsonCodec<CommitTaskData> commitTaskCodec;

    private final Map<String, Optional<Long>> snapshotIds = new ConcurrentHashMap<>();
    private final boolean useUniqueTableLocation;

    private Transaction transaction;

    public IcebergMetadata(
            HiveMetastore metastore,
            HdfsEnvironment hdfsEnvironment,
            TypeManager typeManager,
            JsonCodec<CommitTaskData> commitTaskCodec,
            boolean useUniqueTableLocation)
    {
        this.metastore = requireNonNull(metastore, "metastore is null");
        this.hdfsEnvironment = requireNonNull(hdfsEnvironment, "hdfsEnvironment is null");
        this.typeManager = requireNonNull(typeManager, "typeManager is null");
        this.commitTaskCodec = requireNonNull(commitTaskCodec, "commitTaskCodec is null");
        this.useUniqueTableLocation = useUniqueTableLocation;
    }

    @Override
    public List<String> listSchemaNames(ConnectorSession session)
    {
        return metastore.getAllDatabases();
    }

    @Override
    public Map<String, Object> getSchemaProperties(ConnectorSession session, CatalogSchemaName schemaName)
    {
        Optional<Database> db = metastore.getDatabase(schemaName.getSchemaName());
        if (db.isPresent()) {
            return HiveSchemaProperties.fromDatabase(db.get());
        }

        throw new SchemaNotFoundException(schemaName.getSchemaName());
    }

    @Override
    public Optional<PrestoPrincipal> getSchemaOwner(ConnectorSession session, CatalogSchemaName schemaName)
    {
        Optional<Database> database = metastore.getDatabase(schemaName.getSchemaName());
        if (database.isPresent()) {
            return database.flatMap(db -> Optional.of(new PrestoPrincipal(db.getOwnerType(), db.getOwnerName())));
        }

        throw new SchemaNotFoundException(schemaName.getSchemaName());
    }

    @Override
    public IcebergTableHandle getTableHandle(ConnectorSession session, SchemaTableName tableName)
    {
        IcebergTableName name = IcebergTableName.from(tableName.getTableName());
        verify(name.getTableType() == DATA, "Wrong table type: " + name.getTableType());

        Optional<Table> hiveTable = metastore.getTable(new HiveIdentity(session), tableName.getSchemaName(), name.getTableName());
        if (hiveTable.isEmpty()) {
            return null;
        }
        if (!isIcebergTable(hiveTable.get())) {
            throw new UnknownTableTypeException(tableName);
        }

        org.apache.iceberg.Table table = getIcebergTable(metastore, hdfsEnvironment, session, hiveTable.get().getSchemaTableName());
        Optional<Long> snapshotId = getSnapshotId(table, name.getSnapshotId());

        return new IcebergTableHandle(
                tableName.getSchemaName(),
                name.getTableName(),
                name.getTableType(),
                snapshotId,
                TupleDomain.all());
    }

    @Override
    public Optional<SystemTable> getSystemTable(ConnectorSession session, SchemaTableName tableName)
    {
        return getRawSystemTable(session, tableName)
                .map(systemTable -> new ClassLoaderSafeSystemTable(systemTable, getClass().getClassLoader()));
    }

    private Optional<SystemTable> getRawSystemTable(ConnectorSession session, SchemaTableName tableName)
    {
        IcebergTableName name = IcebergTableName.from(tableName.getTableName());

        Optional<Table> hiveTable = metastore.getTable(new HiveIdentity(session), tableName.getSchemaName(), name.getTableName());
        if (hiveTable.isEmpty() || !isIcebergTable(hiveTable.get())) {
            return Optional.empty();
        }

        org.apache.iceberg.Table table = getIcebergTable(metastore, hdfsEnvironment, session, hiveTable.get().getSchemaTableName());

        SchemaTableName systemTableName = new SchemaTableName(tableName.getSchemaName(), name.getTableNameWithType());
        switch (name.getTableType()) {
            case HISTORY:
                if (name.getSnapshotId().isPresent()) {
                    throw new PrestoException(NOT_SUPPORTED, "Snapshot ID not supported for history table: " + systemTableName);
                }
                return Optional.of(new HistoryTable(systemTableName, table));
            case SNAPSHOTS:
                if (name.getSnapshotId().isPresent()) {
                    throw new PrestoException(NOT_SUPPORTED, "Snapshot ID not supported for snapshots table: " + systemTableName);
                }
                return Optional.of(new SnapshotsTable(systemTableName, typeManager, table));
            case PARTITIONS:
                return Optional.of(new PartitionTable(systemTableName, typeManager, table, getSnapshotId(table, name.getSnapshotId())));
            case MANIFESTS:
                return Optional.of(new ManifestsTable(systemTableName, table, getSnapshotId(table, name.getSnapshotId())));
            case FILES:
                return Optional.of(new FilesTable(systemTableName, typeManager, table, getSnapshotId(table, name.getSnapshotId())));
        }
        return Optional.empty();
    }

    @Override
    public ConnectorTableProperties getTableProperties(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        return new ConnectorTableProperties();
    }

    @Override
    public ConnectorTableMetadata getTableMetadata(ConnectorSession session, ConnectorTableHandle table)
    {
        return getTableMetadata(session, ((IcebergTableHandle) table).getSchemaTableName());
    }

    @Override
    public List<SchemaTableName> listTables(ConnectorSession session, Optional<String> schemaName)
    {
        List<SchemaTableName> tablesList = schemaName.map(Collections::singletonList)
                .orElseGet(metastore::getAllDatabases)
                .stream()
                .flatMap(schema -> metastore.getTablesWithParameter(schema, TABLE_TYPE_PROP, ICEBERG_TABLE_TYPE_VALUE).stream()
                        .map(table -> new SchemaTableName(schema, table))
                        .collect(toList())
                        .stream())
                .collect(toList());

        schemaName.map(Collections::singletonList)
                .orElseGet(metastore::getAllDatabases).stream()
                .flatMap(schema -> metastore.getAllViews(schema).stream()
                        .map(table -> new SchemaTableName(schema, table)))
                .forEach(schemaTableName -> tablesList.add(schemaTableName));
        return tablesList;
    }

    @Override
    public Map<String, ColumnHandle> getColumnHandles(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        IcebergTableHandle table = (IcebergTableHandle) tableHandle;
        org.apache.iceberg.Table icebergTable = getIcebergTable(metastore, hdfsEnvironment, session, table.getSchemaTableName());
        return getColumns(icebergTable.schema(), typeManager).stream()
                .collect(toImmutableMap(IcebergColumnHandle::getName, identity()));
    }

    @Override
    public ColumnMetadata getColumnMetadata(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnHandle columnHandle)
    {
        IcebergColumnHandle column = (IcebergColumnHandle) columnHandle;
        return ColumnMetadata.builder()
                .setName(column.getName())
                .setType(column.getType())
                .setComment(column.getComment())
                .build();
    }

    @Override
    public Map<SchemaTableName, List<ColumnMetadata>> listTableColumns(ConnectorSession session, SchemaTablePrefix prefix)
    {
        List<SchemaTableName> tables = prefix.getTable()
                .map(ignored -> singletonList(prefix.toSchemaTableName()))
                .orElseGet(() -> listTables(session, prefix.getSchema()));

        ImmutableMap.Builder<SchemaTableName, List<ColumnMetadata>> columns = ImmutableMap.builder();
        for (SchemaTableName table : tables) {
            try {
                columns.put(table, getTableMetadata(session, table).getColumns());
            }
            catch (TableNotFoundException e) {
                // table disappeared during listing operation
            }
            catch (UnknownTableTypeException e) {
                // ignore table of unknown type
            }
        }
        return columns.build();
    }

    @Override
    public void createSchema(ConnectorSession session, String schemaName, Map<String, Object> properties, PrestoPrincipal owner)
    {
        Optional<String> location = getSchemaLocation(properties).map(uri -> {
            try {
                hdfsEnvironment.getFileSystem(new HdfsContext(session, schemaName), new Path(uri));
            }
            catch (IOException | IllegalArgumentException e) {
                throw new PrestoException(INVALID_SCHEMA_PROPERTY, "Invalid location URI: " + uri, e);
            }
            return uri;
        });

        Database database = Database.builder()
                .setDatabaseName(schemaName)
                .setLocation(location)
                .setOwnerType(owner.getType())
                .setOwnerName(owner.getName())
                .build();

        metastore.createDatabase(new HiveIdentity(session), database);
    }

    @Override
    public void dropSchema(ConnectorSession session, String schemaName)
    {
        // basic sanity check to provide a better error message
        if (!listTables(session, Optional.of(schemaName)).isEmpty() ||
                !listViews(session, Optional.of(schemaName)).isEmpty()) {
            throw new PrestoException(SCHEMA_NOT_EMPTY, "Schema not empty: " + schemaName);
        }
        metastore.dropDatabase(new HiveIdentity(session), schemaName);
    }

    @Override
    public void renameSchema(ConnectorSession session, String source, String target)
    {
        metastore.renameDatabase(new HiveIdentity(session), source, target);
    }

    @Override
    public void setSchemaAuthorization(ConnectorSession session, String source, PrestoPrincipal principal)
    {
        metastore.setDatabaseOwner(new HiveIdentity(session), source, HivePrincipal.from(principal));
    }

    @Override
    public void createTable(ConnectorSession session, ConnectorTableMetadata tableMetadata, boolean ignoreExisting)
    {
        Optional<ConnectorNewTableLayout> layout = getNewTableLayout(session, tableMetadata);
        finishCreateTable(session, beginCreateTable(session, tableMetadata, layout), ImmutableList.of(), ImmutableList.of());
    }

    @Override
    public void setTableComment(ConnectorSession session, ConnectorTableHandle tableHandle, Optional<String> comment)
    {
        IcebergTableHandle handle = (IcebergTableHandle) tableHandle;
        metastore.commentTable(new HiveIdentity(session), handle.getSchemaName(), handle.getTableName(), comment);
        org.apache.iceberg.Table icebergTable = getIcebergTable(metastore, hdfsEnvironment, session, handle.getSchemaTableName());
        if (comment.isEmpty()) {
            icebergTable.updateProperties().remove(TABLE_COMMENT).commit();
        }
        else {
            icebergTable.updateProperties().set(TABLE_COMMENT, comment.get()).commit();
        }
    }

    @Override
    public ConnectorOutputTableHandle beginCreateTable(ConnectorSession session, ConnectorTableMetadata tableMetadata, Optional<ConnectorNewTableLayout> layout)
    {
        SchemaTableName schemaTableName = tableMetadata.getTable();
        String schemaName = schemaTableName.getSchemaName();
        String tableName = schemaTableName.getTableName();

        Schema schema = toIcebergSchema(tableMetadata.getColumns());

        PartitionSpec partitionSpec = parsePartitionFields(schema, getPartitioning(tableMetadata.getProperties()));

        Database database = metastore.getDatabase(schemaName)
                .orElseThrow(() -> new SchemaNotFoundException(schemaName));

        HdfsContext hdfsContext = new HdfsContext(session, schemaName, tableName);
        HiveIdentity identity = new HiveIdentity(session);
        String targetPath = getTableLocation(tableMetadata.getProperties());
        if (targetPath == null) {
            String uniqueTableName = useUniqueTableLocation ? generateUniqueTableName(tableName) : tableName;
            targetPath = getTableDefaultLocation(database, hdfsContext, hdfsEnvironment, schemaName, uniqueTableName).toString();
        }

        TableOperations operations = new HiveTableOperations(metastore, hdfsEnvironment, hdfsContext, identity, schemaName, tableName, session.getUser(), targetPath);
        if (operations.current() != null) {
            throw new TableAlreadyExistsException(schemaTableName);
        }

        ImmutableMap.Builder<String, String> propertiesBuilder = ImmutableMap.builderWithExpectedSize(2);
        FileFormat fileFormat = getFileFormat(tableMetadata.getProperties());
        propertiesBuilder.put(DEFAULT_FILE_FORMAT, fileFormat.toString());
        if (tableMetadata.getComment().isPresent()) {
            propertiesBuilder.put(TABLE_COMMENT, tableMetadata.getComment().get());
        }

        TableMetadata metadata = newTableMetadata(schema, partitionSpec, targetPath, propertiesBuilder.build());

        transaction = createTableTransaction(tableName, operations, metadata);

        return new IcebergWritableTableHandle(
                schemaName,
                tableName,
                SchemaParser.toJson(metadata.schema()),
                PartitionSpecParser.toJson(metadata.spec()),
                getColumns(metadata.schema(), typeManager),
                targetPath,
                fileFormat);
    }

    @Override
    public Optional<ConnectorOutputMetadata> finishCreateTable(ConnectorSession session, ConnectorOutputTableHandle tableHandle, Collection<Slice> fragments, Collection<ComputedStatistics> computedStatistics)
    {
        return finishInsert(session, (IcebergWritableTableHandle) tableHandle, fragments, computedStatistics);
    }

    @Override
    public ConnectorInsertTableHandle beginInsert(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        IcebergTableHandle table = (IcebergTableHandle) tableHandle;
        org.apache.iceberg.Table icebergTable = getIcebergTable(metastore, hdfsEnvironment, session, table.getSchemaTableName());

        transaction = icebergTable.newTransaction();

        return new IcebergWritableTableHandle(
                table.getSchemaName(),
                table.getTableName(),
                SchemaParser.toJson(icebergTable.schema()),
                PartitionSpecParser.toJson(icebergTable.spec()),
                getColumns(icebergTable.schema(), typeManager),
                getDataPath(icebergTable.location()),
                getFileFormat(icebergTable));
    }

    @Override
    public Optional<ConnectorOutputMetadata> finishInsert(ConnectorSession session, ConnectorInsertTableHandle insertHandle, Collection<Slice> fragments, Collection<ComputedStatistics> computedStatistics)
    {
        IcebergWritableTableHandle table = (IcebergWritableTableHandle) insertHandle;
        org.apache.iceberg.Table icebergTable = transaction.table();

        List<CommitTaskData> commitTasks = fragments.stream()
                .map(slice -> commitTaskCodec.fromJson(slice.getBytes()))
                .collect(toImmutableList());

        Type[] partitionColumnTypes = icebergTable.spec().fields().stream()
                .map(field -> field.transform().getResultType(
                        icebergTable.schema().findType(field.sourceId())))
                .toArray(Type[]::new);

        AppendFiles appendFiles = transaction.newFastAppend();
        for (CommitTaskData task : commitTasks) {
            HdfsContext context = new HdfsContext(session, table.getSchemaName(), table.getTableName());

            DataFiles.Builder builder = DataFiles.builder(icebergTable.spec())
                    .withInputFile(new HdfsInputFile(new Path(task.getPath()), hdfsEnvironment, context))
                    .withFormat(table.getFileFormat())
                    .withMetrics(task.getMetrics().metrics());

            if (!icebergTable.spec().fields().isEmpty()) {
                String partitionDataJson = task.getPartitionDataJson()
                        .orElseThrow(() -> new VerifyException("No partition data for partitioned table"));
                builder.withPartition(PartitionData.fromJson(partitionDataJson, partitionColumnTypes));
            }

            appendFiles.appendFile(builder.build());
        }

        appendFiles.commit();
        transaction.commitTransaction();

        return Optional.of(new HiveWrittenPartitions(commitTasks.stream()
                .map(CommitTaskData::getPath)
                .collect(toImmutableList())));
    }

    @Override
    public ColumnHandle getUpdateRowIdColumnHandle(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        return new IcebergColumnHandle(0, "$row_id", BIGINT, Optional.empty());
    }

    @Override
    public Optional<Object> getInfo(ConnectorTableHandle tableHandle)
    {
        IcebergTableHandle table = (IcebergTableHandle) tableHandle;
        return Optional.of(new IcebergInputInfo(table.getSnapshotId()));
    }

    @Override
    public void dropTable(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        IcebergTableHandle handle = (IcebergTableHandle) tableHandle;
        metastore.dropTable(new HiveIdentity(session), handle.getSchemaName(), handle.getTableName(), true);
    }

    @Override
    public void renameTable(ConnectorSession session, ConnectorTableHandle tableHandle, SchemaTableName newTable)
    {
        IcebergTableHandle handle = (IcebergTableHandle) tableHandle;
        metastore.renameTable(new HiveIdentity(session), handle.getSchemaName(), handle.getTableName(), newTable.getSchemaName(), newTable.getTableName());
    }

    @Override
    public void addColumn(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnMetadata column)
    {
        IcebergTableHandle handle = (IcebergTableHandle) tableHandle;
        org.apache.iceberg.Table icebergTable = getIcebergTable(metastore, hdfsEnvironment, session, handle.getSchemaTableName());
        icebergTable.updateSchema().addColumn(column.getName(), toIcebergType(column.getType())).commit();
    }

    @Override
    public void dropColumn(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnHandle column)
    {
        IcebergTableHandle icebergTableHandle = (IcebergTableHandle) tableHandle;
        IcebergColumnHandle handle = (IcebergColumnHandle) column;
        org.apache.iceberg.Table icebergTable = getIcebergTable(metastore, hdfsEnvironment, session, icebergTableHandle.getSchemaTableName());
        icebergTable.updateSchema().deleteColumn(handle.getName()).commit();
    }

    @Override
    public void renameColumn(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnHandle source, String target)
    {
        IcebergTableHandle icebergTableHandle = (IcebergTableHandle) tableHandle;
        IcebergColumnHandle columnHandle = (IcebergColumnHandle) source;
        org.apache.iceberg.Table icebergTable = getIcebergTable(metastore, hdfsEnvironment, session, icebergTableHandle.getSchemaTableName());
        icebergTable.updateSchema().renameColumn(columnHandle.getName(), target).commit();
    }

    private ConnectorTableMetadata getTableMetadata(ConnectorSession session, SchemaTableName table)
    {
        if (metastore.getTable(new HiveIdentity(session), table.getSchemaName(), table.getTableName()).isEmpty()) {
            throw new TableNotFoundException(table);
        }

        org.apache.iceberg.Table icebergTable = getIcebergTable(metastore, hdfsEnvironment, session, table);

        List<ColumnMetadata> columns = getColumnMetadatas(icebergTable);

        ImmutableMap.Builder<String, Object> properties = ImmutableMap.builder();
        properties.put(FILE_FORMAT_PROPERTY, getFileFormat(icebergTable));
        if (!icebergTable.spec().fields().isEmpty()) {
            properties.put(PARTITIONING_PROPERTY, toPartitionFields(icebergTable.spec()));
        }

        return new ConnectorTableMetadata(table, columns, properties.build(), getTableComment(icebergTable));
    }

    private List<ColumnMetadata> getColumnMetadatas(org.apache.iceberg.Table table)
    {
        return table.schema().columns().stream()
                .map(column -> {
                    return ColumnMetadata.builder()
                            .setName(column.name())
                            .setType(toPrestoType(column.type(), typeManager))
                            .setNullable(column.isOptional())
                            .setComment(Optional.ofNullable(column.doc()))
                            .build();
                })
                .collect(toImmutableList());
    }

    private static Schema toIcebergSchema(List<ColumnMetadata> columns)
    {
        List<NestedField> icebergColumns = new ArrayList<>();
        for (ColumnMetadata column : columns) {
            if (!column.isHidden()) {
                int index = icebergColumns.size();
                Type type = toIcebergType(column.getType());
                NestedField field = column.isNullable()
                        ? NestedField.optional(index, column.getName(), type, column.getComment())
                        : NestedField.required(index, column.getName(), type, column.getComment());
                icebergColumns.add(field);
            }
        }
        Type icebergSchema = Types.StructType.of(icebergColumns);
        AtomicInteger nextFieldId = new AtomicInteger(1);
        icebergSchema = TypeUtil.assignFreshIds(icebergSchema, nextFieldId::getAndIncrement);
        return new Schema(icebergSchema.asStructType().fields());
    }

    @Override
    public Optional<ConnectorTableHandle> applyDelete(ConnectorSession session, ConnectorTableHandle handle)
    {
        return Optional.of(handle);
    }

    @Override
    public ConnectorTableHandle beginDelete(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        throw new PrestoException(NOT_SUPPORTED, "This connector only supports delete where one or more partitions are deleted entirely");
    }

    @Override
    public OptionalLong executeDelete(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        IcebergTableHandle handle = (IcebergTableHandle) tableHandle;

        org.apache.iceberg.Table icebergTable = getIcebergTable(metastore, hdfsEnvironment, session, handle.getSchemaTableName());

        icebergTable.newDelete()
                .deleteFromRowFilter(toIcebergExpression(handle.getPredicate()))
                .commit();

        // TODO: it should be possible to return number of deleted records
        return OptionalLong.empty();
    }

    @Override
    public boolean usesLegacyTableLayouts()
    {
        return false;
    }

    public HiveMetastore getMetastore()
    {
        return metastore;
    }

    public void rollback()
    {
        // TODO: cleanup open transaction
    }

    @Override
    public Optional<ConstraintApplicationResult<ConnectorTableHandle>> applyFilter(ConnectorSession session, ConnectorTableHandle handle, Constraint constraint)
    {
        IcebergTableHandle table = (IcebergTableHandle) handle;

        // TODO: Remove TupleDomain#simplify once Iceberg supports IN expression
        TupleDomain<IcebergColumnHandle> newDomain = constraint.getSummary()
                .transform(IcebergColumnHandle.class::cast)
                .intersect(table.getPredicate());

        if (newDomain.isNone()) {
            return Optional.empty();
        }

        if (newDomain.equals(table.getPredicate())) {
            return Optional.empty();
        }

        org.apache.iceberg.Table icebergTable = getIcebergTable(metastore, hdfsEnvironment, session, table.getSchemaTableName());

        List<PartitionField> fields = icebergTable.spec().fields().stream()
                .filter(field -> field.transform().isIdentity())
                .collect(toImmutableList());

        // Ensure partition specs in all manifests contain the identity fields from the predicate
        if (!icebergTable.specs().values().stream().allMatch(spec -> spec.fields().containsAll(fields))) {
            return Optional.empty();
        }

        Set<Integer> partitionSourceIds = icebergTable.spec().fields().stream()
                .filter(field -> field.transform().isIdentity())
                .map(PartitionField::sourceId)
                .collect(toImmutableSet());

        BiPredicate<IcebergColumnHandle, Domain> contains = (column, domain) -> partitionSourceIds.contains(column.getId());
        TupleDomain<ColumnHandle> remainingTupleDomain = newDomain.filter(contains.negate()).transform(ColumnHandle.class::cast);
        TupleDomain<IcebergColumnHandle> enforcedTupleDomain = newDomain.filter(contains);

        return Optional.of(new ConstraintApplicationResult<>(
                new IcebergTableHandle(table.getSchemaName(),
                        table.getTableName(),
                        table.getTableType(),
                        table.getSnapshotId(),
                        enforcedTupleDomain),
                remainingTupleDomain));
    }

    @Override
    public TableStatistics getTableStatistics(ConnectorSession session, ConnectorTableHandle tableHandle, Constraint constraint)
    {
        IcebergTableHandle handle = (IcebergTableHandle) tableHandle;
        org.apache.iceberg.Table icebergTable = getIcebergTable(metastore, hdfsEnvironment, session, handle.getSchemaTableName());
        return TableStatisticsMaker.getTableStatistics(typeManager, constraint, handle, icebergTable);
    }

    private Optional<Long> getSnapshotId(org.apache.iceberg.Table table, Optional<Long> snapshotId)
    {
        return snapshotIds.computeIfAbsent(table.toString(), ignored -> snapshotId
                .map(id -> IcebergUtil.resolveSnapshotId(table, id))
                .or(() -> Optional.ofNullable(table.currentSnapshot()).map(Snapshot::snapshotId)));
    }

    @Override
    public void createMaterializedView(ConnectorSession session, SchemaTableName viewName, ConnectorMaterializedViewDefinition definition, boolean replace, boolean ignoreExisting)
    {
        HiveIdentity identity = new HiveIdentity(session);
        Optional<Table> existing = metastore.getTable(identity, viewName.getSchemaName(), viewName.getTableName());

        // It's a create command where the materialized view already exists and 'if not exists' clause is not specified
        if (!replace && existing.isPresent()) {
            if (ignoreExisting) {
                return;
            }
            throw new PrestoException(ALREADY_EXISTS, "Materialized view already exists: " + viewName);
        }

        // Generate a storage table name and create a storage table. The properties in the definition are table properties for the
        // storage table as indicated in the materialized view definition.
        String storageTableName = "st_" + UUID.randomUUID().toString().replace("-", "");
        Map<String, Object> storageTableProperties = new HashMap<>(definition.getProperties());
        storageTableProperties.putIfAbsent(FILE_FORMAT_PROPERTY, DEFAULT_FILE_FORMAT_DEFAULT);

        SchemaTableName storageTable = new SchemaTableName(viewName.getSchemaName(), storageTableName);
        List<ColumnMetadata> columns = definition.getColumns().stream()
                .map(column -> new ColumnMetadata(column.getName(), typeManager.getType(column.getType())))
                .collect(toImmutableList());

        ConnectorTableMetadata tableMetadata = new ConnectorTableMetadata(storageTable, columns, storageTableProperties, Optional.empty());
        Optional<ConnectorNewTableLayout> layout = getNewTableLayout(session, tableMetadata);
        finishCreateTable(session, beginCreateTable(session, tableMetadata, layout), ImmutableList.of(), ImmutableList.of());

        // Create a view indicating the storage table
        Map<String, String> viewProperties = ImmutableMap.<String, String>builder()
                .put(PRESTO_QUERY_ID_NAME, session.getQueryId())
                .put(STORAGE_TABLE, storageTableName)
                .put(PRESTO_VIEW_FLAG, "true")
                .put(TABLE_COMMENT, "Presto Materialized View")
                .build();

        Column dummyColumn = new Column("dummy", HIVE_STRING, Optional.empty());

        String schemaName = viewName.getSchemaName();
        String tableName = viewName.getTableName();
        Table.Builder tableBuilder = Table.builder()
                .setDatabaseName(schemaName)
                .setTableName(tableName)
                .setOwner(session.getUser())
                .setTableType(VIRTUAL_VIEW.name())
                .setDataColumns(ImmutableList.of(dummyColumn))
                .setPartitionColumns(ImmutableList.of())
                .setParameters(viewProperties)
                .withStorage(storage -> storage.setStorageFormat(VIEW_STORAGE_FORMAT))
                .withStorage(storage -> storage.setLocation(""))
                .setViewOriginalText(Optional.of(encodeMaterializedViewData(definition)))
                .setViewExpandedText(Optional.of("/* Presto Materialized View */"));
        Table table = tableBuilder.build();
        PrincipalPrivileges principalPrivileges = buildInitialPrivilegeSet(session.getUser());
        if (existing.isPresent() && replace) {
            // drop the current storage table
            String oldStorageTable = existing.get().getParameters().get(STORAGE_TABLE);
            if (oldStorageTable != null) {
                metastore.dropTable(identity, viewName.getSchemaName(), oldStorageTable, true);
            }
            // Replace the existing view definition
            metastore.replaceTable(identity, viewName.getSchemaName(), viewName.getTableName(), table, principalPrivileges);
            return;
        }
        // create the view definition
        metastore.createTable(identity, table, principalPrivileges);
    }

    @Override
    public void dropMaterializedView(ConnectorSession session, SchemaTableName viewName)
    {
        final HiveIdentity identity = new HiveIdentity(session);
        Table view = metastore.getTable(identity, viewName.getSchemaName(), viewName.getTableName())
                .orElseThrow(() -> new MaterializedViewNotFoundException(viewName));

        String storageTableName = view.getParameters().get(STORAGE_TABLE);
        if (storageTableName != null) {
            try {
                metastore.dropTable(identity, viewName.getSchemaName(), storageTableName, true);
            }
            catch (PrestoException e) {
                log.warn(e, "Failed to drop storage table '%s' for materialized view '%s'", storageTableName, viewName);
            }
        }
        metastore.dropTable(identity, viewName.getSchemaName(), viewName.getTableName(), true);
    }

    @Override
    public ConnectorInsertTableHandle beginRefreshMaterializedView(ConnectorSession session, ConnectorTableHandle tableHandle, List<ConnectorTableHandle> sourceTableHandles)
    {
        IcebergTableHandle table = (IcebergTableHandle) tableHandle;
        org.apache.iceberg.Table icebergTable = getIcebergTable(metastore, hdfsEnvironment, session, table.getSchemaTableName());
        transaction = icebergTable.newTransaction();

        return new IcebergWritableTableHandle(
            table.getSchemaName(),
            table.getTableName(),
            SchemaParser.toJson(icebergTable.schema()),
            PartitionSpecParser.toJson(icebergTable.spec()),
            getColumns(icebergTable.schema(), typeManager),
            getDataPath(icebergTable.location()),
            getFileFormat(icebergTable));
    }

    @Override
    public Optional<ConnectorOutputMetadata> finishRefreshMaterializedView(
            ConnectorSession session,
            ConnectorTableHandle tableHandle,
            ConnectorInsertTableHandle insertHandle,
            Collection<Slice> fragments,
            Collection<ComputedStatistics> computedStatistics,
            List<ConnectorTableHandle> sourceTableHandles)
    {
        // delete before insert .. simulating overwrite
        executeDelete(session, tableHandle);

        IcebergWritableTableHandle table = (IcebergWritableTableHandle) insertHandle;

        org.apache.iceberg.Table icebergTable = transaction.table();
        List<CommitTaskData> commitTasks = fragments.stream()
                .map(slice -> commitTaskCodec.fromJson(slice.getBytes()))
                .collect(toImmutableList());

        Type[] partitionColumnTypes = icebergTable.spec().fields().stream()
            .map(field -> field.transform().getResultType(
                icebergTable.schema().findType(field.sourceId())))
            .toArray(Type[]::new);

        AppendFiles appendFiles = transaction.newFastAppend();
        for (CommitTaskData task : commitTasks) {
            HdfsContext context = new HdfsContext(session, table.getSchemaName(), table.getTableName());
            DataFiles.Builder builder = DataFiles.builder(icebergTable.spec())
                    .withInputFile(new HdfsInputFile(new Path(task.getPath()), hdfsEnvironment, context))
                    .withFormat(table.getFileFormat())
                    .withMetrics(task.getMetrics().metrics());

            if (!icebergTable.spec().fields().isEmpty()) {
                String partitionDataJson = task.getPartitionDataJson()
                        .orElseThrow(() -> new VerifyException("No partition data for partitioned table"));
                builder.withPartition(PartitionData.fromJson(partitionDataJson, partitionColumnTypes));
            }

            appendFiles.appendFile(builder.build());
        }

        String dependencies = sourceTableHandles.stream()
                .map(handle -> (IcebergTableHandle) handle)
                .filter(handle -> handle.getSnapshotId().isPresent())
                .map(handle -> handle.getSchemaTableName() + "=" + handle.getSnapshotId().get())
                .collect(joining(","));

        // Update the 'dependsOnTables' property that tracks tables on which the materialized view depends and the corresponding snapshot ids of the tables
        appendFiles.set(DEPENDS_ON_TABLES, dependencies);
        appendFiles.commit();

        transaction.commitTransaction();
        return Optional.of(new HiveWrittenPartitions(commitTasks.stream()
            .map(CommitTaskData::getPath)
            .collect(toImmutableList())));
    }

    private boolean isMaterializedView(Table table)
    {
        if (table.getTableType().equals(VIRTUAL_VIEW.name()) &&
                "true".equals(table.getParameters().get(PRESTO_VIEW_FLAG)) &&
                table.getParameters().containsKey(STORAGE_TABLE)) {
            return true;
        }
        return false;
    }

    private boolean isMaterializedView(ConnectorSession session, SchemaTableName schemaTableName)
    {
        final HiveIdentity identity = new HiveIdentity(session);
        if (metastore.getTable(identity, schemaTableName.getSchemaName(), schemaTableName.getTableName()).isPresent()) {
            Table table = metastore.getTable(identity, schemaTableName.getSchemaName(), schemaTableName.getTableName()).get();
            return isMaterializedView(table);
        }
        return false;
    }

    @Override
    public Optional<ConnectorMaterializedViewDefinition> getMaterializedView(ConnectorSession session, SchemaTableName viewName)
    {
        Optional<Table> materializedViewOptional = metastore.getTable(new HiveIdentity(session), viewName.getSchemaName(), viewName.getTableName());
        if (!materializedViewOptional.isPresent()) {
            return Optional.empty();
        }
        if (!isMaterializedView(session, viewName)) {
            return Optional.empty();
        }

        Table materializedView = materializedViewOptional.get();

        ConnectorMaterializedViewDefinition definition = decodeMaterializedViewData(materializedView.getViewOriginalText()
                .orElseThrow(() -> new PrestoException(HIVE_INVALID_METADATA, "No view original text: " + viewName)));

        String storageTable = materializedView.getParameters().getOrDefault(STORAGE_TABLE, "");
        return Optional.of(new ConnectorMaterializedViewDefinition(
            definition.getOriginalSql(),
            storageTable,
            definition.getCatalog(),
            Optional.of(viewName.getSchemaName()),
            definition.getColumns(),
            definition.getComment(),
            Optional.of(materializedView.getOwner()),
            new HashMap<>(materializedView.getParameters())));
    }

    public Optional<TableToken> getTableToken(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        IcebergTableHandle table = (IcebergTableHandle) tableHandle;
        org.apache.iceberg.Table icebergTable = getIcebergTable(metastore, hdfsEnvironment, session, table.getSchemaTableName());
        return Optional.ofNullable(icebergTable.currentSnapshot())
            .map(snapshot -> new TableToken(snapshot.snapshotId()));
    }

    public boolean isTableCurrent(ConnectorSession session, ConnectorTableHandle tableHandle, Optional<TableToken> tableToken)
    {
        IcebergTableHandle handle = (IcebergTableHandle) tableHandle;
        Optional<TableToken> currentToken = getTableToken(session, handle);

        if (!tableToken.isPresent() || !currentToken.isPresent()) {
            return false;
        }

        return tableToken.get().getSnapshotId() == currentToken.get().getSnapshotId();
    }

    @Override
    public MaterializedViewFreshness getMaterializedViewFreshness(ConnectorSession session, SchemaTableName materializedViewName)
    {
        Map<String, Optional<TableToken>> refreshStateMap = getMaterializedViewToken(session, materializedViewName);
        if (refreshStateMap.isEmpty()) {
            return new MaterializedViewFreshness(false);
        }

        for (Map.Entry<String, Optional<TableToken>> entry : refreshStateMap.entrySet()) {
            List<String> strings = Splitter.on(".").splitToList(entry.getKey());
            if (strings.size() == 3) {
                strings = strings.subList(1, 3);
            }
            else if (strings.size() != 2) {
                throw new PrestoException(ICEBERG_INVALID_METADATA, String.format("Invalid table name in '%s' property: %s'", DEPENDS_ON_TABLES, strings));
            }
            String schema = strings.get(0);
            String name = strings.get(1);
            SchemaTableName schemaTableName = new SchemaTableName(schema, name);
            if (!isTableCurrent(session, getTableHandle(session, schemaTableName), entry.getValue())) {
                return new MaterializedViewFreshness(false);
            }
        }
        return new MaterializedViewFreshness(true);
    }

    private Map<String, Optional<TableToken>> getMaterializedViewToken(ConnectorSession session, SchemaTableName name)
    {
        Map<String, Optional<TableToken>> viewToken = new HashMap<>();
        Optional<ConnectorMaterializedViewDefinition> materializedViewDefinition = getMaterializedView(session, name);
        if (!materializedViewDefinition.isPresent()) {
            return viewToken;
        }

        String storageTableName = materializedViewDefinition.get().getProperties().getOrDefault(STORAGE_TABLE, "").toString();
        org.apache.iceberg.Table icebergTable = getIcebergTable(metastore, hdfsEnvironment, session, new SchemaTableName(name.getSchemaName(), storageTableName));
        String dependsOnTables = icebergTable.currentSnapshot().summary().getOrDefault(DEPENDS_ON_TABLES, "");
        if (!dependsOnTables.isEmpty()) {
            Map<String, String> tableToSnapshotIdMap = Splitter.on(',').withKeyValueSeparator('=').split(dependsOnTables);
            for (Map.Entry<String, String> entry : tableToSnapshotIdMap.entrySet()) {
                viewToken.put(entry.getKey(), Optional.of(new TableToken(Long.parseLong(entry.getValue()))));
            }
        }
        return viewToken;
    }

    private static class TableToken
    {
        // Current Snapshot ID of the table
        private long snapshotId;

        public TableToken(long snapshotId)
        {
            this.snapshotId = snapshotId;
        }

        public long getSnapshotId()
        {
            return this.snapshotId;
        }
    }

    private String generateUniqueTableName(String tableName)
    {
        return String.format("%s-%s", tableName, UUID.randomUUID().toString().replace("-", ""));
    }
}
