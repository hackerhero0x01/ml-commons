/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.opensearch.ml.model;

import static org.opensearch.common.xcontent.XContentParserUtils.ensureExpectedToken;
import static org.opensearch.ml.common.CommonValue.ML_MODEL_RELOAD_INDEX;
import static org.opensearch.ml.common.CommonValue.ML_MODEL_RELOAD_MAX_RETRY_TIMES;
import static org.opensearch.ml.common.CommonValue.ML_TASK_INDEX;
import static org.opensearch.ml.common.CommonValue.MODEL_LOAD_RETRY_TIMES_FIELD;
import static org.opensearch.ml.common.CommonValue.NODE_ID_FIELD;
import static org.opensearch.ml.plugin.MachineLearningPlugin.GENERAL_THREAD_POOL;
import static org.opensearch.ml.settings.MLCommonsSettings.ML_COMMONS_MODEL_AUTO_RELOAD_ENABLE;
import static org.opensearch.ml.utils.MLNodeUtils.createXContentParserFromRegistry;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import lombok.extern.log4j.Log4j2;

import org.opensearch.action.ActionListener;
import org.opensearch.action.admin.indices.exists.indices.IndicesExistsAction;
import org.opensearch.action.admin.indices.exists.indices.IndicesExistsRequest;
import org.opensearch.action.index.IndexAction;
import org.opensearch.action.index.IndexRequest;
import org.opensearch.action.index.IndexResponse;
import org.opensearch.action.search.SearchAction;
import org.opensearch.action.search.SearchRequest;
import org.opensearch.action.search.SearchResponse;
import org.opensearch.action.support.WriteRequest;
import org.opensearch.client.Client;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.util.CollectionUtils;
import org.opensearch.common.xcontent.NamedXContentRegistry;
import org.opensearch.common.xcontent.XContentParser;
import org.opensearch.index.IndexNotFoundException;
import org.opensearch.index.query.QueryBuilder;
import org.opensearch.index.query.QueryBuilders;
import org.opensearch.ml.cluster.DiscoveryNodeHelper;
import org.opensearch.ml.common.MLTask;
import org.opensearch.ml.common.transport.load.LoadModelResponse;
import org.opensearch.ml.common.transport.load.MLLoadModelAction;
import org.opensearch.ml.common.transport.load.MLLoadModelRequest;
import org.opensearch.ml.utils.MLNodeUtils;
import org.opensearch.rest.RestStatus;
import org.opensearch.search.SearchHit;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.search.sort.FieldSortBuilder;
import org.opensearch.search.sort.SortBuilder;
import org.opensearch.search.sort.SortOrder;
import org.opensearch.threadpool.ThreadPool;

import com.google.common.annotations.VisibleForTesting;

/**
 * Manager class for ML models and nodes. It contains ML model auto reload operations etc.
 */
@Log4j2
public class MLModelAutoReLoader {

    private final Client client;
    private final ClusterService clusterService;
    private final NamedXContentRegistry xContentRegistry;
    private final DiscoveryNodeHelper nodeHelper;
    private final ThreadPool threadPool;
    private volatile Boolean enableAutoReLoadModel;

    /**
     * constructor method， init all the params necessary for model auto reloading
     *
     * @param clusterService   clusterService
     * @param threadPool       threadPool
     * @param client           client
     * @param xContentRegistry xContentRegistry
     * @param nodeHelper       nodeHelper
     * @param settings         settings
     */
    public MLModelAutoReLoader(
        ClusterService clusterService,
        ThreadPool threadPool,
        Client client,
        NamedXContentRegistry xContentRegistry,
        DiscoveryNodeHelper nodeHelper,
        Settings settings
    ) {
        this.clusterService = clusterService;
        this.client = client;
        this.xContentRegistry = xContentRegistry;
        this.nodeHelper = nodeHelper;
        this.threadPool = threadPool;

        enableAutoReLoadModel = ML_COMMONS_MODEL_AUTO_RELOAD_ENABLE.get(settings);
        clusterService
            .getClusterSettings()
            .addSettingsUpdateConsumer(ML_COMMONS_MODEL_AUTO_RELOAD_ENABLE, it -> enableAutoReLoadModel = it);

        autoReLoadModel();
    }

