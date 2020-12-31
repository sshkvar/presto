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
package io.trino.plugin.hive.metastore;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.trino.spi.security.ConnectorIdentity;
import io.trino.spi.security.PrestoPrincipal;
import io.trino.spi.security.PrincipalType;
import io.trino.spi.security.SelectedRole;

import java.util.Objects;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;

public class HivePrincipal
{
    public static HivePrincipal from(ConnectorIdentity identity)
    {
        if (identity.getRole().isEmpty()) {
            return ofUser(identity.getUser());
        }
        SelectedRole.Type type = identity.getRole().get().getType();
        if (type == SelectedRole.Type.ALL) {
            return ofUser(identity.getUser());
        }
        checkArgument(type == SelectedRole.Type.ROLE, "Expected role type to be ALL or ROLE, but got: %s", type);
        return ofRole(identity.getRole().get().getRole().get());
    }

    private static HivePrincipal ofUser(String user)
    {
        return new HivePrincipal(PrincipalType.USER, user);
    }

    private static HivePrincipal ofRole(String role)
    {
        return new HivePrincipal(PrincipalType.ROLE, role);
    }

    public static Set<HivePrincipal> from(Set<PrestoPrincipal> prestoPrincipals)
    {
        return prestoPrincipals.stream()
                .map(HivePrincipal::from)
                .collect(toImmutableSet());
    }

    public static HivePrincipal from(PrestoPrincipal prestoPrincipal)
    {
        return new HivePrincipal(prestoPrincipal.getType(), prestoPrincipal.getName());
    }

    private final PrincipalType type;
    private final String name;

    @JsonCreator
    public HivePrincipal(@JsonProperty("type") PrincipalType type, @JsonProperty("name") String name)
    {
        this.type = requireNonNull(type, "type is null");
        requireNonNull(name, "name is null");
        if (type == PrincipalType.USER) {
            // In Hive user names are case sensitive
            this.name = name;
        }
        else if (type == PrincipalType.ROLE) {
            // In Hive role names are case insensitive
            this.name = name.toLowerCase(ENGLISH);
        }
        else {
            throw new IllegalArgumentException("Unsupported type: " + type);
        }
    }

    @JsonProperty
    public PrincipalType getType()
    {
        return type;
    }

    @JsonProperty
    public String getName()
    {
        return name;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        HivePrincipal prestoPrincipal = (HivePrincipal) o;
        return type == prestoPrincipal.type &&
                Objects.equals(name, prestoPrincipal.name);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(type, name);
    }

    @Override
    public String toString()
    {
        return type + " " + name;
    }

    public PrestoPrincipal toPrestoPrincipal()
    {
        return new PrestoPrincipal(type, name);
    }
}
