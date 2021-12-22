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

package org.opensearch.ml.indices;

import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import lombok.extern.log4j.Log4j2;

import org.opensearch.client.Client;
import org.opensearch.cluster.service.ClusterService;
import org.opensearch.common.xcontent.XContentType;

@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
@RequiredArgsConstructor
@Log4j2
public class MLIndicesHandler {
    public static final String ML_MODEL_INDEX = ".plugins-ml-model";
    private static final String ML_MODEL_INDEX_MAPPING = "{\n"
        + "    \"properties\": {\n"
        + "      \"task_id\": { \"type\": \"keyword\" },\n"
        + "      \"algorithm\": {\"type\": \"keyword\"},\n"
        + "      \"model_name\" : { \"type\": \"keyword\"},\n"
        + "      \"model_version\" : { \"type\": \"keyword\"},\n"
        + "      \"model_content\" : { \"type\": \"binary\"}\n"
        + "    }\n"
        + "}";

    ClusterService clusterService;
    Client client;

    public void initModelIndexIfAbsent() {
        initMLIndexIfAbsent(ML_MODEL_INDEX, ML_MODEL_INDEX_MAPPING);
    }

    public boolean doesModelIndexExist() {
        return clusterService.state().metadata().hasIndex(ML_MODEL_INDEX);
    }

    private void initMLIndexIfAbsent(String indexName, String mapping) {
        if (!clusterService.state().metadata().hasIndex(indexName)) {
            client.admin().indices().prepareCreate(indexName).addMapping("_doc", mapping, XContentType.JSON).get();
            log.info("create index:{}", indexName);
        } else {
            log.info("index:{} is already created", indexName);
        }
    }
}
