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
package io.trino.plugin.redis;

import com.google.common.collect.ImmutableMap;
import io.trino.plugin.redis.util.RedisServer;
import io.trino.testing.AbstractTestQueries;
import io.trino.testing.QueryRunner;
import io.trino.tpch.TpchTable;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

@Test
public class TestRedisDistributed
        extends AbstractTestQueries
{
    private RedisServer redisServer;

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        redisServer = new RedisServer();
        return RedisQueryRunner.createRedisQueryRunner(redisServer, ImmutableMap.of(), "string", TpchTable.getTables());
    }

    @AfterClass(alwaysRun = true)
    public void destroy()
    {
        redisServer.close();
    }
}
