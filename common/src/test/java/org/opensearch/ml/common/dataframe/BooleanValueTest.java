/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.common.dataframe;

import org.junit.Test;
import org.opensearch.common.Strings;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.common.xcontent.XContentType;
import org.opensearch.core.xcontent.XContentBuilder;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class BooleanValueTest {
    @Test
    public void booleanValue() {
        BooleanValue value = new BooleanValue(true);
        assertEquals(ColumnType.BOOLEAN, value.columnType());
        assertTrue(value.booleanValue());
        assertEquals(true, value.getValue());
    }

    @Test
    public void testToXContent() throws IOException {
        BooleanValue value = new BooleanValue(true);
        XContentBuilder builder = XContentFactory.contentBuilder(XContentType.JSON);
        value.toXContent(builder);

        assertNotNull(builder);
        String jsonStr = Strings.toString(builder);
        assertEquals("{\"column_type\":\"BOOLEAN\",\"value\":true}", jsonStr);
    }
}