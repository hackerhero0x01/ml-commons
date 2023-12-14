/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.engine.indices;

import static org.opensearch.ml.common.CommonValue.ML_AGENT_INDEX;
import static org.opensearch.ml.common.CommonValue.ML_AGENT_INDEX_MAPPING;
import static org.opensearch.ml.common.CommonValue.ML_AGENT_INDEX_SCHEMA_VERSION;
import static org.opensearch.ml.common.CommonValue.ML_MEMORY_MESSAGE_INDEX;
import static org.opensearch.ml.common.CommonValue.ML_MEMORY_MESSAGE_INDEX_MAPPING;
import static org.opensearch.ml.common.CommonValue.ML_MEMORY_MESSAGE_INDEX_SCHEMA_VERSION;
import static org.opensearch.ml.common.CommonValue.ML_MEMORY_META_INDEX;
import static org.opensearch.ml.common.CommonValue.ML_MEMORY_META_INDEX_MAPPING;
import static org.opensearch.ml.common.CommonValue.ML_MEMORY_META_INDEX_SCHEMA_VERSION;

public enum MLIndex {
    AGENT(ML_AGENT_INDEX, false, ML_AGENT_INDEX_MAPPING, ML_AGENT_INDEX_SCHEMA_VERSION),
    MEMORY_META(ML_MEMORY_META_INDEX, false, ML_MEMORY_META_INDEX_MAPPING, ML_MEMORY_META_INDEX_SCHEMA_VERSION),
    MEMORY_MESSAGE(ML_MEMORY_MESSAGE_INDEX, false, ML_MEMORY_MESSAGE_INDEX_MAPPING, ML_MEMORY_MESSAGE_INDEX_SCHEMA_VERSION);

    private final String indexName;
    // whether we use an alias for the index
    private final boolean alias;
    private final String mapping;
    private final Integer version;

    MLIndex(String name, boolean alias, String mapping, Integer version) {
        this.indexName = name;
        this.alias = alias;
        this.mapping = mapping;
        this.version = version;
    }

    public String getIndexName() {
        return indexName;
    }

    public boolean isAlias() {
        return alias;
    }

    public String getMapping() {
        return mapping;
    }

    public Integer getVersion() {
        return version;
    }
}
