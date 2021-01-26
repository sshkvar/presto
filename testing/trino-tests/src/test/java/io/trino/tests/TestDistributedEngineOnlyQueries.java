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
package io.trino.tests;

import io.trino.Session;
import io.trino.plugin.memory.MemoryQueryRunner;
import io.trino.testing.QueryRunner;
import org.intellij.lang.annotations.Language;
import org.testng.annotations.Test;

import java.time.ZonedDateTime;

import static io.trino.SystemSessionProperties.ENABLE_DYNAMIC_FILTERING;
import static io.trino.sql.analyzer.FeaturesConfig.JoinDistributionType.BROADCAST;
import static io.trino.testing.sql.TestTable.randomTableSuffix;
import static org.assertj.core.api.Assertions.assertThat;

public class TestDistributedEngineOnlyQueries
        extends AbstractTestEngineOnlyQueries
{
    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        return MemoryQueryRunner.createQueryRunner();
    }

    /**
     * Ensure the tests are run with {@link io.trino.testing.DistributedQueryRunner}. E.g. {@link io.trino.testing.LocalQueryRunner} takes some
     * shortcuts, not exercising certain aspects.
     */
    @Test
    public void ensureDistributedQueryRunner()
    {
        assertThat(getQueryRunner().getNodeCount()).as("query runner node count")
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    public void testTimestampWithTimeZoneLiteralsWithDifferentZone()
    {
        assertThat(getQueryRunner().execute("SELECT TIMESTAMP '2017-01-02 09:12:34.123 Europe/Warsaw'").getOnlyValue()).isEqualTo(ZonedDateTime.parse("2017-01-02T09:12:34.123+01:00[Europe/Warsaw]"));
        assertThat(getQueryRunner().execute("SELECT TIMESTAMP '2017-01-02 09:12:34.123 Europe/Paris'").getOnlyValue()).isEqualTo(ZonedDateTime.parse("2017-01-02T09:12:34.123+01:00[Europe/Paris]"));

        assertThat(getQueryRunner().execute("SELECT TIMESTAMP '2017-01-02 09:12:34.123456 Europe/Warsaw'").getOnlyValue()).isEqualTo(ZonedDateTime.parse("2017-01-02T09:12:34.123456+01:00[Europe/Warsaw]"));
        assertThat(getQueryRunner().execute("SELECT TIMESTAMP '2017-01-02 09:12:34.123456 Europe/Paris'").getOnlyValue()).isEqualTo(ZonedDateTime.parse("2017-01-02T09:12:34.123456+01:00[Europe/Paris]"));

        assertThat(getQueryRunner().execute("SELECT TIMESTAMP '2017-01-02 09:12:34.123456789 Europe/Warsaw'").getOnlyValue()).isEqualTo(ZonedDateTime.parse("2017-01-02T09:12:34.123456789+01:00[Europe/Warsaw]"));
        assertThat(getQueryRunner().execute("SELECT TIMESTAMP '2017-01-02 09:12:34.123456789 Europe/Paris'").getOnlyValue()).isEqualTo(ZonedDateTime.parse("2017-01-02T09:12:34.123456789+01:00[Europe/Paris]"));

        assertThat(getQueryRunner().execute("SELECT TIMESTAMP '2017-01-02 09:12:34.123456789012 Europe/Warsaw'").getOnlyValue()).isEqualTo("2017-01-02 09:12:34.123456789012 Europe/Warsaw");
        assertThat(getQueryRunner().execute("SELECT TIMESTAMP '2017-01-02 09:12:34.123456789012 Europe/Paris'").getOnlyValue()).isEqualTo("2017-01-02 09:12:34.123456789012 Europe/Paris");
    }

    @Test
    public void testUse()
    {
        assertQueryFails("USE invalid.xyz", "Catalog does not exist: invalid");
        assertQueryFails("USE tpch.invalid", "Schema does not exist: tpch.invalid");
    }

    @Test
    public void testRoles()
    {
        Session invalid = Session.builder(getSession()).setCatalog("invalid").build();
        assertQueryFails(invalid, "CREATE ROLE test", "Catalog does not exist: invalid");
        assertQueryFails(invalid, "DROP ROLE test", "Catalog does not exist: invalid");
        assertQueryFails(invalid, "GRANT bar TO USER foo", "Catalog does not exist: invalid");
        assertQueryFails(invalid, "REVOKE bar FROM USER foo", "Catalog does not exist: invalid");
        assertQueryFails(invalid, "SET ROLE test", "Catalog does not exist: invalid");
    }

    @Test
    public void testDuplicatedRowCreateTable()
    {
        assertQueryFails("CREATE TABLE test (a integer, a integer)",
                "line 1:31: Column name 'a' specified more than once");
        assertQueryFails("CREATE TABLE test (a integer, orderkey integer, LIKE orders INCLUDING PROPERTIES)",
                "line 1:49: Column name 'orderkey' specified more than once");

        assertQueryFails("CREATE TABLE test (a integer, A integer)",
                "line 1:31: Column name 'A' specified more than once");
        assertQueryFails("CREATE TABLE test (a integer, OrderKey integer, LIKE orders INCLUDING PROPERTIES)",
                "line 1:49: Column name 'orderkey' specified more than once");
    }

    @Test
    public void testTooLongQuery()
    {
        //  Generate a super-long query: SELECT x,x,x,x,x,... FROM (VALUES 1,2,3,4,5) t(x)
        @Language("SQL") String longQuery = "SELECT x" + ",x".repeat(500_000) + " FROM (VALUES 1,2,3,4,5) t(x)";
        assertQueryFails(longQuery, "Query text length \\(1000037\\) exceeds the maximum length \\(1000000\\)");
    }

    @Test
    public void testTooManyStages()
    {
        @Language("SQL") String query = "WITH\n" +
                "  t1 AS (SELECT nationkey AS x FROM nation where name='UNITED STATES'),\n" +
                "  t2 AS (SELECT a.x+b.x+c.x+d.x AS x FROM t1 a, t1 b, t1 c, t1 d),\n" +
                "  t3 AS (SELECT a.x+b.x+c.x+d.x AS x FROM t2 a, t2 b, t2 c, t2 d),\n" +
                "  t4 AS (SELECT a.x+b.x+c.x+d.x AS x FROM t3 a, t3 b, t3 c, t3 d),\n" +
                "  t5 AS (SELECT a.x+b.x+c.x+d.x AS x FROM t4 a, t4 b, t4 c, t4 d)\n" +
                "SELECT x FROM t5\n";
        assertQueryFails(query, "Number of stages in the query \\([0-9]+\\) exceeds the allowed maximum \\([0-9]+\\).*");
    }

    @Test
    public void testRowSubscriptWithReservedKeyword()
    {
        // Subscript over field named after reserved keyword. This test needs to run in distributed
        // mode, as it uncovers a problem during deserialization plan expressions
        assertQuery(
                "SELECT cast(row(1) AS row(\"cross\" bigint))[1]",
                "VALUES 1");
    }

    @Test
    public void testRowTypeWithReservedKeyword()
    {
        // This test is here because it only reproduces the issue (https://github.com/trinodb/trino/issues/1962)
        // when running in distributed mode
        assertQuery(
                "SELECT cast(row(1) AS row(\"cross\" bigint)).\"cross\"",
                "VALUES 1");
    }

    // explain analyze can only run on coordinator
    @Test
    public void testExplainAnalyze()
    {
        assertExplainAnalyze(
                noJoinReordering(BROADCAST),
                "EXPLAIN ANALYZE SELECT * FROM (SELECT nationkey, regionkey FROM nation GROUP BY nationkey, regionkey) a, nation b WHERE a.regionkey = b.regionkey");
        assertExplainAnalyze(
                "EXPLAIN ANALYZE SELECT * FROM nation a, nation b WHERE a.nationkey = b.nationkey",
                "Left \\(probe\\) Input avg\\.: .* rows, Input std\\.dev\\.: .*",
                "Right \\(build\\) Input avg\\.: .* rows, Input std\\.dev\\.: .*",
                "Collisions avg\\.: .* \\(.* est\\.\\), Collisions std\\.dev\\.: .*");
        assertExplainAnalyze(
                Session.builder(getSession())
                        .setSystemProperty(ENABLE_DYNAMIC_FILTERING, "false")
                        .build(),
                "EXPLAIN ANALYZE SELECT * FROM nation a, nation b WHERE a.nationkey = b.nationkey",
                "Left \\(probe\\) Input avg\\.: .* rows, Input std\\.dev\\.: .*",
                "Right \\(build\\) Input avg\\.: .* rows, Input std\\.dev\\.: .*",
                "Collisions avg\\.: .* \\(.* est\\.\\), Collisions std\\.dev\\.: .*");
        assertExplainAnalyze(
                "EXPLAIN ANALYZE SELECT nationkey FROM nation GROUP BY nationkey",
                "Collisions avg\\.: .* \\(.* est\\.\\), Collisions std\\.dev\\.: .*");
    }

    @Test
    public void testInsertWithCoercion()
    {
        String tableName = "test_insert_with_coercion_" + randomTableSuffix();

        assertUpdate("CREATE TABLE " + tableName + " (" +
                "tinyint_column tinyint, " +
                "integer_column integer, " +
                "decimal_column decimal(5, 3), " +
                "real_column real, " +
                "char_column char(3), " +
                "bounded_varchar_column varchar(3), " +
                "unbounded_varchar_column varchar, " +
                "date_column date)");

        assertUpdate("INSERT INTO " + tableName + " (tinyint_column, integer_column, decimal_column, real_column) VALUES (1e0, 2e0, 3e0, 4e0)", 1);
        assertUpdate("INSERT INTO " + tableName + " (char_column, bounded_varchar_column, unbounded_varchar_column) VALUES (CAST('aa     ' AS varchar), CAST('aa     ' AS varchar), CAST('aa     ' AS varchar))", 1);
        assertUpdate("INSERT INTO " + tableName + " (char_column, bounded_varchar_column, unbounded_varchar_column) VALUES (NULL, NULL, NULL)", 1);
        assertUpdate("INSERT INTO " + tableName + " (char_column, bounded_varchar_column, unbounded_varchar_column) VALUES (CAST(NULL AS varchar), CAST(NULL AS varchar), CAST(NULL AS varchar))", 1);
        assertUpdate("INSERT INTO " + tableName + " (date_column) VALUES (TIMESTAMP '2019-11-18 22:13:40')", 1);

        assertQuery(
                "SELECT * FROM " + tableName,
                "VALUES " +
                        "(1, 2, 3, 4, NULL, NULL, NULL, NULL), " +
                        "(NULL, NULL, NULL, NULL, 'aa ', 'aa ', 'aa     ', NULL), " +
                        "(NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL), " +
                        "(NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL), " +
                        "(NULL, NULL, NULL, NULL, NULL, NULL, NULL, DATE '2019-11-18')");

        assertQueryFails("INSERT INTO " + tableName + " (integer_column) VALUES (3e9)", "Out of range for integer: 3.0E9");
        assertQueryFails("INSERT INTO " + tableName + " (char_column) VALUES ('abcd')", "\\QCannot truncate non-space characters when casting from varchar(4) to char(3) on INSERT");
        assertQueryFails("INSERT INTO " + tableName + " (bounded_varchar_column) VALUES ('abcd')", "\\QCannot truncate non-space characters when casting from varchar(4) to varchar(3) on INSERT");

        assertUpdate("DROP TABLE " + tableName);
    }
}
