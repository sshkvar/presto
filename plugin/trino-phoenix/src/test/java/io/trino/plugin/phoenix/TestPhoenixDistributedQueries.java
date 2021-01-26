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
package io.trino.plugin.phoenix;

import com.google.common.collect.ImmutableMap;
import io.trino.testing.AbstractTestDistributedQueries;
import io.trino.testing.QueryRunner;
import io.trino.testing.sql.TestTable;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;

import static io.trino.plugin.phoenix.PhoenixQueryRunner.createPhoenixQueryRunner;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class TestPhoenixDistributedQueries
        extends AbstractTestDistributedQueries
{
    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        return createPhoenixQueryRunner(TestingPhoenixServer.getInstance(), ImmutableMap.of());
    }

    @AfterClass(alwaysRun = true)
    public void destroy()
    {
        TestingPhoenixServer.shutDown();
    }

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
        return true;
    }

    @Override
    protected boolean supportsCommentOnTable()
    {
        return false;
    }

    @Override
    protected boolean supportsCommentOnColumn()
    {
        return false;
    }

    @Override
    protected TestTable createTableWithDefaultColumns()
    {
        throw new SkipException("Phoenix connector does not support column default values");
    }

    @Override
    public void testRenameTable()
    {
        // Phoenix does not support renaming tables
    }

    @Override
    public void testRenameColumn()
    {
        // Phoenix does not support renaming columns
    }

    @Override
    public void testInsert()
    {
        String query = "SELECT orderdate, orderkey, totalprice FROM orders";

        assertUpdate("CREATE TABLE test_insert WITH (ROWKEYS='orderkey') AS " + query + " WITH NO DATA", 0);
        assertQuery("SELECT count(*) FROM test_insert", "SELECT 0");

        assertUpdate("INSERT INTO test_insert " + query, "SELECT count(*) FROM orders");

        assertQuery("SELECT * FROM test_insert", query);

        assertUpdate("INSERT INTO test_insert (orderkey) VALUES (-1)", 1);
        assertUpdate("INSERT INTO test_insert (orderkey) VALUES (-1)", 1); // Phoenix Upsert
        assertUpdate("INSERT INTO test_insert (orderkey) VALUES (-2)", 1);
        assertUpdate("INSERT INTO test_insert (orderkey, orderdate) VALUES (-3, DATE '2001-01-01')", 1);
        assertUpdate("INSERT INTO test_insert (orderkey, orderdate) VALUES (-4, DATE '2001-01-02')", 1);
        assertUpdate("INSERT INTO test_insert (orderdate, orderkey) VALUES (DATE '2001-01-03', -5)", 1);
        assertUpdate("INSERT INTO test_insert (orderkey, totalprice) VALUES (-6, 1234)", 1);

        assertQuery("SELECT * FROM test_insert", query
                + " UNION ALL SELECT null, -1, null"
                + " UNION ALL SELECT null, -2, null"
                + " UNION ALL SELECT DATE '2001-01-01', -3, null"
                + " UNION ALL SELECT DATE '2001-01-02', -4, null"
                + " UNION ALL SELECT DATE '2001-01-03', -5, null"
                + " UNION ALL SELECT null, -6, 1234");

        // UNION query produces columns in the opposite order
        // of how they are declared in the table schema
        assertUpdate(
                "INSERT INTO test_insert (orderkey, orderdate, totalprice) " +
                        "SELECT orderkey, orderdate, totalprice FROM orders " +
                        "UNION ALL " +
                        "SELECT orderkey, orderdate, totalprice FROM orders",
                "SELECT 2 * count(*) FROM orders");

        assertUpdate("DROP TABLE test_insert");
    }

    @Override
    public void testInsertArray()
    {
        assertThatThrownBy(super::testInsertArray)
                // TODO (https://github.com/trinodb/trino/issues/6421) array with double null stored as array with 0
                .hasMessageContaining("Actual rows (up to 100 of 1 extra rows shown, 2 rows in total):\n" +
                        "    [0.0, null]\n" +
                        "Expected rows (up to 100 of 1 missing rows shown, 2 rows in total):\n" +
                        "    [null, null]");
    }

    @Override
    public void testCreateSchema()
    {
        throw new SkipException("test disabled until issue fixed"); // TODO https://github.com/trinodb/trino/issues/2348
    }

    @Override
    protected boolean isColumnNameRejected(Exception exception, String columnName, boolean delimited)
    {
        // TODO This should produce a reasonable exception message like "Invalid column name". Then, we should verify the actual exception message
        return columnName.equals("a\"quote");
    }

    @Override
    public void testDataMappingSmokeTest(DataMappingTestSetup dataMappingTestSetup)
    {
        // TODO enable the test
        throw new SkipException("test fails on Phoenix");
    }
}
