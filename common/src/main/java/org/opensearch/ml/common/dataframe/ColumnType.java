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

package org.opensearch.ml.common.dataframe;

public enum ColumnType {
    STRING,
    INTEGER,
    DOUBLE,
    BOOLEAN,
    FLOAT,
    NULL;

    public static ColumnType from(Object object) {
        if(object instanceof Integer) {
            return INTEGER;
        }

        if(object instanceof String) {
            return STRING;
        }

        if(object instanceof Double) {
            return DOUBLE;
        }

        if(object instanceof Boolean) {
            return BOOLEAN;
        }

        if(object instanceof Float) {
            return FLOAT;
        }

        throw new IllegalArgumentException("unsupported type:" + object.getClass().getName());
    }
}
