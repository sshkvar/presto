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
package io.trino.testing;

import io.trino.GroupByHashPageIndexerFactory;
import io.trino.PagesIndexPageSorter;
import io.trino.connector.CatalogName;
import io.trino.connector.ConnectorAwareNodeManager;
import io.trino.metadata.InMemoryNodeManager;
import io.trino.metadata.Metadata;
import io.trino.operator.PagesIndex;
import io.trino.spi.NodeManager;
import io.trino.spi.PageIndexerFactory;
import io.trino.spi.PageSorter;
import io.trino.spi.VersionEmbedder;
import io.trino.spi.connector.ConnectorContext;
import io.trino.spi.type.TypeManager;
import io.trino.spi.type.TypeOperators;
import io.trino.sql.gen.JoinCompiler;
import io.trino.type.BlockTypeOperators;
import io.trino.type.InternalTypeManager;
import io.trino.version.EmbedVersion;

import static io.trino.metadata.MetadataManager.createTestMetadataManager;

public final class TestingConnectorContext
        implements ConnectorContext
{
    private final NodeManager nodeManager = new ConnectorAwareNodeManager(new InMemoryNodeManager(), "testenv", new CatalogName("test"));
    private final VersionEmbedder versionEmbedder = new EmbedVersion("testversion");
    private final TypeManager typeManager;
    private final PageSorter pageSorter = new PagesIndexPageSorter(new PagesIndex.TestingFactory(false));
    private final PageIndexerFactory pageIndexerFactory;

    public TestingConnectorContext()
    {
        Metadata metadata = createTestMetadataManager();
        TypeOperators typeOperators = new TypeOperators();
        pageIndexerFactory = new GroupByHashPageIndexerFactory(new JoinCompiler(typeOperators), new BlockTypeOperators(typeOperators));
        typeManager = new InternalTypeManager(metadata, typeOperators);
    }

    @Override
    public NodeManager getNodeManager()
    {
        return nodeManager;
    }

    @Override
    public VersionEmbedder getVersionEmbedder()
    {
        return versionEmbedder;
    }

    @Override
    public TypeManager getTypeManager()
    {
        return typeManager;
    }

    @Override
    public PageSorter getPageSorter()
    {
        return pageSorter;
    }

    @Override
    public PageIndexerFactory getPageIndexerFactory()
    {
        return pageIndexerFactory;
    }

    @Override
    public ClassLoader duplicatePluginClassLoader()
    {
        return getClass().getClassLoader();
    }
}
