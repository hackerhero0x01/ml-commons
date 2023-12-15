/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.common.transport.update;

import org.opensearch.action.ActionType;

public class MLUpdateModelCacheAction extends ActionType<MLUpdateModelCacheNodesResponse> {
    public static final MLUpdateModelCacheAction INSTANCE = new MLUpdateModelCacheAction();
    public static final String NAME = "cluster:admin/opensearch/ml/models/in_place_update";

    private MLUpdateModelCacheAction() { super(NAME, MLUpdateModelCacheNodesResponse::new);}
}
