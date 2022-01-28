/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.engine;

import lombok.EqualsAndHashCode;

import java.io.Serializable;

@EqualsAndHashCode
public class DummyModel implements Serializable {
    private String name = "dummy model";
    private String version = "0.1";
}
