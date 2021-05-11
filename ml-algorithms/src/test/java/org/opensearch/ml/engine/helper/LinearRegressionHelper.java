package org.opensearch.ml.engine.helper;

import org.opensearch.ml.common.dataframe.ColumnMeta;
import org.opensearch.ml.common.dataframe.ColumnType;
import org.opensearch.ml.common.dataframe.DataFrame;
import org.opensearch.ml.common.dataframe.DataFrameBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LinearRegressionHelper {
    public static DataFrame constructLinearRegressionTrainDataFrame() {
        double[] feet = new double[]{1000.00, 1500.00, 2000.00, 2500.00, 3000.00, 3500.00, 4000.00, 4500.00};
        double[] prices = new double[]{10000.00, 15000.00, 20000.00, 25000.00, 30000.00, 35000.00, 40000.00, 45000.00};
        String[] columnNames = new String[]{"feet", "price"};
        ColumnMeta[] columnMetas = Arrays.stream(columnNames).map(e -> new ColumnMeta(e, ColumnType.DOUBLE)).toArray(ColumnMeta[]::new);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i=0; i<prices.length; ++i) {
            Map<String, Object> row = new HashMap<>();
            row.put("feet", feet[i]);
            row.put("price", prices[i]);
            rows.add(row);
        }

        return DataFrameBuilder.load(columnMetas, rows);
    }
}
