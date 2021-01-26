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
package io.trino.plugin.hive.orc;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import io.trino.memory.context.AggregatedMemoryContext;
import io.trino.orc.OrcColumn;
import io.trino.orc.OrcDataSource;
import io.trino.orc.OrcDataSourceId;
import io.trino.orc.OrcReader;
import io.trino.orc.OrcReaderOptions;
import io.trino.orc.OrcRecordReader;
import io.trino.orc.TupleDomainOrcPredicate;
import io.trino.orc.TupleDomainOrcPredicate.TupleDomainOrcPredicateBuilder;
import io.trino.orc.metadata.OrcType.OrcTypeKind;
import io.trino.plugin.hive.AcidInfo;
import io.trino.plugin.hive.FileFormatDataSourceStats;
import io.trino.plugin.hive.HdfsEnvironment;
import io.trino.plugin.hive.HiveColumnHandle;
import io.trino.plugin.hive.HiveColumnProjectionInfo;
import io.trino.plugin.hive.HiveConfig;
import io.trino.plugin.hive.HivePageSourceFactory;
import io.trino.plugin.hive.ReaderColumns;
import io.trino.plugin.hive.ReaderPageSource;
import io.trino.plugin.hive.acid.AcidSchema;
import io.trino.plugin.hive.acid.AcidTransaction;
import io.trino.plugin.hive.orc.OrcPageSource.ColumnAdaptation;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ConnectorPageSource;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.EmptyPageSource;
import io.trino.spi.predicate.Domain;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.type.Type;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.BlockMissingException;
import org.apache.hadoop.hive.ql.io.orc.OrcSerde;
import org.joda.time.DateTimeZone;

import javax.inject.Inject;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.nullToEmpty;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.Maps.uniqueIndex;
import static io.trino.memory.context.AggregatedMemoryContext.newSimpleAggregatedMemoryContext;
import static io.trino.orc.OrcReader.INITIAL_BATCH_SIZE;
import static io.trino.orc.OrcReader.ProjectedLayout.createProjectedLayout;
import static io.trino.orc.OrcReader.ProjectedLayout.fullyProjectedLayout;
import static io.trino.orc.metadata.OrcType.OrcTypeKind.INT;
import static io.trino.orc.metadata.OrcType.OrcTypeKind.LONG;
import static io.trino.orc.metadata.OrcType.OrcTypeKind.STRUCT;
import static io.trino.plugin.hive.HiveColumnHandle.ColumnType.REGULAR;
import static io.trino.plugin.hive.HiveErrorCode.HIVE_BAD_DATA;
import static io.trino.plugin.hive.HiveErrorCode.HIVE_CANNOT_OPEN_SPLIT;
import static io.trino.plugin.hive.HiveErrorCode.HIVE_FILE_MISSING_COLUMN_NAMES;
import static io.trino.plugin.hive.HiveErrorCode.HIVE_MISSING_DATA;
import static io.trino.plugin.hive.HivePageSourceProvider.projectBaseColumns;
import static io.trino.plugin.hive.HiveSessionProperties.getOrcLazyReadSmallRanges;
import static io.trino.plugin.hive.HiveSessionProperties.getOrcMaxBufferSize;
import static io.trino.plugin.hive.HiveSessionProperties.getOrcMaxMergeDistance;
import static io.trino.plugin.hive.HiveSessionProperties.getOrcMaxReadBlockSize;
import static io.trino.plugin.hive.HiveSessionProperties.getOrcStreamBufferSize;
import static io.trino.plugin.hive.HiveSessionProperties.getOrcTinyStripeThreshold;
import static io.trino.plugin.hive.HiveSessionProperties.isOrcBloomFiltersEnabled;
import static io.trino.plugin.hive.HiveSessionProperties.isOrcNestedLazy;
import static io.trino.plugin.hive.HiveSessionProperties.isUseOrcColumnNames;
import static io.trino.plugin.hive.ReaderPageSource.noProjectionAdaptation;
import static io.trino.plugin.hive.orc.OrcPageSource.handleException;
import static io.trino.plugin.hive.util.HiveUtil.isDeserializerClass;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.IntegerType.INTEGER;
import static java.lang.String.format;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toUnmodifiableList;
import static org.apache.hadoop.hive.ql.io.AcidUtils.isFullAcidTable;