    /**
     * the main method: model auto reloading
     */
    @VisibleForTesting
    void autoReLoadModel() {
        log.info("enableAutoReLoadModel: {} ", enableAutoReLoadModel);

        // if we don't need to reload automatically, just return without doing anything
        if (!enableAutoReLoadModel) {
            return;
        }

        // At opensearch startup, get local node id, if not ml node,we ignored, just return without doing anything
        String localNodeId = clusterService.localNode().getId();
        if (!MLNodeUtils.isMLNode(nodeHelper.getNode(localNodeId))) {
            return;
        }

        // auto reload all models of this local node, if it fails, reTryTimes+1, if it succeeds, reTryTimes is cleared to 0
        threadPool.executor(GENERAL_THREAD_POOL).execute(() -> autoReLoadModelByNodeId(localNodeId));
    }

    /**
     * auto reload all the models under the node id the node must be a ml node
     *
     * @param localNodeId node id
     */
    @VisibleForTesting
    void autoReLoadModelByNodeId(String localNodeId) {
        if (!isExistedIndex(ML_TASK_INDEX)) {
            return;
        }

        SearchResponse response = queryTask(localNodeId);
        if (response == null || response.getHits() == null) {
            return;
        }

        SearchHit[] hits = response.getHits().getHits();
        if (CollectionUtils.isEmpty(hits)) {
            return;
        }

        // According to the node id to get retry times, if more than the maxi retry times, don't need to retry
        // that the number of unsuccessful reload has reached the maximum number of times, do not need to reload
        int reTryTimes = getReTryTimes(localNodeId);
        if (reTryTimes > ML_MODEL_RELOAD_MAX_RETRY_TIMES) {
            log.info("have exceeded max retry times, always failure");
            return;
        }

        try (XContentParser parser = createXContentParserFromRegistry(xContentRegistry, hits[0].getSourceRef())) {
            ensureExpectedToken(XContentParser.Token.START_OBJECT, parser.nextToken(), parser);
            MLTask mlTask = MLTask.parse(parser);

            autoReLoadModelByNodeAndModelId(localNodeId, mlTask.getModelId());

            // if reload the model successfully,the number of unsuccessful reload should be reset to zero.
            reTryTimes = 0;
        } catch (RuntimeException | IOException e) {
            reTryTimes++;
            log.error("Can't auto reload model in node id {} ,has tried {} times\nThe reason is:{}", localNodeId, reTryTimes, e);
        }

        // Store the latest value of the reTryTimes and node id under the index ".plugins-ml-model-reload"
        saveLatestReTryTimes(localNodeId, reTryTimes);
    }

    /**
     * auto reload 1 model under the node id
     *
     * @param localNodeId node id
     * @param modelId     model id
     */
    @VisibleForTesting
    void autoReLoadModelByNodeAndModelId(String localNodeId, String modelId) throws IllegalArgumentException {
        MLLoadModelRequest mlLoadModelRequest = new MLLoadModelRequest(modelId, new String[] { localNodeId }, false, false, true);
        AtomicReference<LoadModelResponse> loadModelResponse = new AtomicReference<>();
        client
            .execute(
                MLLoadModelAction.INSTANCE,
                mlLoadModelRequest,
                ActionListener
                    .wrap(
                        loadModelResponse::set,
                        e -> {
                            throw new RuntimeException(
                                "fail to reload model " + modelId + " under the node " + localNodeId + "\nthe reason is: " + e.getMessage()
                            );
                        }
                    )
            );
    }

    /**
     * query task index, and get the result of "task_type"="LOAD_MODEL" and "state"="COMPLETED" and
     * "worker_node" match nodeId
     *
     * @param localNodeId one of query condition
     * @return SearchResponse
     */
    @VisibleForTesting
    SearchResponse queryTask(String localNodeId) {
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder().from(0).size(1);

        QueryBuilder queryBuilder = QueryBuilders
            .boolQuery()
            .must(QueryBuilders.matchPhraseQuery("task_type", "LOAD_MODEL"))
            .must(QueryBuilders.matchPhraseQuery("state", "COMPLETED"))
            .must(QueryBuilders.matchPhraseQuery("worker_node", localNodeId));
        searchSourceBuilder.query(queryBuilder);

        SortBuilder<FieldSortBuilder> sortBuilderOrder = new FieldSortBuilder("create_time").order(SortOrder.DESC);
        searchSourceBuilder.sort(sortBuilderOrder);

        SearchRequest searchRequest = new SearchRequest().source(searchSourceBuilder).indices(ML_TASK_INDEX);
        try {
            return client.execute(SearchAction.INSTANCE, searchRequest).actionGet(5000);
        } catch (IndexNotFoundException e) {
            log.error("index {} not found, the reason is {}", ML_TASK_INDEX, e);
            throw new IndexNotFoundException("index " + ML_TASK_INDEX + " not found");
        }
    }

