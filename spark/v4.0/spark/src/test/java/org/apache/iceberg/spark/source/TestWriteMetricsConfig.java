/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iceberg.spark.source;

import static org.apache.iceberg.spark.SparkSchemaUtil.convert;
import static org.apache.iceberg.types.Types.NestedField.optional;
import static org.apache.iceberg.types.Types.NestedField.required;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.exceptions.ValidationException;
import org.apache.iceberg.hadoop.HadoopTables;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.relocated.com.google.common.collect.Maps;
import org.apache.iceberg.spark.SparkWriteOptions;
import org.apache.iceberg.spark.data.RandomData;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.ByteBuffers;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SaveMode;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.catalyst.InternalRow;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class TestWriteMetricsConfig {

  private static final Configuration CONF = new Configuration();
  private static final Schema SIMPLE_SCHEMA =
      new Schema(
          optional(1, "id", Types.IntegerType.get()), optional(2, "data", Types.StringType.get()));
  private static final Schema COMPLEX_SCHEMA =
      new Schema(
          required(1, "longCol", Types.IntegerType.get()),
          optional(2, "strCol", Types.StringType.get()),
          required(
              3,
              "record",
              Types.StructType.of(
                  required(4, "id", Types.IntegerType.get()),
                  required(5, "data", Types.StringType.get()))));

  @TempDir private Path temp;

  private static SparkSession spark = null;
  private static JavaSparkContext sc = null;

  @BeforeAll
  public static void startSpark() {
    TestWriteMetricsConfig.spark = SparkSession.builder().master("local[2]").getOrCreate();
    TestWriteMetricsConfig.sc = JavaSparkContext.fromSparkContext(spark.sparkContext());
  }

  @AfterAll
  public static void stopSpark() {
    SparkSession currentSpark = TestWriteMetricsConfig.spark;
    TestWriteMetricsConfig.spark = null;
    TestWriteMetricsConfig.sc = null;
    currentSpark.stop();
  }

  @Test
  public void testFullMetricsCollectionForParquet() {
    String tableLocation = temp.resolve("iceberg-table").toFile().toString();

    HadoopTables tables = new HadoopTables(CONF);
    PartitionSpec spec = PartitionSpec.unpartitioned();
    Map<String, String> properties = Maps.newHashMap();
    properties.put(TableProperties.DEFAULT_WRITE_METRICS_MODE, "full");
    Table table = tables.create(SIMPLE_SCHEMA, spec, properties, tableLocation);

    List<SimpleRecord> expectedRecords =
        Lists.newArrayList(
            new SimpleRecord(1, "a"), new SimpleRecord(2, "b"), new SimpleRecord(3, "c"));
    Dataset<Row> df = spark.createDataFrame(expectedRecords, SimpleRecord.class);
    df.select("id", "data")
        .coalesce(1)
        .write()
        .format("iceberg")
        .option(SparkWriteOptions.WRITE_FORMAT, "parquet")
        .mode(SaveMode.Append)
        .save(tableLocation);

    for (FileScanTask task : table.newScan().includeColumnStats().planFiles()) {
      DataFile file = task.file();
      assertThat(file.nullValueCounts()).hasSize(2);
      assertThat(file.valueCounts()).hasSize(2);
      assertThat(file.lowerBounds()).hasSize(2);
      assertThat(file.upperBounds()).hasSize(2);
    }
  }

  @Test
  public void testCountMetricsCollectionForParquet() {
    String tableLocation = temp.resolve("iceberg-table").toFile().toString();

    HadoopTables tables = new HadoopTables(CONF);
    PartitionSpec spec = PartitionSpec.unpartitioned();
    Map<String, String> properties = Maps.newHashMap();
    properties.put(TableProperties.DEFAULT_WRITE_METRICS_MODE, "counts");
    Table table = tables.create(SIMPLE_SCHEMA, spec, properties, tableLocation);

    List<SimpleRecord> expectedRecords =
        Lists.newArrayList(
            new SimpleRecord(1, "a"), new SimpleRecord(2, "b"), new SimpleRecord(3, "c"));
    Dataset<Row> df = spark.createDataFrame(expectedRecords, SimpleRecord.class);
    df.select("id", "data")
        .coalesce(1)
        .write()
        .format("iceberg")
        .option(SparkWriteOptions.WRITE_FORMAT, "parquet")
        .mode(SaveMode.Append)
        .save(tableLocation);

    for (FileScanTask task : table.newScan().includeColumnStats().planFiles()) {
      DataFile file = task.file();
      assertThat(file.nullValueCounts()).hasSize(2);
      assertThat(file.valueCounts()).hasSize(2);
      assertThat(file.lowerBounds()).isEmpty();
      assertThat(file.upperBounds()).isEmpty();
    }
  }

  @Test
  public void testNoMetricsCollectionForParquet() {
    String tableLocation = temp.resolve("iceberg-table").toFile().toString();

    HadoopTables tables = new HadoopTables(CONF);
    PartitionSpec spec = PartitionSpec.unpartitioned();
    Map<String, String> properties = Maps.newHashMap();
    properties.put(TableProperties.DEFAULT_WRITE_METRICS_MODE, "none");
    Table table = tables.create(SIMPLE_SCHEMA, spec, properties, tableLocation);

    List<SimpleRecord> expectedRecords =
        Lists.newArrayList(
            new SimpleRecord(1, "a"), new SimpleRecord(2, "b"), new SimpleRecord(3, "c"));
    Dataset<Row> df = spark.createDataFrame(expectedRecords, SimpleRecord.class);
    df.select("id", "data")
        .coalesce(1)
        .write()
        .format("iceberg")
        .option(SparkWriteOptions.WRITE_FORMAT, "parquet")
        .mode(SaveMode.Append)
        .save(tableLocation);

    for (FileScanTask task : table.newScan().includeColumnStats().planFiles()) {
      DataFile file = task.file();
      assertThat(file.nullValueCounts()).isEmpty();
      assertThat(file.valueCounts()).isEmpty();
      assertThat(file.lowerBounds()).isEmpty();
      assertThat(file.upperBounds()).isEmpty();
    }
  }

  @Test
  public void testCustomMetricCollectionForParquet() {
    String tableLocation = temp.resolve("iceberg-table").toFile().toString();

    HadoopTables tables = new HadoopTables(CONF);
    PartitionSpec spec = PartitionSpec.unpartitioned();
    Map<String, String> properties = Maps.newHashMap();
    properties.put(TableProperties.DEFAULT_WRITE_METRICS_MODE, "counts");
    properties.put("write.metadata.metrics.column.id", "full");
    Table table = tables.create(SIMPLE_SCHEMA, spec, properties, tableLocation);

    List<SimpleRecord> expectedRecords =
        Lists.newArrayList(
            new SimpleRecord(1, "a"), new SimpleRecord(2, "b"), new SimpleRecord(3, "c"));
    Dataset<Row> df = spark.createDataFrame(expectedRecords, SimpleRecord.class);
    df.select("id", "data")
        .coalesce(1)
        .write()
        .format("iceberg")
        .option(SparkWriteOptions.WRITE_FORMAT, "parquet")
        .mode(SaveMode.Append)
        .save(tableLocation);

    Schema schema = table.schema();
    Types.NestedField id = schema.findField("id");
    for (FileScanTask task : table.newScan().includeColumnStats().planFiles()) {
      DataFile file = task.file();
      assertThat(file.nullValueCounts()).hasSize(2);
      assertThat(file.valueCounts()).hasSize(2);
      assertThat(file.lowerBounds()).hasSize(1).containsKey(id.fieldId());
      assertThat(file.upperBounds()).hasSize(1).containsKey(id.fieldId());
    }
  }

  @Test
  public void testBadCustomMetricCollectionForParquet() {
    String tableLocation = temp.resolve("iceberg-table").toFile().toString();

    HadoopTables tables = new HadoopTables(CONF);
    PartitionSpec spec = PartitionSpec.unpartitioned();
    Map<String, String> properties = Maps.newHashMap();
    properties.put(TableProperties.DEFAULT_WRITE_METRICS_MODE, "counts");
    properties.put("write.metadata.metrics.column.ids", "full");

    assertThatThrownBy(() -> tables.create(SIMPLE_SCHEMA, spec, properties, tableLocation))
        .isInstanceOf(ValidationException.class)
        .hasMessageStartingWith(
            "Invalid metrics config, could not find column ids from table prop write.metadata.metrics.column.ids in schema table");
  }

  @Test
  public void testCustomMetricCollectionForNestedParquet() {
    String tableLocation = temp.resolve("iceberg-table").toFile().toString();

    HadoopTables tables = new HadoopTables(CONF);
    PartitionSpec spec = PartitionSpec.builderFor(COMPLEX_SCHEMA).identity("strCol").build();
    Map<String, String> properties = Maps.newHashMap();
    properties.put(TableProperties.DEFAULT_WRITE_METRICS_MODE, "none");
    properties.put("write.metadata.metrics.column.longCol", "counts");
    properties.put("write.metadata.metrics.column.record.id", "full");
    properties.put("write.metadata.metrics.column.record.data", "truncate(2)");
    Table table = tables.create(COMPLEX_SCHEMA, spec, properties, tableLocation);

    Iterable<InternalRow> rows = RandomData.generateSpark(COMPLEX_SCHEMA, 10, 0);
    JavaRDD<InternalRow> rdd = sc.parallelize(Lists.newArrayList(rows));
    Preconditions.checkArgument(
        spark instanceof org.apache.spark.sql.classic.SparkSession,
        "Expected instance of org.apache.spark.sql.classic.SparkSession, but got: %s",
        spark.getClass().getName());

    Dataset<Row> df =
        ((org.apache.spark.sql.classic.SparkSession) spark)
            .internalCreateDataFrame(JavaRDD.toRDD(rdd), convert(COMPLEX_SCHEMA), false);

    df.coalesce(1)
        .write()
        .format("iceberg")
        .option(SparkWriteOptions.WRITE_FORMAT, "parquet")
        .mode(SaveMode.Append)
        .save(tableLocation);

    Schema schema = table.schema();
    Types.NestedField longCol = schema.findField("longCol");
    Types.NestedField recordId = schema.findField("record.id");
    Types.NestedField recordData = schema.findField("record.data");
    for (FileScanTask task : table.newScan().includeColumnStats().planFiles()) {
      DataFile file = task.file();

      Map<Integer, Long> nullValueCounts = file.nullValueCounts();
      assertThat(nullValueCounts)
          .hasSize(3)
          .containsKeys(longCol.fieldId(), recordId.fieldId(), recordData.fieldId());

      Map<Integer, Long> valueCounts = file.valueCounts();
      assertThat(valueCounts)
          .hasSize(3)
          .containsKeys(longCol.fieldId(), recordId.fieldId(), recordData.fieldId());

      Map<Integer, ByteBuffer> lowerBounds = file.lowerBounds();
      assertThat(lowerBounds).hasSize(2).containsKey(recordId.fieldId());

      ByteBuffer recordDataLowerBound = lowerBounds.get(recordData.fieldId());
      assertThat(ByteBuffers.toByteArray(recordDataLowerBound)).hasSize(2);

      Map<Integer, ByteBuffer> upperBounds = file.upperBounds();
      assertThat(upperBounds).hasSize(2).containsKey(recordId.fieldId());

      ByteBuffer recordDataUpperBound = upperBounds.get(recordData.fieldId());
      assertThat(ByteBuffers.toByteArray(recordDataUpperBound)).hasSize(2);
    }
  }
}
