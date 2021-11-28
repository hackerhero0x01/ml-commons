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

import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;
import org.opensearch.common.io.stream.Writeable;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.experimental.FieldDefaults;
import org.opensearch.common.xcontent.ToXContentObject;
import org.opensearch.common.xcontent.XContentBuilder;
import org.opensearch.common.xcontent.XContentParser;

import static org.opensearch.common.xcontent.XContentParserUtils.ensureExpectedToken;

@FieldDefaults(makeFinal = true, level = AccessLevel.PRIVATE)
@Getter
@Builder
@RequiredArgsConstructor
@ToString
public class ColumnMeta implements Writeable, ToXContentObject {
    private static final String NAME_FIELD = "name";
    private static final String COLUMN_TYPE_FIELD = "column_type";
    String name;
    ColumnType columnType;

    ColumnMeta(StreamInput in) throws IOException {
        this.name = in.readOptionalString();
        this.columnType = in.readEnum(ColumnType.class);
    }

    public static ColumnMeta parse(XContentParser parser) throws IOException {
        String name = null;
        ColumnType columnType = null;

        ensureExpectedToken(XContentParser.Token.START_OBJECT, parser.currentToken(), parser);
        while (parser.nextToken() != XContentParser.Token.END_OBJECT) {
            String fieldName = parser.currentName();
            parser.nextToken();

            switch (fieldName) {
                case NAME_FIELD:
                    name = parser.text();
                    break;
                case COLUMN_TYPE_FIELD:
                    columnType = ColumnType.fromString(parser.text());
                    break;
                default:
                    parser.skipChildren();
                    break;
            }
        }
        return new ColumnMeta(name, columnType);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeOptionalString(name);
        out.writeEnum(columnType);
    }

    public void toXContent(final XContentBuilder builder) throws IOException {
        toXContent(builder, EMPTY_PARAMS);
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.field(NAME_FIELD, name);
        builder.field(COLUMN_TYPE_FIELD, columnType);
        builder.endObject();
        return builder;
    }
}
