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

import java.io.IOException;

import org.junit.Test;
import org.opensearch.common.Strings;
import org.opensearch.common.io.stream.BytesStreamOutput;
import org.opensearch.common.xcontent.XContentBuilder;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.common.xcontent.XContentType;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class DoubleValueTest {

    @Test
    public void columnType() {
        assertEquals(ColumnType.DOUBLE, new DoubleValue(2D).columnType());
    }

    @Test
    public void getValue() {
        assertEquals(2D, (Double) new DoubleValue(2.0D).getValue(), 0.00001D);
    }

    @Test
    public void doubleValue() {
        assertEquals(3.0D, new DoubleValue(3.0D).doubleValue(), 0.00001D);
    }

    @Test
    public void writeTo() throws IOException {
        DoubleValue doubleValue = new DoubleValue(5.0D);
        BytesStreamOutput bytesStreamOutput = new BytesStreamOutput();
        doubleValue.writeTo(bytesStreamOutput);
        assertEquals(9, bytesStreamOutput.size());
        doubleValue = (DoubleValue) new ColumnValueReader().read(bytesStreamOutput.bytes().streamInput());
        assertEquals(5.0D, doubleValue.doubleValue(), 0.00001D);
    }

    @Test
    public void testToXContent() throws IOException {
        DoubleValue doubleValue = new DoubleValue(5.0D);
        XContentBuilder builder = XContentFactory.contentBuilder(XContentType.JSON);
        builder.startObject();
        doubleValue.toXContent(builder);
        builder.endObject();

        assertNotNull(builder);
        String jsonStr = Strings.toString(builder);
        assertEquals("{\"ColumnType\":\"DOUBLE\",\"Value\":5.0}", jsonStr);
    }
}