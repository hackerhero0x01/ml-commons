/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 *
 */

package org.opensearch.ml.plugin;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.opensearch.SpecialPermission;
import org.opensearch.action.ActionRequest;
import org.opensearch.action.ActionResponse;
import org.opensearch.client.Client;
import org.opensearch.cluster.metadata.IndexNameExpressionResolver;
import org.opensearch.cluster.node.DiscoveryNodeRole;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.io.stream.NamedWriteableRegistry;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.IndexScopedSettings;
import org.opensearch.common.settings.Setting;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.settings.SettingsFilter;
import org.opensearch.common.xcontent.NamedXContentRegistry;
import org.opensearch.env.Environment;
import org.opensearch.env.NodeEnvironment;
import org.opensearch.ml.action.prediction.MLPredictionTaskExecutionAction;
import org.opensearch.ml.action.prediction.MLPredictionTaskExecutionTransportAction;
import org.opensearch.ml.action.prediction.TransportPredictionTaskAction;
import org.opensearch.ml.action.stats.MLStatsNodesAction;
import org.opensearch.ml.action.stats.MLStatsNodesTransportAction;
import org.opensearch.ml.action.training.MLTrainingTaskExecutionAction;
import org.opensearch.ml.action.training.MLTrainingTaskExecutionTransportAction;
import org.opensearch.ml.action.training.TransportTrainingTaskAction;
import org.opensearch.ml.common.transport.prediction.MLPredictionTaskAction;
import org.opensearch.ml.common.transport.training.MLTrainingTaskAction;
import org.opensearch.ml.indices.MLIndicesHandler;
import org.opensearch.ml.indices.MLInputDatasetHandler;

import org.opensearch.ml.rest.RestMLPredictionAction;
import org.opensearch.ml.rest.RestMLTrainingAction;
import org.opensearch.ml.rest.RestStatsMLAction;
import org.opensearch.ml.stats.MLStat;
import org.opensearch.ml.stats.MLStats;
import org.opensearch.ml.stats.StatNames;
import org.opensearch.ml.stats.suppliers.CounterSupplier;
import org.opensearch.ml.task.MLTaskManager;
import org.opensearch.ml.task.MLTaskRunner;
import org.opensearch.monitor.jvm.JvmService;
import org.opensearch.plugins.ActionPlugin;
import org.opensearch.plugins.Plugin;
import org.opensearch.repositories.RepositoriesService;
import org.opensearch.rest.RestController;
import org.opensearch.rest.RestHandler;
import org.opensearch.script.ScriptService;
import org.opensearch.threadpool.ExecutorBuilder;
import org.opensearch.threadpool.FixedExecutorBuilder;
import org.opensearch.threadpool.ThreadPool;
import org.opensearch.watcher.ResourceWatcherService;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

public class MachineLearningPlugin extends Plugin implements ActionPlugin {
    public static final String TASK_THREAD_POOL = "OPENSEARCH_ML_TASK_THREAD_POOL";
    public static final String ML_BASE_URI = "/_plugins/_ml";

    private MLStats mlStats;
    private MLTaskManager mlTaskManager;
    private MLIndicesHandler mlIndicesHandler;
    private MLInputDatasetHandler mlInputDatasetHandler;
    private MLTaskRunner mlTaskRunner;

    private Client client;
    private ClusterService clusterService;
    private ThreadPool threadPool;

    public static final Setting<Boolean> IS_ML_NODE_SETTING = Setting.boolSetting("node.ml", false, Setting.Property.NodeScope);

    public static final DiscoveryNodeRole ML_ROLE = new DiscoveryNodeRole("ml", "l") {
        @Override
        public Setting<Boolean> legacySetting() {
            return IS_ML_NODE_SETTING;
        }
    };

    // This is required by the Java Security permissions.
    // https://www.elastic.co/guide/en/elasticsearch/plugins/current/plugin-authors.html#_java_security_permissions
    static {
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            // unprivileged code such as scripts do not have SpecialPermission
            sm.checkPermission(new SpecialPermission());
        }
    }

    @Override
    public List<ActionHandler<? extends ActionRequest, ? extends ActionResponse>> getActions() {
        return ImmutableList
            .of(
                new ActionHandler<>(MLStatsNodesAction.INSTANCE, MLStatsNodesTransportAction.class),
                new ActionHandler<>(MLPredictionTaskAction.INSTANCE, TransportPredictionTaskAction.class),
                new ActionHandler<>(MLTrainingTaskAction.INSTANCE, TransportTrainingTaskAction.class),
                new ActionHandler<>(MLPredictionTaskExecutionAction.INSTANCE, MLPredictionTaskExecutionTransportAction.class),
                new ActionHandler<>(MLTrainingTaskExecutionAction.INSTANCE, MLTrainingTaskExecutionTransportAction.class)
            );
    }

    @Override
    public Collection<Object> createComponents(
        Client client,
        ClusterService clusterService,
        ThreadPool threadPool,
        ResourceWatcherService resourceWatcherService,
        ScriptService scriptService,
        NamedXContentRegistry xContentRegistry,
        Environment environment,
        NodeEnvironment nodeEnvironment,
        NamedWriteableRegistry namedWriteableRegistry,
        IndexNameExpressionResolver indexNameExpressionResolver,
        Supplier<RepositoriesService> repositoriesServiceSupplier
    ) {
        this.client = client;
        this.threadPool = threadPool;
        this.clusterService = clusterService;

        JvmService jvmService = new JvmService(environment.settings());

        Map<String, MLStat<?>> stats = ImmutableMap
            .<String, MLStat<?>>builder()
            .put(StatNames.ML_EXECUTING_TASK_COUNT.getName(), new MLStat<>(false, new CounterSupplier()))
            .build();
        this.mlStats = new MLStats(stats);

        mlTaskManager = new MLTaskManager();
        mlIndicesHandler = new MLIndicesHandler(clusterService, client);
        mlInputDatasetHandler = new MLInputDatasetHandler(client);

        mlTaskRunner = new MLTaskRunner(
            threadPool,
            clusterService,
            client,
            mlTaskManager,
            mlStats,
            mlIndicesHandler,
            mlInputDatasetHandler
        );

        return ImmutableList.of(jvmService, mlStats, mlTaskManager, mlIndicesHandler, mlInputDatasetHandler, mlTaskRunner);
    }

    @Override
    public List<RestHandler> getRestHandlers(
        Settings settings,
        RestController restController,
        ClusterSettings clusterSettings,
        IndexScopedSettings indexScopedSettings,
        SettingsFilter settingsFilter,
        IndexNameExpressionResolver indexNameExpressionResolver,
        Supplier<DiscoveryNodes> nodesInCluster
    ) {
        RestStatsMLAction restStatsMLAction = new RestStatsMLAction(mlStats);
        RestMLTrainingAction restMLTrainingAction = new RestMLTrainingAction();
        RestMLPredictionAction restMLPredictionAction = new RestMLPredictionAction();
        return ImmutableList.of(restStatsMLAction, restMLTrainingAction, restMLPredictionAction);
    }

    @Override
    public List<ExecutorBuilder<?>> getExecutorBuilders(Settings settings) {
        FixedExecutorBuilder ml = new FixedExecutorBuilder(settings, TASK_THREAD_POOL, 4, 4, "ml.task_thread_pool", false);

        return Collections.singletonList(ml);
    }
}