    /**
     * get retry times from the index ".plugins-ml-model-reload" by 1 ml node
     *
     * @param localNodeId the filter condition to query
     * @return retry times
     */
    @VisibleForTesting
    int getReTryTimes(String localNodeId) {
        if (!isExistedIndex(ML_MODEL_RELOAD_INDEX)) {
            return 0;
        }

        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        searchSourceBuilder.fetchSource(new String[] { MODEL_LOAD_RETRY_TIMES_FIELD }, null);
        QueryBuilder queryBuilder = QueryBuilders.idsQuery().addIds(localNodeId);
        searchSourceBuilder.query(queryBuilder);
        SearchRequest searchRequest = new SearchRequest().source(searchSourceBuilder).indices(ML_MODEL_RELOAD_INDEX);

        SearchResponse response = client.execute(SearchAction.INSTANCE, searchRequest).actionGet(5000);

        SearchHit[] hits = response.getHits().getHits();
        if (CollectionUtils.isEmpty(hits)) {
            return 0;
        }

        Map<String, Object> sourceAsMap = hits[0].getSourceAsMap();
        return (Integer) sourceAsMap.get(MODEL_LOAD_RETRY_TIMES_FIELD);
    }

    @VisibleForTesting
    void getReTryTimesAsync(String localNodeId, ActionListener<SearchResponse> searchResponseActionListener) {
        isExistedIndexAsync(ML_MODEL_RELOAD_INDEX);

        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        searchSourceBuilder.fetchSource(new String[] { MODEL_LOAD_RETRY_TIMES_FIELD }, null);
        QueryBuilder queryBuilder = QueryBuilders.idsQuery().addIds(localNodeId);
        searchSourceBuilder.query(queryBuilder);
        SearchRequest searchRequest = new SearchRequest().source(searchSourceBuilder).indices(ML_MODEL_RELOAD_INDEX);

        client.execute(SearchAction.INSTANCE, searchRequest, searchResponseActionListener);
    }

    void isExistedIndexAsync(String indexName) {
        IndicesExistsRequest existsRequest = new IndicesExistsRequest(indexName);

        boolean result = client.execute(IndicesExistsAction.INSTANCE, existsRequest).actionGet(5000).isExists();

        if (!result) {
            throw new IndexNotFoundException("index " + indexName + " not found");
        }
    }

    /**
     * judge whether the index ".plugins-ml-model-reload" existed
     *
     * @param indexName index name
     * @return true: existed. false: not existed
     */
    @VisibleForTesting
    boolean isExistedIndex(String indexName) {
        IndicesExistsRequest existsRequest = new IndicesExistsRequest(indexName);

        return client.execute(IndicesExistsAction.INSTANCE, existsRequest).actionGet(5000).isExists();
    }

    /**
     * save retry times
     *
     * @param localNodeId node id
     * @param reTryTimes  actual retry times
     */
    @VisibleForTesting
    void saveLatestReTryTimes(String localNodeId, int reTryTimes) {
        Map<String, Object> content = new HashMap<>(2);
        content.put(NODE_ID_FIELD, localNodeId);
        content.put(MODEL_LOAD_RETRY_TIMES_FIELD, reTryTimes);

        IndexRequest indexRequest = new IndexRequest(ML_MODEL_RELOAD_INDEX);
        indexRequest.id(localNodeId);
        indexRequest.source(content);
        indexRequest.setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE);

        IndexResponse indexResponse = client.execute(IndexAction.INSTANCE, indexRequest).actionGet(5000);

        if (indexResponse.status() == RestStatus.CREATED || indexResponse.status() == RestStatus.OK) {
            log.debug("node id:{} insert retry times successfully", localNodeId);
        }
    }
}
