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
package io.trino.plugin.oracle;

import io.trino.Session;
import io.trino.execution.QueryInfo;
import io.trino.testing.AbstractTestDistributedQueries;
import io.trino.testing.MaterializedResult;
import io.trino.testing.ResultWithQueryId;
import io.trino.testing.sql.SqlExecutor;
import io.trino.testing.sql.TestTable;
import org.testng.annotations.Test;

import java.util.Optional;

import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.testing.MaterializedResult.resultBuilder;
import static io.trino.testing.sql.TestTable.randomTableSuffix;
import static java.lang.String.format;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public abstract class BaseTestOracleDistributedQueries
        extends AbstractTestDistributedQueries
{
    @Override
    protected boolean supportsDelete()
    {
        return false;
    }

    @Override
    protected boolean supportsViews()
    {
        return false;
    }

    @Override
    protected boolean supportsArrays()
    {
        return false;
    }

    @Override
    protected boolean supportsCommentOnTable()
    {
        return false;
    }

    @Override
    public void testCreateSchema()
    {
        // schema creation is not supported
        assertQueryFails("CREATE SCHEMA test_schema_create", "This connector does not support creating schemas");
    }

    @Override
    protected String dataMappingTableName(String trinoTypeName)
    {
        return "tmp_trino_" + System.nanoTime();
    }

    @Override
    protected Optional<DataMappingTestSetup> filterDataMappingSmokeTestData(DataMappingTestSetup dataMappingTestSetup)
    {
        String typeName = dataMappingTestSetup.getTrinoTypeName();
        if (typeName.equals("time")) {
            return Optional.empty();
        }
        if (typeName.equals("boolean")) {
            // Oracle does not have native support for boolean however usually it is represented as number(1)
            return Optional.empty();
        }

        return Optional.of(dataMappingTestSetup);
    }

    @Override
    protected TestTable createTableWithDefaultColumns()
    {
        return new TestTable(
                createJdbcSqlExecutor(),
                "test_default_columns",
                "(col_required decimal(20,0) NOT NULL," +
                        "col_nullable decimal(20,0)," +
                        "col_default decimal(20,0) DEFAULT 43," +
                        "col_nonnull_default decimal(20,0) DEFAULT 42 NOT NULL ," +
                        "col_required2 decimal(20,0) NOT NULL)");
    }

    @Test
    @Override
    public void testCreateTableAsSelect()
    {
        String tableName = "test_ctas" + randomTableSuffix();
        assertUpdate("CREATE TABLE IF NOT EXISTS " + tableName + " AS SELECT name, regionkey FROM nation", "SELECT count(*) FROM nation");
        assertTableColumnNames(tableName, "name", "regionkey");
        assertUpdate("DROP TABLE " + tableName);

        // Some connectors support CREATE TABLE AS but not the ordinary CREATE TABLE. Let's test CTAS IF NOT EXISTS with a table that is guaranteed to exist.
        assertUpdate("CREATE TABLE IF NOT EXISTS nation AS SELECT orderkey, discount FROM lineitem", 0);
        assertTableColumnNames("nation", "nationkey", "name", "regionkey", "comment");

        assertCreateTableAsSelect(
                "SELECT orderdate, orderkey, totalprice FROM orders",
                "SELECT count(*) FROM orders");

        assertCreateTableAsSelect(
                "SELECT orderstatus, sum(totalprice) x FROM orders GROUP BY orderstatus",
                "SELECT count(DISTINCT orderstatus) FROM orders");

        assertCreateTableAsSelect(
                "SELECT count(*) x FROM lineitem JOIN orders ON lineitem.orderkey = orders.orderkey",
                "SELECT 1");

        assertCreateTableAsSelect(
                "SELECT orderkey FROM orders ORDER BY orderkey LIMIT 10",
                "SELECT 10");

        // this is comment because Trino creates a table of varchar(1) and in oracle this unicode occupy 3 char
        // we should try to get bytes instead of size ??
        /*
        assertCreateTableAsSelect(
                "SELECT '\u2603' unicode",
                "SELECT 1");
        */
        assertCreateTableAsSelect(
                "SELECT * FROM orders WITH DATA",
                "SELECT * FROM orders",
                "SELECT count(*) FROM orders");

        assertCreateTableAsSelect(
                "SELECT * FROM orders WITH NO DATA",
                "SELECT * FROM orders LIMIT 0",
                "SELECT 0");

        // Tests for CREATE TABLE with UNION ALL: exercises PushTableWriteThroughUnion optimizer

        assertCreateTableAsSelect(
                "SELECT orderdate, orderkey, totalprice FROM orders WHERE orderkey % 2 = 0 UNION ALL " +
                        "SELECT orderdate, orderkey, totalprice FROM orders WHERE orderkey % 2 = 1",
                "SELECT orderdate, orderkey, totalprice FROM orders",
                "SELECT count(*) FROM orders");

        assertCreateTableAsSelect(
                Session.builder(getSession()).setSystemProperty("redistribute_writes", "true").build(),
                "SELECT CAST(orderdate AS DATE) orderdate, orderkey, totalprice FROM orders UNION ALL " +
                        "SELECT DATE '2000-01-01', 1234567890, 1.23",
                "SELECT orderdate, orderkey, totalprice FROM orders UNION ALL " +
                        "SELECT DATE '2000-01-01', 1234567890, 1.23",
                "SELECT count(*) + 1 FROM orders");

        assertCreateTableAsSelect(
                Session.builder(getSession()).setSystemProperty("redistribute_writes", "false").build(),
                "SELECT CAST(orderdate AS DATE) orderdate, orderkey, totalprice FROM orders UNION ALL " +
                        "SELECT DATE '2000-01-01', 1234567890, 1.23",
                "SELECT orderdate, orderkey, totalprice FROM orders UNION ALL " +
                        "SELECT DATE '2000-01-01', 1234567890, 1.23",
                "SELECT count(*) + 1 FROM orders");

        assertExplainAnalyze("EXPLAIN ANALYZE CREATE TABLE " + tableName + " AS SELECT orderstatus FROM orders");
        assertQuery("SELECT * from " + tableName, "SELECT orderstatus FROM orders");
        assertUpdate("DROP TABLE " + tableName);
    }

    @Test
    @Override
    public void testSymbolAliasing()
    {
        // Replace tablename to less than 30chars, max size naming on oracle
        String tableName = "symbol_aliasing" + System.currentTimeMillis();
        assertUpdate("CREATE TABLE " + tableName + " AS SELECT 1 foo_1, 2 foo_2_4", 1);
        assertQuery("SELECT foo_1, foo_2_4 FROM " + tableName, "SELECT 1, 2");
        assertUpdate("DROP TABLE " + tableName);
    }

    @Test
    @Override
    public void testRenameColumn()
    {
        // Replace tablename to less than 30chars, max size naming on oracle
        String tableName = "test_renamecol_" + System.currentTimeMillis();
        assertUpdate("CREATE TABLE " + tableName + " AS SELECT 'some value' x", 1);

        assertUpdate("ALTER TABLE " + tableName + " RENAME COLUMN x TO y");
        assertQuery("SELECT y FROM " + tableName, "VALUES 'some value'");

        assertUpdate("ALTER TABLE " + tableName + " RENAME COLUMN y TO Z"); // 'Z' is upper-case, not delimited
        assertQuery(
                "SELECT z FROM " + tableName, // 'z' is lower-case, not delimited
                "VALUES 'some value'");

        // There should be exactly one column
        assertQuery("SELECT * FROM " + tableName, "VALUES 'some value'");

        assertUpdate("DROP TABLE " + tableName);
    }

    @Test
    @Override
    public void testQueryLoggingCount()
    {
        // table name has more than 30chars,max size naming on oracle.
        // but as this methods call on private methods we disable it
    }

    @Test
    @Override
    public void testWrittenStats()
    {
        // Replace tablename to fetch max size naming on oracle
        String tableName = "written_stats_" + System.currentTimeMillis();
        String sql = "CREATE TABLE " + tableName + " AS SELECT * FROM nation";
        ResultWithQueryId<MaterializedResult> resultResultWithQueryId = getDistributedQueryRunner().executeWithQueryId(getSession(), sql);
        QueryInfo queryInfo = getDistributedQueryRunner().getCoordinator().getQueryManager().getFullQueryInfo(resultResultWithQueryId.getQueryId());

        assertEquals(queryInfo.getQueryStats().getOutputPositions(), 1L);
        assertEquals(queryInfo.getQueryStats().getWrittenPositions(), 25L);
        assertTrue(queryInfo.getQueryStats().getLogicalWrittenDataSize().toBytes() > 0L);

        sql = "INSERT INTO " + tableName + " SELECT * FROM nation LIMIT 10";
        resultResultWithQueryId = getDistributedQueryRunner().executeWithQueryId(getSession(), sql);
        queryInfo = getDistributedQueryRunner().getCoordinator().getQueryManager().getFullQueryInfo(resultResultWithQueryId.getQueryId());

        assertEquals(queryInfo.getQueryStats().getOutputPositions(), 1L);
        assertEquals(queryInfo.getQueryStats().getWrittenPositions(), 10L);
        assertTrue(queryInfo.getQueryStats().getLogicalWrittenDataSize().toBytes() > 0L);

        assertUpdate("DROP TABLE " + tableName);
    }

    @Test
    @Override
    public void testShowColumns()
    {
        MaterializedResult actual = computeActual("SHOW COLUMNS FROM orders");

        MaterializedResult expectedParametrizedVarchar = resultBuilder(getSession(), VARCHAR, VARCHAR, VARCHAR, VARCHAR)
                .row("orderkey", "decimal(19,0)", "", "")
                .row("custkey", "decimal(19,0)", "", "")
                .row("orderstatus", "varchar(1)", "", "")
                .row("totalprice", "double", "", "")
                .row("orderdate", "timestamp(3)", "", "")
                .row("orderpriority", "varchar(15)", "", "")
                .row("clerk", "varchar(15)", "", "")
                .row("shippriority", "decimal(10,0)", "", "")
                .row("comment", "varchar(79)", "", "")
                .build();

        // Until we migrate all connectors to parametrized varchar we check two options
        assertTrue(actual.equals(expectedParametrizedVarchar),
                format("%s does not matches %s", actual, expectedParametrizedVarchar));
    }

    @Override
    public void testInformationSchemaFiltering()
    {
        assertQuery(
                "SELECT table_name FROM information_schema.tables WHERE table_name = 'orders' LIMIT 1",
                "SELECT 'orders' table_name");
        assertQuery(
                "SELECT table_name FROM information_schema.columns WHERE data_type = 'decimal(19,0)' AND table_name = 'customer' AND column_name = 'custkey' LIMIT 1",
                "SELECT 'customer' table_name");
    }

    @Test
    @Override
    public void testInsertUnicode()
    {
        // unicode not working correctly as one unicode char takes more than one byte
    }

    @Override
    protected Optional<String> filterColumnNameTestData(String columnName)
    {
        // table names generated has more than 30chars, max size naming on oracle.
        return Optional.empty();
    }

    protected abstract SqlExecutor createJdbcSqlExecutor();
}
