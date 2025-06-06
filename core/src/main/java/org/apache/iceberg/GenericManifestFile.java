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
package org.apache.iceberg;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;
import org.apache.avro.Schema;
import org.apache.avro.generic.IndexedRecord;
import org.apache.avro.specific.SpecificData.SchemaConstructable;
import org.apache.iceberg.avro.AvroSchemaUtil;
import org.apache.iceberg.avro.SupportsIndexProjection;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.relocated.com.google.common.base.MoreObjects;
import org.apache.iceberg.relocated.com.google.common.base.Objects;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.ByteBuffers;

public class GenericManifestFile extends SupportsIndexProjection
    implements ManifestFile, StructLike, IndexedRecord, SchemaConstructable, Serializable {
  private static final Schema AVRO_SCHEMA =
      AvroSchemaUtil.convert(ManifestFile.schema(), "manifest_file");
  private static final ManifestContent[] MANIFEST_CONTENT_VALUES = ManifestContent.values();

  private transient Schema avroSchema; // not final for Java serialization

  // data fields
  private InputFile file = null;
  private String manifestPath = null;
  private Long length = null;
  private int specId = -1;
  private ManifestContent content = ManifestContent.DATA;
  private long sequenceNumber = 0;
  private long minSequenceNumber = 0;
  private Long snapshotId = null;
  private Integer addedFilesCount = null;
  private Integer existingFilesCount = null;
  private Integer deletedFilesCount = null;
  private Long addedRowsCount = null;
  private Long existingRowsCount = null;
  private Long deletedRowsCount = null;
  private PartitionFieldSummary[] partitions = null;
  private byte[] keyMetadata = null;
  private Long firstRowId = null;

  /** Used by Avro reflection to instantiate this class when reading manifest files. */
  public GenericManifestFile(Schema avroSchema) {
    super(ManifestFile.schema().asStruct(), AvroSchemaUtil.convert(avroSchema).asStructType());
    this.avroSchema = avroSchema;
  }

  /** Used by Avro reflection to instantiate this class when reading manifest files. */
  GenericManifestFile(Types.StructType projectedSchema) {
    super(ManifestFile.schema().asStruct(), projectedSchema);
    this.avroSchema = AVRO_SCHEMA;
  }

  GenericManifestFile(InputFile file, int specId, long snapshotId) {
    super(ManifestFile.schema().columns().size());
    this.avroSchema = AVRO_SCHEMA;
    this.file = file;
    this.manifestPath = file.location();
    this.length = null; // lazily loaded from file
    this.specId = specId;
    this.sequenceNumber = 0;
    this.minSequenceNumber = 0;
    this.snapshotId = snapshotId;
    this.addedFilesCount = null;
    this.addedRowsCount = null;
    this.existingFilesCount = null;
    this.existingRowsCount = null;
    this.deletedFilesCount = null;
    this.deletedRowsCount = null;
    this.partitions = null;
    this.keyMetadata = null;
    this.firstRowId = null;
  }

  /** Adjust the arg order to avoid conflict with the public constructor below */
  GenericManifestFile(
      String path,
      long length,
      int specId,
      ManifestContent content,
      long sequenceNumber,
      long minSequenceNumber,
      Long snapshotId,
      List<PartitionFieldSummary> partitions,
      ByteBuffer keyMetadata,
      Integer addedFilesCount,
      Long addedRowsCount,
      Integer existingFilesCount,
      Long existingRowsCount,
      Integer deletedFilesCount,
      Long deletedRowsCount,
      Long firstRowId) {
    super(ManifestFile.schema().columns().size());
    this.avroSchema = AVRO_SCHEMA;
    this.manifestPath = path;
    this.length = length;
    this.specId = specId;
    this.content = content;
    this.sequenceNumber = sequenceNumber;
    this.minSequenceNumber = minSequenceNumber;
    this.snapshotId = snapshotId;
    this.addedFilesCount = addedFilesCount;
    this.addedRowsCount = addedRowsCount;
    this.existingFilesCount = existingFilesCount;
    this.existingRowsCount = existingRowsCount;
    this.deletedFilesCount = deletedFilesCount;
    this.deletedRowsCount = deletedRowsCount;
    this.partitions = partitions == null ? null : partitions.toArray(new PartitionFieldSummary[0]);
    this.keyMetadata = ByteBuffers.toByteArray(keyMetadata);
    this.firstRowId = firstRowId;
  }

  /**
   * Copy constructor.
   *
   * @param toCopy a generic manifest file to copy.
   */
  private GenericManifestFile(GenericManifestFile toCopy) {
    super(toCopy);
    this.avroSchema = toCopy.avroSchema;
    this.manifestPath = toCopy.manifestPath;
    try {
      this.length = toCopy.length();
    } catch (UnsupportedOperationException e) {
      // Can be removed when embedded manifests are dropped
      // DummyFileIO does not support .length()
      this.length = null;
    }
    this.specId = toCopy.specId;
    this.content = toCopy.content;
    this.sequenceNumber = toCopy.sequenceNumber;
    this.minSequenceNumber = toCopy.minSequenceNumber;
    this.snapshotId = toCopy.snapshotId;
    this.addedFilesCount = toCopy.addedFilesCount;
    this.addedRowsCount = toCopy.addedRowsCount;
    this.existingFilesCount = toCopy.existingFilesCount;
    this.existingRowsCount = toCopy.existingRowsCount;
    this.deletedFilesCount = toCopy.deletedFilesCount;
    this.deletedRowsCount = toCopy.deletedRowsCount;
    if (toCopy.partitions != null) {
      this.partitions =
          Stream.of(toCopy.partitions)
              .map(PartitionFieldSummary::copy)
              .toArray(PartitionFieldSummary[]::new);
    } else {
      this.partitions = null;
    }
    this.keyMetadata =
        toCopy.keyMetadata == null
            ? null
            : Arrays.copyOf(toCopy.keyMetadata, toCopy.keyMetadata.length);
    this.firstRowId = toCopy.firstRowId;
  }

  /** Constructor for Java serialization. */
  GenericManifestFile() {
    super(ManifestFile.schema().columns().size());
  }

  @Override
  public String path() {
    return manifestPath;
  }

  public Long lazyLength() {
    if (length == null) {
      if (file != null) {
        // this was created from an input file and length is lazily loaded
        this.length = file.getLength();
      } else {
        // this was loaded from a file without projecting length, throw an exception
        return null;
      }
    }
    return length;
  }

  @Override
  public long length() {
    return lazyLength();
  }

  @Override
  public int partitionSpecId() {
    return specId;
  }

  @Override
  public ManifestContent content() {
    return content;
  }

  @Override
  public long sequenceNumber() {
    return sequenceNumber;
  }

  @Override
  public long minSequenceNumber() {
    return minSequenceNumber;
  }

  @Override
  public Long snapshotId() {
    return snapshotId;
  }

  @Override
  public Integer addedFilesCount() {
    return addedFilesCount;
  }

  @Override
  public Long addedRowsCount() {
    return addedRowsCount;
  }

  @Override
  public Integer existingFilesCount() {
    return existingFilesCount;
  }

  @Override
  public Long existingRowsCount() {
    return existingRowsCount;
  }

  @Override
  public Integer deletedFilesCount() {
    return deletedFilesCount;
  }

  @Override
  public Long deletedRowsCount() {
    return deletedRowsCount;
  }

  @Override
  public List<PartitionFieldSummary> partitions() {
    return partitions == null ? null : Arrays.asList(partitions);
  }

  @Override
  public ByteBuffer keyMetadata() {
    return keyMetadata == null ? null : ByteBuffer.wrap(keyMetadata);
  }

  @Override
  public Long firstRowId() {
    return firstRowId;
  }

  @Override
  public int size() {
    return ManifestFile.schema().columns().size();
  }

  @Override
  public Object get(int pos) {
    return internalGet(pos, Object.class);
  }

  @Override
  protected <T> T internalGet(int pos, Class<T> javaClass) {
    return javaClass.cast(getByPos(pos));
  }

  private Object getByPos(int basePos) {
    switch (basePos) {
      case 0:
        return manifestPath;
      case 1:
        return lazyLength();
      case 2:
        return specId;
      case 3:
        return content.id();
      case 4:
        return sequenceNumber;
      case 5:
        return minSequenceNumber;
      case 6:
        return snapshotId;
      case 7:
        return addedFilesCount;
      case 8:
        return existingFilesCount;
      case 9:
        return deletedFilesCount;
      case 10:
        return addedRowsCount;
      case 11:
        return existingRowsCount;
      case 12:
        return deletedRowsCount;
      case 13:
        return partitions();
      case 14:
        return keyMetadata();
      case 15:
        return firstRowId();
      default:
        throw new UnsupportedOperationException("Unknown field ordinal: " + basePos);
    }
  }

  @Override
  protected <T> void internalSet(int basePos, T value) {
    switch (basePos) {
      case 0:
        // always coerce to String for Serializable
        this.manifestPath = value.toString();
        return;
      case 1:
        this.length = (Long) value;
        return;
      case 2:
        this.specId = (Integer) value;
        return;
      case 3:
        this.content =
            value != null ? MANIFEST_CONTENT_VALUES[(Integer) value] : ManifestContent.DATA;
        return;
      case 4:
        this.sequenceNumber = value != null ? (Long) value : 0;
        return;
      case 5:
        this.minSequenceNumber = value != null ? (Long) value : 0;
        return;
      case 6:
        this.snapshotId = (Long) value;
        return;
      case 7:
        this.addedFilesCount = (Integer) value;
        return;
      case 8:
        this.existingFilesCount = (Integer) value;
        return;
      case 9:
        this.deletedFilesCount = (Integer) value;
        return;
      case 10:
        this.addedRowsCount = (Long) value;
        return;
      case 11:
        this.existingRowsCount = (Long) value;
        return;
      case 12:
        this.deletedRowsCount = (Long) value;
        return;
      case 13:
        this.partitions =
            value == null
                ? null
                : ((List<PartitionFieldSummary>) value).toArray(new PartitionFieldSummary[0]);
        return;
      case 14:
        this.keyMetadata = ByteBuffers.toByteArray((ByteBuffer) value);
        return;
      case 15:
        this.firstRowId = (Long) value;
        return;
      default:
        // ignore the object, it must be from a newer version of the format
    }
  }

  @Override
  public void put(int i, Object v) {
    set(i, v);
  }

  @Override
  public ManifestFile copy() {
    return new GenericManifestFile(this);
  }

  @Override
  public Schema getSchema() {
    return avroSchema;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    } else if (!(other instanceof GenericManifestFile)) {
      return false;
    }
    GenericManifestFile that = (GenericManifestFile) other;
    return Objects.equal(manifestPath, that.manifestPath);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(manifestPath);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("content", content)
        .add("path", manifestPath)
        .add("length", length)
        .add("partition_spec_id", specId)
        .add("added_snapshot_id", snapshotId)
        .add("added_data_files_count", addedFilesCount)
        .add("added_rows_count", addedRowsCount)
        .add("existing_data_files_count", existingFilesCount)
        .add("existing_rows_count", existingRowsCount)
        .add("deleted_data_files_count", deletedFilesCount)
        .add("deleted_rows_count", deletedRowsCount)
        .add("partitions", partitions)
        .add("key_metadata", keyMetadata == null ? "null" : "(redacted)")
        .add("sequence_number", sequenceNumber)
        .add("min_sequence_number", minSequenceNumber)
        .add("first_row_id", firstRowId)
        .toString();
  }

  public static CopyBuilder copyOf(ManifestFile manifestFile) {
    return new CopyBuilder(manifestFile);
  }

  public static class CopyBuilder {
    private final GenericManifestFile manifestFile;

    private CopyBuilder(ManifestFile toCopy) {
      if (toCopy instanceof GenericManifestFile) {
        this.manifestFile = new GenericManifestFile((GenericManifestFile) toCopy);
      } else {
        this.manifestFile =
            new GenericManifestFile(
                toCopy.path(),
                toCopy.length(),
                toCopy.partitionSpecId(),
                toCopy.content(),
                toCopy.sequenceNumber(),
                toCopy.minSequenceNumber(),
                toCopy.snapshotId(),
                copyList(toCopy.partitions(), PartitionFieldSummary::copy),
                toCopy.keyMetadata(),
                toCopy.addedFilesCount(),
                toCopy.addedRowsCount(),
                toCopy.existingFilesCount(),
                toCopy.existingRowsCount(),
                toCopy.deletedFilesCount(),
                toCopy.deletedRowsCount(),
                toCopy.firstRowId());
      }
    }

    public CopyBuilder withSnapshotId(Long newSnapshotId) {
      manifestFile.snapshotId = newSnapshotId;
      return this;
    }

    public ManifestFile build() {
      return manifestFile;
    }
  }

  private static <E, R> List<R> copyList(List<E> list, Function<E, R> transform) {
    if (list != null) {
      List<R> copy = Lists.newArrayListWithExpectedSize(list.size());
      for (E element : list) {
        copy.add(transform.apply(element));
      }
      return copy;
    }
    return null;
  }
}
