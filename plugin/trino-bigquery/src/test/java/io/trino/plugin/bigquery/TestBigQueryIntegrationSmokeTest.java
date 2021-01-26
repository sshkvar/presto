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
package io.trino.plugin.bigquery;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.Job;
import com.google.cloud.bigquery.JobId;
import com.google.cloud.bigquery.JobInfo;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.common.collect.ImmutableMap;
import io.trino.testing.AbstractTestIntegrationSmokeTest;
import io.trino.testing.MaterializedResult;
import io.trino.testing.QueryRunner;
import org.testng.annotations.Test;

import static io.trino.plugin.bigquery.BigQueryQueryRunner.createBigQueryClient;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.testing.MaterializedResult.resultBuilder;
import static io.trino.testing.assertions.Assert.assertEquals;
import static io.trino.testing.sql.TestTable.randomTableSuffix;
import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;

@Test
public class TestBigQueryIntegrationSmokeTest
        extends AbstractTestIntegrationSmokeTest
{
    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        return BigQueryQueryRunner.createQueryRunner(ImmutableMap.of());
    }

    @Override
    public void testDescribeTable()
    {
        MaterializedResult expectedColumns = resultBuilder(getQueryRunner().getDefaultSession(), VARCHAR, VARCHAR, VARCHAR, VARCHAR)
                .row("orderkey", "bigint", "", "")
                .row("custkey", "bigint", "", "")
                .row("orderstatus", "varchar", "", "")
                .row("totalprice", "double", "", "")
                .row("orderdate", "date", "", "")
                .row("orderpriority", "varchar", "", "")
                .row("clerk", "varchar", "", "")
                .row("shippriority", "bigint", "", "")
                .row("comment", "varchar", "", "")
                .build();
        MaterializedResult actualColumns = computeActual("DESCRIBE orders");
        assertEquals(actualColumns, expectedColumns);
    }

    @Test(enabled = false)
    public void testSelectFromHourlyPartitionedTable()
    {
        BigQuery client = createBigQueryClient();

        executeBigQuerySql(client, "DROP TABLE IF EXISTS test.hourly_partitioned");
        executeBigQuerySql(client, "CREATE TABLE test.hourly_partitioned (value INT64, ts TIMESTAMP) PARTITION BY TIMESTAMP_TRUNC(ts, HOUR)");
        executeBigQuerySql(client, "INSERT INTO test.hourly_partitioned (value, ts) VALUES (1000, '2018-01-01 10:00:00')");

        MaterializedResult actualValues = computeActual("SELECT COUNT(1) FROM test.hourly_partitioned");

        assertEquals((long) actualValues.getOnlyValue(), 1L);
    }

    @Test(enabled = false)
    public void testSelectFromYearlyPartitionedTable()
    {
        BigQuery client = createBigQueryClient();

        executeBigQuerySql(client, "DROP TABLE IF EXISTS test.yearly_partitioned");
        executeBigQuerySql(client, "CREATE TABLE test.yearly_partitioned (value INT64, ts TIMESTAMP) PARTITION BY TIMESTAMP_TRUNC(ts, YEAR)");
        executeBigQuerySql(client, "INSERT INTO test.yearly_partitioned (value, ts) VALUES (1000, '2018-01-01 10:00:00')");

        MaterializedResult actualValues = computeActual("SELECT COUNT(1) FROM test.yearly_partitioned");

        assertEquals((long) actualValues.getOnlyValue(), 1L);
    }

    @Test(description = "regression test for https://github.com/trinodb/trino/issues/5618")
    public void testPredicatePushdownPrunnedColumns()
    {
        BigQuery client = createBigQueryClient();

        String tableName = "test.predicate_pushdown_prunned_columns";

        executeBigQuerySql(client, "DROP TABLE IF EXISTS " + tableName);
        executeBigQuerySql(client, "CREATE TABLE " + tableName + " (a INT64, b INT64, c INT64)");
        executeBigQuerySql(client, "INSERT INTO " + tableName + " VALUES (1,2,3)");

        assertQuery(
                "SELECT 1 FROM " + tableName + " WHERE " +
                        "    ((NULL IS NULL) OR a = 100) AND " +
                        "    b = 2",
                "VALUES (1)");
    }

    @Test(description = "regression test for https://github.com/trinodb/trino/issues/5635")
    public void testCountAggregationView()
    {
        BigQuery client = createBigQueryClient();

        String tableName = "test.count_aggregation_table";
        String viewName = "test.count_aggregation_view";

        executeBigQuerySql(client, "DROP TABLE IF EXISTS " + tableName);
        executeBigQuerySql(client, "DROP VIEW IF EXISTS " + viewName);
        executeBigQuerySql(client, "CREATE TABLE " + tableName + " (a INT64, b INT64, c INT64)");
        executeBigQuerySql(client, "INSERT INTO " + tableName + " VALUES (1, 2, 3), (4, 5, 6)");
        executeBigQuerySql(client, "CREATE VIEW " + viewName + " AS SELECT * FROM " + tableName);

        assertQuery(
                "SELECT count(*) FROM " + viewName,
                "VALUES (2)");

        assertQuery(
                "SELECT count(*) FROM " + viewName + " WHERE a = 1",
                "VALUES (1)");

        assertQuery(
                "SELECT count(a) FROM " + viewName + " WHERE b = 2",
                "VALUES (1)");
    }

    /**
     * regression test for https://github.com/trinodb/trino/issues/6696
     */
    @Test
    public void testRepeatCountAggregationView()
    {
        BigQuery client = createBigQueryClient();

        String viewName = "test.repeat_count_aggregation_view_" + randomTableSuffix();

        executeBigQuerySql(client, "DROP VIEW IF EXISTS " + viewName);
        executeBigQuerySql(client, "CREATE VIEW " + viewName + " AS SELECT 1 AS col1");

        assertQuery("SELECT count(*) FROM " + viewName, "VALUES (1)");
        assertQuery("SELECT count(*) FROM " + viewName, "VALUES (1)");

        executeBigQuerySql(client, "DROP VIEW " + viewName);
    }

    private static void executeBigQuerySql(BigQuery bigquery, String query)
    {
        QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(query)
                .setUseLegacySql(false)
                .build();

        JobId jobId = JobId.of();
        Job queryJob = bigquery.create(JobInfo.newBuilder(queryConfig).setJobId(jobId).build());

        try {
            queryJob = queryJob.waitFor();

            if (queryJob == null) {
                throw new RuntimeException(format("Job with uuid %s does not longer exists", jobId.getJob()));
            }

            if (queryJob.getStatus().getError() != null) {
                throw new RuntimeException(format("Query '%s' failed: %s", query, queryJob.getStatus().getError()));
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    @Override
    public void testShowCreateTable()
    {
        assertThat((String) computeActual("SHOW CREATE TABLE orders").getOnlyValue())
                .isEqualTo("CREATE TABLE bigquery.tpch.orders (\n" +
                        "   orderkey bigint NOT NULL,\n" +
                        "   custkey bigint NOT NULL,\n" +
                        "   orderstatus varchar NOT NULL,\n" +
                        "   totalprice double NOT NULL,\n" +
                        "   orderdate date NOT NULL,\n" +
                        "   orderpriority varchar NOT NULL,\n" +
                        "   clerk varchar NOT NULL,\n" +
                        "   shippriority bigint NOT NULL,\n" +
                        "   comment varchar NOT NULL\n" +
                        ")");
    }
}
