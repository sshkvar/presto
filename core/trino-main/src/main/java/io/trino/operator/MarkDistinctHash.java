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
package io.trino.operator;

import com.google.common.annotations.VisibleForTesting;
import io.trino.Session;
import io.trino.spi.Page;
import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.type.Type;
import io.trino.sql.gen.JoinCompiler;
import io.trino.type.BlockTypeOperators;

import java.util.List;
import java.util.Optional;

import static io.trino.SystemSessionProperties.isDictionaryAggregationEnabled;
import static io.trino.operator.GroupByHash.createGroupByHash;
import static io.trino.spi.type.BooleanType.BOOLEAN;

public class MarkDistinctHash
{
    private final GroupByHash groupByHash;
    private long nextDistinctId;

    public MarkDistinctHash(Session session, List<Type> types, int[] channels, Optional<Integer> hashChannel, JoinCompiler joinCompiler, BlockTypeOperators blockTypeOperators, UpdateMemory updateMemory)
    {
        this(session, types, channels, hashChannel, 10_000, joinCompiler, blockTypeOperators, updateMemory);
    }

    public MarkDistinctHash(Session session, List<Type> types, int[] channels, Optional<Integer> hashChannel, int expectedDistinctValues, JoinCompiler joinCompiler, BlockTypeOperators blockTypeOperators, UpdateMemory updateMemory)
    {
        this.groupByHash = createGroupByHash(types, channels, hashChannel, expectedDistinctValues, isDictionaryAggregationEnabled(session), joinCompiler, blockTypeOperators, updateMemory);
    }

    public long getEstimatedSize()
    {
        return groupByHash.getEstimatedSize();
    }

    public Work<Block> markDistinctRows(Page page)
    {
        return new TransformWork<>(
                groupByHash.getGroupIds(page),
                ids -> {
                    BlockBuilder blockBuilder = BOOLEAN.createBlockBuilder(null, ids.getPositionCount());
                    for (int i = 0; i < ids.getPositionCount(); i++) {
                        if (ids.getGroupId(i) == nextDistinctId) {
                            BOOLEAN.writeBoolean(blockBuilder, true);
                            nextDistinctId++;
                        }
                        else {
                            BOOLEAN.writeBoolean(blockBuilder, false);
                        }
                    }
                    return blockBuilder.build();
                });
    }

    @VisibleForTesting
    public int getCapacity()
    {
        return groupByHash.getCapacity();
    }
}
