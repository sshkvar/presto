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
package io.trino.plugin.hive.metastore.glue;

import com.amazonaws.services.glue.model.PartitionInput;
import com.amazonaws.services.glue.model.TableInput;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.trino.plugin.hive.metastore.HiveColumnStatistics;
import io.trino.plugin.hive.metastore.Partition;
import io.trino.plugin.hive.metastore.Table;
import io.trino.spi.TrinoException;
import io.trino.spi.statistics.ColumnStatisticType;
import io.trino.spi.type.Type;

import java.util.Map;
import java.util.Set;

import static io.trino.spi.StandardErrorCode.NOT_SUPPORTED;

public class DisabledGlueColumnStatisticsProvider
        implements GlueColumnStatisticsProvider
{
    @Override
    public Set<ColumnStatisticType> getSupportedColumnStatistics(Type type)
    {
        return ImmutableSet.of();
    }

    @Override
    public Map<String, HiveColumnStatistics> getTableColumnStatistics(Table table)
    {
        return ImmutableMap.of();
    }

    @Override
    public Map<String, HiveColumnStatistics> getPartitionColumnStatistics(Partition partition)
    {
        return ImmutableMap.of();
    }

    @Override
    public void updateTableColumnStatistics(TableInput table, Map<String, HiveColumnStatistics> columnStatistics)
    {
        if (!columnStatistics.isEmpty()) {
            throw new TrinoException(NOT_SUPPORTED, "Glue metastore column level statistics are disabled");
        }
    }

    @Override
    public void updatePartitionStatistics(PartitionInput partition, Map<String, HiveColumnStatistics> columnStatistics)
    {
        if (!columnStatistics.isEmpty()) {
            throw new TrinoException(NOT_SUPPORTED, "Glue metastore column level statistics are disabled");
        }
    }
}