public class OrcPageSourceFactory
        implements HivePageSourceFactory
{
    private static final Pattern DEFAULT_HIVE_COLUMN_NAME_PATTERN = Pattern.compile("_col\\d+");
    private final OrcReaderOptions orcReaderOptions;
    private final HdfsEnvironment hdfsEnvironment;
    private final FileFormatDataSourceStats stats;
    private final DateTimeZone legacyTimeZone;

    @Inject
    public OrcPageSourceFactory(OrcReaderConfig config, HdfsEnvironment hdfsEnvironment, FileFormatDataSourceStats stats, HiveConfig hiveConfig)
    {
        this(config.toOrcReaderOptions(), hdfsEnvironment, stats, requireNonNull(hiveConfig, "hiveConfig is null").getOrcLegacyDateTimeZone());
    }

    public OrcPageSourceFactory(
            OrcReaderOptions orcReaderOptions,
            HdfsEnvironment hdfsEnvironment,
            FileFormatDataSourceStats stats,
            DateTimeZone legacyTimeZone)
    {
        this.orcReaderOptions = requireNonNull(orcReaderOptions, "orcReaderOptions is null");
        this.hdfsEnvironment = requireNonNull(hdfsEnvironment, "hdfsEnvironment is null");
        this.stats = requireNonNull(stats, "stats is null");
        this.legacyTimeZone = legacyTimeZone;
    }

    @Override
    public Optional<ReaderPageSource> createPageSource(
            Configuration configuration,
            ConnectorSession session,
            Path path,
            long start,
            long length,
            long estimatedFileSize,
            Properties schema,
            List<HiveColumnHandle> columns,
            TupleDomain<HiveColumnHandle> effectivePredicate,
            Optional<AcidInfo> acidInfo,
            OptionalInt bucketNumber,
            boolean originalFile,
            AcidTransaction transaction)
    {
        if (!isDeserializerClass(schema, OrcSerde.class)) {
            return Optional.empty();
        }

        // per HIVE-13040 and ORC-162, empty files are allowed
        if (estimatedFileSize == 0) {
            ReaderPageSource context = noProjectionAdaptation(new EmptyPageSource());
            return Optional.of(context);
        }

        List<HiveColumnHandle> readerColumnHandles = columns;

        Optional<ReaderColumns> readerColumns = projectBaseColumns(columns);
        if (readerColumns.isPresent()) {
            readerColumnHandles = readerColumns.get().get().stream()
                    .map(HiveColumnHandle.class::cast)
                    .collect(toUnmodifiableList());
        }

        ConnectorPageSource orcPageSource = createOrcPageSource(
                hdfsEnvironment,
                session.getUser(),
                configuration,
                path,
                start,
                length,
                estimatedFileSize,
                readerColumnHandles,
                columns,
                isUseOrcColumnNames(session),
                isFullAcidTable(Maps.fromProperties(schema)),
                effectivePredicate,
                legacyTimeZone,
                orcReaderOptions
                        .withMaxMergeDistance(getOrcMaxMergeDistance(session))
                        .withMaxBufferSize(getOrcMaxBufferSize(session))
                        .withStreamBufferSize(getOrcStreamBufferSize(session))
                        .withTinyStripeThreshold(getOrcTinyStripeThreshold(session))
                        .withMaxReadBlockSize(getOrcMaxReadBlockSize(session))
                        .withLazyReadSmallRanges(getOrcLazyReadSmallRanges(session))
                        .withNestedLazy(isOrcNestedLazy(session))
                        .withBloomFiltersEnabled(isOrcBloomFiltersEnabled(session)),
                acidInfo,
                bucketNumber,
                originalFile,
                transaction,
                stats);

        return Optional.of(new ReaderPageSource(orcPageSource, readerColumns));
    }

    private static ConnectorPageSource createOrcPageSource(
            HdfsEnvironment hdfsEnvironment,
            String sessionUser,
            Configuration configuration,
            Path path,
            long start,
            long length,
            long estimatedFileSize,
            List<HiveColumnHandle> columns,
            List<HiveColumnHandle> projections,
            boolean useOrcColumnNames,
            boolean isFullAcid,
            TupleDomain<HiveColumnHandle> effectivePredicate,
            DateTimeZone legacyFileTimeZone,
            OrcReaderOptions options,
            Optional<AcidInfo> acidInfo,
            OptionalInt bucketNumber,
            boolean originalFile,
            AcidTransaction transaction,
            FileFormatDataSourceStats stats)
    {
        for (HiveColumnHandle column : columns) {
            checkArgument(column.getColumnType() == REGULAR, "column type must be regular: %s", column);
        }
        checkArgument(!effectivePredicate.isNone());

        OrcDataSource orcDataSource;

        boolean originalFilesPresent = acidInfo.isPresent() && !acidInfo.get().getOriginalFiles().isEmpty();
        try {
            FileSystem fileSystem = hdfsEnvironment.getFileSystem(sessionUser, path, configuration);
            FSDataInputStream inputStream = hdfsEnvironment.doAs(sessionUser, () -> fileSystem.open(path));
            orcDataSource = new HdfsOrcDataSource(
                    new OrcDataSourceId(path.toString()),
                    estimatedFileSize,
                    options,
                    inputStream,
                    stats);
        }
        catch (Exception e) {
            if (nullToEmpty(e.getMessage()).trim().equals("Filesystem closed") ||
                    e instanceof FileNotFoundException) {
                throw new TrinoException(HIVE_CANNOT_OPEN_SPLIT, e);
            }
            throw new TrinoException(HIVE_CANNOT_OPEN_SPLIT, splitError(e, path, start, length), e);
        }

        AggregatedMemoryContext systemMemoryUsage = newSimpleAggregatedMemoryContext();
        try {
            Optional<OrcReader> optionalOrcReader = OrcReader.createOrcReader(orcDataSource, options);
            if (optionalOrcReader.isEmpty()) {
                return new EmptyPageSource();
            }
            OrcReader reader = optionalOrcReader.get();

            List<OrcColumn> fileColumns = reader.getRootColumn().getNestedColumns();
            int actualColumnCount = columns.size() + (isFullAcid ? 3 : 0);
            List<OrcColumn> fileReadColumns = new ArrayList<>(actualColumnCount);
            List<Type> fileReadTypes = new ArrayList<>(actualColumnCount);
            List<OrcReader.ProjectedLayout> fileReadLayouts = new ArrayList<>(actualColumnCount);
            if (isFullAcid && !originalFilesPresent) {
                verifyAcidSchema(reader, path);
                Map<String, OrcColumn> acidColumnsByName = uniqueIndex(fileColumns, orcColumn -> orcColumn.getColumnName().toLowerCase(ENGLISH));
                fileColumns = acidColumnsByName.get(AcidSchema.ACID_COLUMN_ROW_STRUCT.toLowerCase(ENGLISH)).getNestedColumns();

                fileReadColumns.add(acidColumnsByName.get(AcidSchema.ACID_COLUMN_ORIGINAL_TRANSACTION.toLowerCase(ENGLISH)));
                fileReadTypes.add(BIGINT);
                fileReadLayouts.add(fullyProjectedLayout());

                fileReadColumns.add(acidColumnsByName.get(AcidSchema.ACID_COLUMN_ROW_ID.toLowerCase(ENGLISH)));
                fileReadTypes.add(BIGINT);
                fileReadLayouts.add(fullyProjectedLayout());

                fileReadColumns.add(acidColumnsByName.get(AcidSchema.ACID_COLUMN_BUCKET.toLowerCase(ENGLISH)));
                fileReadTypes.add(INTEGER);
                fileReadLayouts.add(fullyProjectedLayout());
            }

            Map<String, OrcColumn> fileColumnsByName = ImmutableMap.of();
            if (useOrcColumnNames || isFullAcid) {
                verifyFileHasColumnNames(fileColumns, path);

                // Convert column names read from ORC files to lower case to be consistent with those stored in Hive Metastore
                fileColumnsByName = uniqueIndex(fileColumns, orcColumn -> orcColumn.getColumnName().toLowerCase(ENGLISH));
            }

            Map<String, List<List<String>>> projectionsByColumnName = ImmutableMap.of();
            Map<Integer, List<List<String>>> projectionsByColumnIndex = ImmutableMap.of();
            if (useOrcColumnNames || isFullAcid) {
                projectionsByColumnName = projections.stream()
                        .collect(Collectors.groupingBy(
                                HiveColumnHandle::getBaseColumnName,
                                mapping(
                                        column -> column.getHiveColumnProjectionInfo().map(HiveColumnProjectionInfo::getDereferenceNames).orElse(ImmutableList.<String>of()),
                                        toList())));
            }
            else {
                projectionsByColumnIndex = projections.stream()
                        .collect(Collectors.groupingBy(
                                HiveColumnHandle::getBaseHiveColumnIndex,
                                mapping(
                                        column -> column.getHiveColumnProjectionInfo().map(HiveColumnProjectionInfo::getDereferenceNames).orElse(ImmutableList.<String>of()),
                                        toList())));
            }

            TupleDomainOrcPredicateBuilder predicateBuilder = TupleDomainOrcPredicate.builder()
                    .setBloomFiltersEnabled(options.isBloomFiltersEnabled());
            Map<HiveColumnHandle, Domain> effectivePredicateDomains = effectivePredicate.getDomains()
                    .orElseThrow(() -> new IllegalArgumentException("Effective predicate is none"));
            List<ColumnAdaptation> columnAdaptations = new ArrayList<>(columns.size());
            for (HiveColumnHandle column : columns) {
                OrcColumn orcColumn = null;
                OrcReader.ProjectedLayout projectedLayout = null;
                Map<Optional<HiveColumnProjectionInfo>, Domain> columnDomains = null;

                if (useOrcColumnNames || isFullAcid) {
                    String columnName = column.getName().toLowerCase(ENGLISH);
                    orcColumn = fileColumnsByName.get(columnName);
                    if (orcColumn != null) {
                        projectedLayout = createProjectedLayout(orcColumn, projectionsByColumnName.get(columnName));
                        columnDomains = effectivePredicateDomains.entrySet().stream()
                                .filter(columnDomain -> columnDomain.getKey().getBaseColumnName().toLowerCase(ENGLISH).equals(columnName))
                                .collect(toImmutableMap(columnDomain -> columnDomain.getKey().getHiveColumnProjectionInfo(), Map.Entry::getValue));
                    }
                }
                else if (column.getBaseHiveColumnIndex() < fileColumns.size()) {
                    orcColumn = fileColumns.get(column.getBaseHiveColumnIndex());
                    if (orcColumn != null) {
                        projectedLayout = createProjectedLayout(orcColumn, projectionsByColumnIndex.get(column.getBaseHiveColumnIndex()));
                        columnDomains = effectivePredicateDomains.entrySet().stream()
                                .filter(columnDomain -> columnDomain.getKey().getBaseHiveColumnIndex() == column.getBaseHiveColumnIndex())
                                .collect(toImmutableMap(columnDomain -> columnDomain.getKey().getHiveColumnProjectionInfo(), Map.Entry::getValue));
                    }
                }

                Type readType = column.getType();
                if (orcColumn != null) {
                    int sourceIndex = fileReadColumns.size();
                    columnAdaptations.add(ColumnAdaptation.sourceColumn(sourceIndex));
                    fileReadColumns.add(orcColumn);
                    fileReadTypes.add(readType);
                    fileReadLayouts.add(projectedLayout);

                    // Add predicates on top-level and nested columns
                    for (Map.Entry<Optional<HiveColumnProjectionInfo>, Domain> columnDomain : columnDomains.entrySet()) {
                        OrcColumn nestedColumn = getNestedColumn(orcColumn, columnDomain.getKey());
                        if (nestedColumn != null) {
                            predicateBuilder.addColumn(nestedColumn.getColumnId(), columnDomain.getValue());
                        }
                    }
                }
                else {
                    columnAdaptations.add(ColumnAdaptation.nullColumn(readType));
                }
            }

            OrcRecordReader recordReader = reader.createRecordReader(
                    fileReadColumns,
                    fileReadTypes,
                    fileReadLayouts,
                    predicateBuilder.build(),
                    start,
                    length,
                    legacyFileTimeZone,
                    systemMemoryUsage,
                    INITIAL_BATCH_SIZE,
                    exception -> handleException(orcDataSource.getId(), exception));

            Optional<OrcDeletedRows> deletedRows = acidInfo.map(info ->
                    new OrcDeletedRows(
                            path.getName(),
                            new OrcDeleteDeltaPageSourceFactory(options, sessionUser, configuration, hdfsEnvironment, stats),
                            sessionUser,
                            configuration,
                            hdfsEnvironment,
                            info));

            Optional<Long> originalFileRowId = acidInfo
                    .filter(OrcPageSourceFactory::hasOriginalFilesAndDeleteDeltas)
                    // TODO reduce number of file footer accesses. Currently this is quadratic to the number of original files.
                    .map(info -> OriginalFilesUtils.getPrecedingRowCount(
                            acidInfo.get().getOriginalFiles(),
                            path,
                            hdfsEnvironment,
                            sessionUser,
                            options,
                            configuration,
                            stats));

            if (transaction.isDelete()) {
                if (originalFile) {
                    int bucket = bucketNumber.orElse(0);
                    long startingRowId = originalFileRowId.orElse(0L);
                    columnAdaptations.add(ColumnAdaptation.originalFileRowIdColumn(startingRowId, bucket));
                }
                else {
                    columnAdaptations.add(ColumnAdaptation.rowIdColumn());
                }
            }

            return new OrcPageSource(
                    recordReader,
                    columnAdaptations,
                    orcDataSource,
                    deletedRows,
                    originalFileRowId,
                    systemMemoryUsage,
                    stats);
        }
        catch (Exception e) {
            try {
                orcDataSource.close();
            }
            catch (IOException ignored) {
            }
            if (e instanceof TrinoException) {
                throw (TrinoException) e;
            }
            String message = splitError(e, path, start, length);
            if (e instanceof BlockMissingException) {
                throw new TrinoException(HIVE_MISSING_DATA, message, e);
            }
            throw new TrinoException(HIVE_CANNOT_OPEN_SPLIT, message, e);
        }
    }

    private static boolean hasOriginalFilesAndDeleteDeltas(AcidInfo acidInfo)
    {
        return !acidInfo.getDeleteDeltas().isEmpty() && !acidInfo.getOriginalFiles().isEmpty();
    }

    private static String splitError(Throwable t, Path path, long start, long length)
    {
        return format("Error opening Hive split %s (offset=%s, length=%s): %s", path, start, length, t.getMessage());
    }

    private static void verifyFileHasColumnNames(List<OrcColumn> columns, Path path)
    {
        if (!columns.isEmpty() && columns.stream().map(OrcColumn::getColumnName).allMatch(physicalColumnName -> DEFAULT_HIVE_COLUMN_NAME_PATTERN.matcher(physicalColumnName).matches())) {
            throw new TrinoException(
                    HIVE_FILE_MISSING_COLUMN_NAMES,
                    "ORC file does not contain column names in the footer: " + path);
        }
    }

    static void verifyAcidSchema(OrcReader orcReader, Path path)
    {
        OrcColumn rootColumn = orcReader.getRootColumn();
        List<OrcColumn> nestedColumns = rootColumn.getNestedColumns();
        if (nestedColumns.size() != 6) {
            throw new TrinoException(
                    HIVE_BAD_DATA,
                    format(
                            "ORC ACID file should have 6 columns, found %s %s in %s",
                            nestedColumns.size(),
                            nestedColumns.stream()
                                    .map(column -> format("%s (%s)", column.getColumnName(), column.getColumnType()))
                                    .collect(toImmutableList()),
                            path));
        }
        verifyAcidColumn(orcReader, 0, AcidSchema.ACID_COLUMN_OPERATION, INT, path);
        verifyAcidColumn(orcReader, 1, AcidSchema.ACID_COLUMN_ORIGINAL_TRANSACTION, LONG, path);
        verifyAcidColumn(orcReader, 2, AcidSchema.ACID_COLUMN_BUCKET, INT, path);
        verifyAcidColumn(orcReader, 3, AcidSchema.ACID_COLUMN_ROW_ID, LONG, path);
        verifyAcidColumn(orcReader, 4, AcidSchema.ACID_COLUMN_CURRENT_TRANSACTION, LONG, path);
        verifyAcidColumn(orcReader, 5, AcidSchema.ACID_COLUMN_ROW_STRUCT, STRUCT, path);
    }

    private static void verifyAcidColumn(OrcReader orcReader, int columnIndex, String columnName, OrcTypeKind columnType, Path path)
    {
        OrcColumn column = orcReader.getRootColumn().getNestedColumns().get(columnIndex);
        if (!column.getColumnName().toLowerCase(ENGLISH).equals(columnName.toLowerCase(ENGLISH))) {
            throw new TrinoException(HIVE_BAD_DATA, format("ORC ACID file column %s should be named %s: %s", columnIndex, columnName, path));
        }
        if (column.getColumnType() != columnType) {
            throw new TrinoException(HIVE_BAD_DATA, format("ORC ACID file %s column should be type %s: %s", columnName, columnType, path));
        }
    }

    private static OrcColumn getNestedColumn(OrcColumn baseColumn, Optional<HiveColumnProjectionInfo> projectionInfo)
    {
        if (projectionInfo.isEmpty()) {
            return baseColumn;
        }

        OrcColumn current = baseColumn;
        for (String field : projectionInfo.get().getDereferenceNames()) {
            Optional<OrcColumn> orcColumn = current.getNestedColumns().stream()
                    .filter(column -> column.getColumnName().toLowerCase(ENGLISH).equals(field))
                    .findFirst();

            if (orcColumn.isEmpty()) {
                return null;
            }
            current = orcColumn.get();
        }
        return current;
    }
}
