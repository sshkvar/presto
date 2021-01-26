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
package io.trino.plugin.jdbc;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.connector.ConnectorTableHandle;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.predicate.TupleDomain;

import javax.annotation.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

public final class JdbcTableHandle
        implements ConnectorTableHandle
{
    private final JdbcRelationHandle relationHandle;

    private final TupleDomain<ColumnHandle> constraint;

    // semantically limit is applied after constraint
    private final OptionalLong limit;

    // columns of the relation described by this handle
    private final Optional<List<JdbcColumnHandle>> columns;

    @Deprecated
    public JdbcTableHandle(SchemaTableName schemaTableName, @Nullable String catalogName, @Nullable String schemaName, String tableName)
    {
        this(schemaTableName, new RemoteTableName(Optional.ofNullable(catalogName), Optional.ofNullable(schemaName), tableName));
    }

    public JdbcTableHandle(SchemaTableName schemaTableName, RemoteTableName remoteTableName)
    {
        this(
                new JdbcNamedRelationHandle(schemaTableName, remoteTableName),
                TupleDomain.all(),
                OptionalLong.empty(),
                Optional.empty());
    }

    @JsonCreator
    public JdbcTableHandle(
            @JsonProperty("relationHandle") JdbcRelationHandle relationHandle,
            @JsonProperty("constraint") TupleDomain<ColumnHandle> constraint,
            @JsonProperty("limit") OptionalLong limit,
            @JsonProperty("columns") Optional<List<JdbcColumnHandle>> columns)
    {
        this.relationHandle = requireNonNull(relationHandle, "relationHandle is null");
        this.constraint = requireNonNull(constraint, "constraint is null");

        this.limit = requireNonNull(limit, "limit is null");

        requireNonNull(columns, "columns is null");
        this.columns = columns.map(ImmutableList::copyOf);
    }

    @JsonIgnore
    public SchemaTableName getSchemaTableName()
    {
        return getNamedRelation().getSchemaTableName();
    }

    @JsonIgnore
    public RemoteTableName getRemoteTableName()
    {
        return getNamedRelation().getRemoteTableName();
    }

    @JsonProperty
    public JdbcRelationHandle getRelationHandle()
    {
        return relationHandle;
    }

    @Deprecated
    @Nullable
    public String getCatalogName()
    {
        return getRemoteTableName().getCatalogName().orElse(null);
    }

    @Deprecated
    @Nullable
    public String getSchemaName()
    {
        return getRemoteTableName().getSchemaName().orElse(null);
    }

    @Deprecated
    public String getTableName()
    {
        return getRemoteTableName().getTableName();
    }

    @JsonProperty
    public TupleDomain<ColumnHandle> getConstraint()
    {
        return constraint;
    }

    @JsonProperty
    public OptionalLong getLimit()
    {
        return limit;
    }

    @JsonProperty
    public Optional<List<JdbcColumnHandle>> getColumns()
    {
        return columns;
    }

    private JdbcNamedRelationHandle getNamedRelation()
    {
        checkState(isNamedRelation(), "The table handle does not represent a named relation: %s", this);
        return (JdbcNamedRelationHandle) relationHandle;
    }

    @JsonIgnore
    public boolean isSynthetic()
    {
        return !isNamedRelation() || !constraint.isAll() || limit.isPresent();
    }

    @JsonIgnore
    public boolean isNamedRelation()
    {
        return relationHandle instanceof JdbcNamedRelationHandle;
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj) {
            return true;
        }
        if ((obj == null) || (getClass() != obj.getClass())) {
            return false;
        }
        JdbcTableHandle o = (JdbcTableHandle) obj;
        return Objects.equals(this.relationHandle, o.relationHandle) &&
                Objects.equals(this.constraint, o.constraint) &&
                Objects.equals(this.limit, o.limit) &&
                Objects.equals(this.columns, o.columns);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(relationHandle, constraint, limit, columns);
    }

    @Override
    public String toString()
    {
        StringBuilder builder = new StringBuilder();
        builder.append(relationHandle);
        limit.ifPresent(value -> builder.append(" limit=").append(value));
        columns.ifPresent(value -> builder.append(" columns=").append(value));
        return builder.toString();
    }

    private static <T> List<List<T>> copy(List<List<T>> listOfLists)
    {
        return listOfLists.stream()
                .map(ImmutableList::copyOf)
                .collect(toImmutableList());
    }
}
