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
package io.prestosql.tests.kafka;

import com.google.common.collect.ImmutableList;
import io.prestosql.tempto.ProductTest;
import io.prestosql.tempto.Requirement;
import io.prestosql.tempto.RequirementsProvider;
import io.prestosql.tempto.Requires;
import io.prestosql.tempto.configuration.Configuration;
import io.prestosql.tempto.fulfillment.table.kafka.KafkaMessage;
import io.prestosql.tempto.fulfillment.table.kafka.KafkaTableDefinition;
import io.prestosql.tempto.fulfillment.table.kafka.ListKafkaDataSource;
import org.testng.annotations.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static io.prestosql.tempto.assertions.QueryAssert.Row.row;
import static io.prestosql.tempto.assertions.QueryAssert.assertThat;
import static io.prestosql.tempto.fulfillment.table.TableRequirements.immutableTable;
import static io.prestosql.tempto.fulfillment.table.kafka.KafkaMessageContentsBuilder.contentsBuilder;
import static io.prestosql.tempto.query.QueryExecutor.query;
import static io.prestosql.tests.TestGroups.KAFKA;
import static io.prestosql.tests.TestGroups.PROFILE_SPECIFIC_TESTS;
import static java.lang.String.format;

public class TestKafkaPushdownSmokeTest
        extends ProductTest
{
    private static final String KAFKA_CATALOG = "kafka";
    private static final String SCHEMA_NAME = "product_tests";

    private static final long NUM_MESSAGES = 100;
    private static final long TIMESTAMP_NUM_MESSAGES = 10;

    private static final String PUSHDOWN_PARTITION_TABLE_NAME = "pushdown_partition";
    private static final String PUSHDOWN_PARTITION_TOPIC_NAME = "pushdown_partition";

    private static final String PUSHDOWN_OFFSET_TABLE_NAME = "pushdown_offset";
    private static final String PUSHDOWN_OFFSET_TOPIC_NAME = "pushdown_offset";

    private static final String PUSHDOWN_CREATE_TIME_TABLE_NAME = "pushdown_create_time";
    private static final String PUSHDOWN_CREATE_TIME_TOPIC_NAME = "pushdown_create_time";

    // Kafka connector requires tables to be predefined in Presto configuration
    // the code here will be used to verify that table actually exists and to
    // create topics and insert test data

    private static class PushdownPartitionTable
            implements RequirementsProvider
    {
        @Override
        public Requirement getRequirements(Configuration configuration)
        {
            List<KafkaMessage> records = LongStream.rangeClosed(1, NUM_MESSAGES)
                    .boxed()
                    .map(i -> new KafkaMessage(
                            // only two possible keys to ensure each partition has NUM_MESSAGES/2 messages
                            contentsBuilder().appendUTF8(format("%s", i % 2)).build(),
                            contentsBuilder().appendUTF8(format("%s", i)).build()))
                    .collect(Collectors.toList());

            return immutableTable(new KafkaTableDefinition(
                    SCHEMA_NAME + "." + PUSHDOWN_PARTITION_TABLE_NAME,
                    PUSHDOWN_PARTITION_TOPIC_NAME,
                    new ListKafkaDataSource(records),
                    2,
                    1));
        }
    }

    @Test(groups = {KAFKA, PROFILE_SPECIFIC_TESTS})
    @Requires(PushdownPartitionTable.class)
    public void testPartitionPushdown()
    {
        assertThat(query(format(
                "SELECT COUNT(*) FROM %s.%s.%s WHERE _partition_id = 1",
                KAFKA_CATALOG,
                SCHEMA_NAME,
                PUSHDOWN_PARTITION_TABLE_NAME)))
                .containsExactly(row(NUM_MESSAGES / 2));
    }

    private static class PushdownOffsetTable
            implements RequirementsProvider
    {
        @Override
        public Requirement getRequirements(Configuration configuration)
        {
            List<KafkaMessage> records = LongStream.rangeClosed(1, NUM_MESSAGES)
                    .boxed()
                    .map(i -> new KafkaMessage(
                            // only two possible keys to ensure each partition has NUM_MESSAGES/2 messages
                            contentsBuilder().appendUTF8(format("%s", i % 2)).build(),
                            contentsBuilder().appendUTF8(format("%s", i)).build()))
                    .collect(Collectors.toList());

            return immutableTable(new KafkaTableDefinition(
                    SCHEMA_NAME + "." + PUSHDOWN_OFFSET_TABLE_NAME,
                    PUSHDOWN_OFFSET_TOPIC_NAME,
                    new ListKafkaDataSource(records),
                    2,
                    1));
        }
    }

    @Test(groups = {KAFKA, PROFILE_SPECIFIC_TESTS})
    @Requires(PushdownOffsetTable.class)
    public void testOffsetPushdown()
    {
        assertThat(query(format(
                "SELECT COUNT(*) FROM %s.%s.%s WHERE _partition_offset BETWEEN 6 AND 10",
                KAFKA_CATALOG,
                SCHEMA_NAME,
                PUSHDOWN_OFFSET_TABLE_NAME)))
                .containsExactly(row(10));

        assertThat(query(format(
                "SELECT COUNT(*) FROM %s.%s.%s WHERE _partition_offset > 5 AND _partition_offset < 10",
                KAFKA_CATALOG,
                SCHEMA_NAME,
                PUSHDOWN_OFFSET_TABLE_NAME)))
                .containsExactly(row(8));

        assertThat(query(format(
                "SELECT COUNT(*) FROM %s.%s.%s WHERE _partition_offset >= 5 AND _partition_offset <= 10",
                KAFKA_CATALOG,
                SCHEMA_NAME,
                PUSHDOWN_OFFSET_TABLE_NAME)))
                .containsExactly(row(12));

        assertThat(query(format(
                "SELECT COUNT(*) FROM %s.%s.%s WHERE _partition_offset >= 5 AND _partition_offset < 10",
                KAFKA_CATALOG,
                SCHEMA_NAME,
                PUSHDOWN_OFFSET_TABLE_NAME)))
                .containsExactly(row(10));

        assertThat(query(format(
                "SELECT COUNT(*) FROM %s.%s.%s WHERE _partition_offset > 5 AND _partition_offset <= 10",
                KAFKA_CATALOG,
                SCHEMA_NAME,
                PUSHDOWN_OFFSET_TABLE_NAME)))
                .containsExactly(row(10));

        assertThat(query(format(
                "SELECT COUNT(*) FROM %s.%s.%s WHERE _partition_offset = 5",
                KAFKA_CATALOG,
                SCHEMA_NAME,
                PUSHDOWN_OFFSET_TABLE_NAME)))
                .containsExactly(row(2));
    }

    private static class PushdownCreateTimeTable
            implements RequirementsProvider
    {
        @Override
        public Requirement getRequirements(Configuration configuration)
        {
            return immutableTable(new KafkaTableDefinition(
                    SCHEMA_NAME + "." + PUSHDOWN_CREATE_TIME_TABLE_NAME,
                    PUSHDOWN_CREATE_TIME_TOPIC_NAME,
                    new ListKafkaDataSource(ImmutableList.of()),
                    1,
                    1));
        }
    }

    @Test(groups = {KAFKA, PROFILE_SPECIFIC_TESTS})
    @Requires(PushdownCreateTimeTable.class)
    public void testCreateTimePushdown()
            throws InterruptedException
    {
        // Ensure a spread of at-least TIMESTAMP_NUM_MESSAGES * 100 milliseconds
        for (int i = 1; i <= TIMESTAMP_NUM_MESSAGES; i++) {
            query(format("INSERT INTO %s.%s.%s (bigint_key, bigint_value) VALUES (%s, %s)",
                    KAFKA_CATALOG, SCHEMA_NAME, PUSHDOWN_CREATE_TIME_TABLE_NAME, i, i));
            Thread.sleep(100);
        }

        long startKey = 4;
        long endKey = 6;
        List<List<?>> rows = query(format(
                "SELECT CAST(_timestamp AS VARCHAR) FROM %s.%s.%s WHERE bigint_key IN (" + startKey + ", " + endKey + ") ORDER BY bigint_key",
                KAFKA_CATALOG,
                SCHEMA_NAME,
                PUSHDOWN_CREATE_TIME_TABLE_NAME))
                .rows();
        String startTime = (String) rows.get(0).get(0);
        String endTime = (String) rows.get(1).get(0);

        assertThat(query(format(
                "SELECT COUNT(*) FROM %s.%s.%s WHERE _timestamp >= TIMESTAMP '%s' AND _timestamp < TIMESTAMP '%s'",
                KAFKA_CATALOG, SCHEMA_NAME, PUSHDOWN_CREATE_TIME_TABLE_NAME, startTime, endTime)))
                .containsExactly(row(endKey - startKey));
    }
}
