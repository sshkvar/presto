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
package io.trino.plugin.hive.s3;

import com.google.inject.Binder;
import com.google.inject.Scopes;
import io.airlift.configuration.AbstractConfigurationAwareModule;
import io.trino.plugin.hive.ConfigurationInitializer;
import io.trino.plugin.hive.DynamicConfigurationProvider;
import io.trino.plugin.hive.HiveConfig;
import io.trino.plugin.hive.rubix.RubixEnabledConfig;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.common.JavaUtils;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.inject.multibindings.Multibinder.newSetBinder;
import static io.airlift.configuration.ConfigBinder.configBinder;
import static org.weakref.jmx.guice.ExportBinder.newExporter;

public class HiveS3Module
        extends AbstractConfigurationAwareModule
{
    public static final String EMR_FS_CLASS_NAME = "com.amazon.ws.emr.hadoop.fs.EmrFileSystem";

    @Override
    protected void setup(Binder binder)
    {
        S3FileSystemType type = buildConfigObject(HiveS3TypeConfig.class).getS3FileSystemType();
        switch (type) {
            case TRINO:
                bindSecurityMapping(binder);

                newSetBinder(binder, ConfigurationInitializer.class).addBinding().to(TrinoS3ConfigurationInitializer.class).in(Scopes.SINGLETON);
                configBinder(binder).bindConfig(HiveS3Config.class);

                binder.bind(TrinoS3FileSystemStats.class).toInstance(TrinoS3FileSystem.getFileSystemStats());
                newExporter(binder).export(TrinoS3FileSystemStats.class)
                        .as(generator -> generator.generatedNameOf(TrinoS3FileSystem.class));
                break;
            case EMRFS:
                validateEmrFsClass();
                newSetBinder(binder, ConfigurationInitializer.class).addBinding().to(EmrFsS3ConfigurationInitializer.class).in(Scopes.SINGLETON);
                break;
            case HADOOP_DEFAULT:
                // configuration is done using Hadoop configuration files
                break;
            default:
                throw new RuntimeException("Unknown file system type: " + type);
        }
    }

    private void bindSecurityMapping(Binder binder)
    {
        if (buildConfigObject(S3SecurityMappingConfig.class).getConfigFile().isPresent()) {
            checkArgument(!buildConfigObject(HiveConfig.class).isS3SelectPushdownEnabled(), "S3 security mapping is not compatible with S3 Select pushdown");
            checkArgument(!buildConfigObject(RubixEnabledConfig.class).isCacheEnabled(), "S3 security mapping is not compatible with Hive caching");

            newSetBinder(binder, DynamicConfigurationProvider.class).addBinding()
                    .to(S3SecurityMappingConfigurationProvider.class).in(Scopes.SINGLETON);
        }
    }

    private static void validateEmrFsClass()
    {
        // verify that the class exists
        try {
            Class.forName(EMR_FS_CLASS_NAME, true, JavaUtils.getClassLoader());
        }
        catch (ClassNotFoundException e) {
            throw new RuntimeException("EMR File System class not found: " + EMR_FS_CLASS_NAME, e);
        }
    }

    public static class EmrFsS3ConfigurationInitializer
            implements ConfigurationInitializer
    {
        @Override
        public void initializeConfiguration(Configuration config)
        {
            // re-map filesystem schemes to use the Amazon EMR file system
            config.set("fs.s3.impl", EMR_FS_CLASS_NAME);
            config.set("fs.s3a.impl", EMR_FS_CLASS_NAME);
            config.set("fs.s3n.impl", EMR_FS_CLASS_NAME);
        }
    }
}
