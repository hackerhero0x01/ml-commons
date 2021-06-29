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

import org.opensearch.common.io.stream.Writeable;
import org.opensearch.common.xcontent.XContentBuilder;

import java.io.IOException;

public interface ColumnValue extends Writeable {
    ColumnType columnType();

    Object getValue();

    default int intValue() {
        throw new RuntimeException("the value isn't Integer type");
    }

    default String stringValue() {
        throw new RuntimeException("the value isn't String type");
    }

    default double doubleValue() {
        throw new RuntimeException("the value isn't Double type");
    }

    default boolean booleanValue() {
        throw new RuntimeException("the value isn't Boolean type");
    }

    default void toXContent(XContentBuilder builder) throws IOException {
        builder.startObject();
        builder.field("column_type", columnType());
        builder.field("value", getValue());
        builder.endObject();
    }
}
