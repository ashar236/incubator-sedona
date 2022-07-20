/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.datasources.parquet

import java.net.URI
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.{Failure, Try}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileStatus, Path}
import org.apache.hadoop.mapred.FileSplit
import org.apache.hadoop.mapreduce._
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl
import org.apache.parquet.filter2.compat.FilterCompat
import org.apache.parquet.filter2.predicate.FilterApi
import org.apache.parquet.format.converter.ParquetMetadataConverter.SKIP_ROW_GROUPS
import org.apache.parquet.hadoop._
import org.apache.parquet.hadoop.ParquetOutputFormat.JobSummaryLevel
import org.apache.parquet.hadoop.codec.CodecConfig
import org.apache.parquet.hadoop.util.ContextUtil
import org.apache.spark.TaskContext
import org.apache.spark.internal.Logging
import org.apache.spark.sql._
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.codegen.GenerateUnsafeProjection
import org.apache.spark.sql.catalyst.parser.LegacyTypeStringParser
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.errors.QueryExecutionErrors
import org.apache.spark.sql.execution.WholeStageCodegenExec
import org.apache.spark.sql.execution.datasources._
import org.apache.spark.sql.execution.datasources.parquet.ParquetFileFormat.readParquetFootersInParallel
import org.apache.spark.sql.execution.vectorized.{OffHeapColumnVector, OnHeapColumnVector}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.sedona_sql.UDT.GeometryUDT
import org.apache.spark.sql.sources._
import org.apache.spark.sql.types._
import org.apache.spark.util.{SerializableConfiguration, ThreadUtils}

class GeoParquetFileFormat
  extends ParquetFileFormat with FileFormat
    with DataSourceRegister
    with Logging
    with Serializable {
  // Hold a reference to the (serializable) singleton instance of ParquetLogRedirector. This
  // ensures the ParquetLogRedirector class is initialized whether an instance of ParquetFileFormat
  // is constructed or deserialized. Do not heed the Scala compiler's warning about an unused field
  // here.
  private val parquetLogRedirector = ParquetLogRedirector.INSTANCE

  override def shortName(): String = "geoparquet"

  override def inferSchema(
                            sparkSession: SparkSession,
                            parameters: Map[String, String],
                            files: Seq[FileStatus]): Option[StructType] = {
    val fieldGeometry = new GeoParquetOptions(parameters).fieldGeometry
    GeometryField.setFieldGeometry(fieldGeometry)
    GeoParquetUtils.inferSchema(sparkSession, parameters, files)
  }

  override def buildReaderWithPartitionValues(
                                               sparkSession: SparkSession,
                                               dataSchema: StructType,
                                               partitionSchema: StructType,
                                               requiredSchema: StructType,
                                               filters: Seq[Filter],
                                               options: Map[String, String],
                                               hadoopConf: Configuration): (PartitionedFile) => Iterator[InternalRow] = {
    hadoopConf.set(ParquetInputFormat.READ_SUPPORT_CLASS, classOf[ParquetReadSupport].getName)
    hadoopConf.set(
      ParquetReadSupport.SPARK_ROW_REQUESTED_SCHEMA,
      requiredSchema.json)
    hadoopConf.set(
      ParquetWriteSupport.SPARK_ROW_SCHEMA,
      requiredSchema.json)
    hadoopConf.set(
      SQLConf.SESSION_LOCAL_TIMEZONE.key,
      sparkSession.sessionState.conf.sessionLocalTimeZone)
    hadoopConf.setBoolean(
      SQLConf.NESTED_SCHEMA_PRUNING_ENABLED.key,
      sparkSession.sessionState.conf.nestedSchemaPruningEnabled)
    hadoopConf.setBoolean(
      SQLConf.CASE_SENSITIVE.key,
      sparkSession.sessionState.conf.caseSensitiveAnalysis)

    // Sets flags for `ParquetToSparkSchemaConverter`
    hadoopConf.setBoolean(
      SQLConf.PARQUET_BINARY_AS_STRING.key,
      sparkSession.sessionState.conf.isParquetBinaryAsString)
    hadoopConf.setBoolean(
      SQLConf.PARQUET_INT96_AS_TIMESTAMP.key,
      sparkSession.sessionState.conf.isParquetINT96AsTimestamp)

    val broadcastedHadoopConf =
      sparkSession.sparkContext.broadcast(new SerializableConfiguration(hadoopConf))

    // TODO: if you move this into the closure it reverts to the default values.
    // If true, enable using the custom RecordReader for parquet. This only works for
    // a subset of the types (no complex types).
    val resultSchema = StructType(partitionSchema.fields ++ requiredSchema.fields)
    val sqlConf = sparkSession.sessionState.conf
    val enableOffHeapColumnVector = sqlConf.offHeapColumnVectorEnabled
    val enableVectorizedReader: Boolean =
      ParquetUtils.isBatchReadSupportedForSchema(sqlConf, resultSchema)
    val enableRecordFilter: Boolean = sqlConf.parquetRecordFilterEnabled
    val timestampConversion: Boolean = sqlConf.isParquetINT96TimestampConversion
    val capacity = sqlConf.parquetVectorizedReaderBatchSize
    val enableParquetFilterPushDown: Boolean = sqlConf.parquetFilterPushDown
    // Whole stage codegen (PhysicalRDD) is able to deal with batches directly
    val returningBatch = supportBatch(sparkSession, resultSchema)
    val pushDownDate = sqlConf.parquetFilterPushDownDate
    val pushDownTimestamp = sqlConf.parquetFilterPushDownTimestamp
    val pushDownDecimal = sqlConf.parquetFilterPushDownDecimal
    val pushDownStringStartWith = sqlConf.parquetFilterPushDownStringStartWith
    val pushDownInFilterThreshold = sqlConf.parquetFilterPushDownInFilterThreshold
    val isCaseSensitive = sqlConf.caseSensitiveAnalysis
    val parquetOptions = new ParquetOptions(options, sparkSession.sessionState.conf)
    val datetimeRebaseModeInRead = parquetOptions.datetimeRebaseModeInRead
    val int96RebaseModeInRead = parquetOptions.int96RebaseModeInRead

    (file: PartitionedFile) => {
      assert(file.partitionValues.numFields == partitionSchema.size)

      val filePath = new Path(new URI(file.filePath))
      val split = new FileSplit(filePath, file.start, file.length, Array.empty[String])

      val sharedConf = broadcastedHadoopConf.value.value

      lazy val footerFileMetaData =
        ParquetFooterReader.readFooter(sharedConf, filePath, SKIP_ROW_GROUPS).getFileMetaData
      val datetimeRebaseSpec = DataSourceUtils.datetimeRebaseSpec(
        footerFileMetaData.getKeyValueMetaData.get,
        datetimeRebaseModeInRead)
      // Try to push down filters when filter push-down is enabled.
      val pushed = if (enableParquetFilterPushDown) {
        val parquetSchema = footerFileMetaData.getSchema
        val parquetFilters = new ParquetFilters(
          parquetSchema,
          pushDownDate,
          pushDownTimestamp,
          pushDownDecimal,
          pushDownStringStartWith,
          pushDownInFilterThreshold,
          isCaseSensitive,
          datetimeRebaseSpec)
        filters
          // Collects all converted Parquet filter predicates. Notice that not all predicates can be
          // converted (`ParquetFilters.createFilter` returns an `Option`). That's why a `flatMap`
          // is used here.
          .flatMap(parquetFilters.createFilter(_))
          .reduceOption(FilterApi.and)
      } else {
        None
      }

      // PARQUET_INT96_TIMESTAMP_CONVERSION says to apply timezone conversions to int96 timestamps'
      // *only* if the file was created by something other than "parquet-mr", so check the actual
      // writer here for this file.  We have to do this per-file, as each file in the table may
      // have different writers.
      // Define isCreatedByParquetMr as function to avoid unnecessary parquet footer reads.
      def isCreatedByParquetMr: Boolean =
        footerFileMetaData.getCreatedBy().startsWith("parquet-mr")

      val convertTz =
        if (timestampConversion && !isCreatedByParquetMr) {
          Some(DateTimeUtils.getZoneId(sharedConf.get(SQLConf.SESSION_LOCAL_TIMEZONE.key)))
        } else {
          None
        }

      val int96RebaseSpec = DataSourceUtils.int96RebaseSpec(
        footerFileMetaData.getKeyValueMetaData.get,
        int96RebaseModeInRead)

      val attemptId = new TaskAttemptID(new TaskID(new JobID(), TaskType.MAP, 0), 0)
      val hadoopAttemptContext =
        new TaskAttemptContextImpl(broadcastedHadoopConf.value.value, attemptId)

      // Try to push down filters when filter push-down is enabled.
      // Notice: This push-down is RowGroups level, not individual records.
      if (pushed.isDefined) {
        ParquetInputFormat.setFilterPredicate(hadoopAttemptContext.getConfiguration, pushed.get)
      }
      val taskContext = Option(TaskContext.get())
      if (enableVectorizedReader) {
        val vectorizedReader = new VectorizedParquetRecordReader(
          convertTz.orNull,
          datetimeRebaseSpec.mode.toString,
          datetimeRebaseSpec.timeZone,
          int96RebaseSpec.mode.toString,
          int96RebaseSpec.timeZone,
          enableOffHeapColumnVector && taskContext.isDefined,
          capacity)
        // SPARK-37089: We cannot register a task completion listener to close this iterator here
        // because downstream exec nodes have already registered their listeners. Since listeners
        // are executed in reverse order of registration, a listener registered here would close the
        // iterator while downstream exec nodes are still running. When off-heap column vectors are
        // enabled, this can cause a use-after-free bug leading to a segfault.
        //
        // Instead, we use FileScanRDD's task completion listener to close this iterator.
        val iter = new RecordReaderIterator(vectorizedReader)
        try {
          vectorizedReader.initialize(split, hadoopAttemptContext)
          logDebug(s"Appending $partitionSchema ${file.partitionValues}")
          vectorizedReader.initBatch(partitionSchema, file.partitionValues)
          if (returningBatch) {
            vectorizedReader.enableReturningBatches()
          }

          // UnsafeRowParquetRecordReader appends the columns internally to avoid another copy.
          iter.asInstanceOf[Iterator[InternalRow]]
        } catch {
          case e: Throwable =>
            // SPARK-23457: In case there is an exception in initialization, close the iterator to
            // avoid leaking resources.
            iter.close()
            throw e
        }
      } else {
        logDebug(s"Falling back to parquet-mr")
        // ParquetRecordReader returns InternalRow //FIXME here is class
        val readSupport = new GeoParquetReadSupport(
          convertTz,
          enableVectorizedReader = false,
          datetimeRebaseSpec,
          int96RebaseSpec)
        val reader = if (pushed.isDefined && enableRecordFilter) {
          val parquetFilter = FilterCompat.get(pushed.get, null)
          new ParquetRecordReader[InternalRow](readSupport, parquetFilter)
        } else {
          new ParquetRecordReader[InternalRow](readSupport)
        }
        val iter = new RecordReaderIterator[InternalRow](reader)
        try {
          reader.initialize(split, hadoopAttemptContext)

          val fullSchema = requiredSchema.toAttributes ++ partitionSchema.toAttributes
          val unsafeProjection = GenerateUnsafeProjection.generate(fullSchema, fullSchema)

          if (partitionSchema.length == 0) {
            // There is no partition columns
            iter.map(unsafeProjection)
          } else {
            val joinedRow = new JoinedRow()
            iter.map(d => unsafeProjection(joinedRow(d, file.partitionValues)))
          }
        } catch {
          case e: Throwable =>
            // SPARK-23457: In case there is an exception in initialization, close the iterator to
            // avoid leaking resources.
            iter.close()
            throw e
        }
      }
    }
  }

//FIXME This can be removed, default definition is true always, anyway this function is also true always for most datatypes

  override def supportDataType(dataType: DataType): Boolean = dataType match {
    case _: AtomicType => true

    case _: GeometryUDT => true

    case st: StructType => st.forall { f => supportDataType(f.dataType) }

    case ArrayType(elementType, _) => supportDataType(elementType)

    case MapType(keyType, valueType, _) =>
      supportDataType(keyType) && supportDataType(valueType)

    case udt: UserDefinedType[_] => supportDataType(udt.sqlType)

    case _ => false
  }
}

object GeoParquetFileFormat extends Logging {
  private[parquet] def readSchema(
                                   footers: Seq[Footer], sparkSession: SparkSession): Option[StructType] = {
//TODO No change is required here
    val converter = new GeoParquetToSparkSchemaConverter(
      sparkSession.sessionState.conf.isParquetBinaryAsString,
      sparkSession.sessionState.conf.isParquetINT96AsTimestamp)

    val seen = mutable.HashSet[String]()
    val finalSchemas: Seq[StructType] = footers.flatMap { footer =>
      val metadata = footer.getParquetMetadata.getFileMetaData
      val serializedSchema = metadata
        .getKeyValueMetaData
        .asScala.toMap
        .get(GeoParquetReadSupport.SPARK_METADATA_KEY)
      if (serializedSchema.isEmpty) {
        // Falls back to Parquet schema if no Spark SQL schema found.
        Some(converter.convert(metadata.getSchema))
      } else if (!seen.contains(serializedSchema.get)) {
        seen += serializedSchema.get

        // Don't throw even if we failed to parse the serialized Spark schema. Just fallback to
        // whatever is available.
        Some(Try(DataType.fromJson(serializedSchema.get))
          .recover { case _: Throwable =>
            logInfo(
              "Serialized Spark schema in Parquet key-value metadata is not in JSON format, " +
                "falling back to the deprecated DataType.fromCaseClassString parser.")
            LegacyTypeStringParser.parseString(serializedSchema.get)
          }
          .recover { case cause: Throwable =>
            logWarning(
              s"""Failed to parse serialized Spark schema in Parquet key-value metadata:
                 |\t$serializedSchema
               """.stripMargin,
              cause)
          }
          .map(_.asInstanceOf[StructType])
          .getOrElse {
            // Falls back to Parquet schema if Spark SQL schema can't be parsed.
            converter.convert(metadata.getSchema)
          })
      } else {
        None
      }
    }

    finalSchemas.reduceOption { (left, right) =>
      try left.merge(right) catch { case e: Throwable =>
        throw QueryExecutionErrors.failedToMergeIncompatibleSchemasError(left, right, e)
      }
    }
  }

// FIXME Nevere getting used anywhere till now, may be in future
  //TODO TO remove??
  private[parquet] def readGeoParquetFootersInParallel(
                                                     conf: Configuration,
                                                     partFiles: Seq[FileStatus],
                                                     ignoreCorruptFiles: Boolean): Seq[Footer] = {
    ThreadUtils.parmap(partFiles, "readingParquetFooters", 8) { currentFile =>
      try {
        // Skips row group information since we only need the schema.
        // ParquetFileReader.readFooter throws RuntimeException, instead of IOException,
        // when it can't read the footer.
        Some(new Footer(currentFile.getPath(),
          ParquetFooterReader.readFooter(
            conf, currentFile, SKIP_ROW_GROUPS)))
      } catch { case e: RuntimeException =>
        if (ignoreCorruptFiles) {
          logWarning(s"Skipped the footer in the corrupted file: $currentFile", e)
          None
        } else {
          throw QueryExecutionErrors.cannotReadFooterForFileError(currentFile, e)
        }
      }
    }.flatten
  }

  /**
   * Figures out a merged Parquet schema with a distributed Spark job.
   *
   * Note that locality is not taken into consideration here because:
   *
   *  1. For a single Parquet part-file, in most cases the footer only resides in the last block of
   *     that file.  Thus we only need to retrieve the location of the last block.  However, Hadoop
   *     `FileSystem` only provides API to retrieve locations of all blocks, which can be
   *     potentially expensive.
   *
   *  2. This optimization is mainly useful for S3, where file metadata operations can be pretty
   *     slow.  And basically locality is not available when using S3 (you can't run computation on
   *     S3 nodes).
   */
  // TODO necessary
  def mergeSchemasInParallel(
                              parameters: Map[String, String],
                              filesToTouch: Seq[FileStatus],
                              sparkSession: SparkSession): Option[StructType] = {
    val assumeBinaryIsString = sparkSession.sessionState.conf.isParquetBinaryAsString
    val assumeInt96IsTimestamp = sparkSession.sessionState.conf.isParquetINT96AsTimestamp

    val reader = (files: Seq[FileStatus], conf: Configuration, ignoreCorruptFiles: Boolean) => {
      // Converter used to convert Parquet `MessageType` to Spark SQL `StructType`
      val converter = new GeoParquetToSparkSchemaConverter(
        assumeBinaryIsString = assumeBinaryIsString,
        assumeInt96IsTimestamp = assumeInt96IsTimestamp)
      readParquetFootersInParallel(conf, files, ignoreCorruptFiles)
        .map(GeoParquetFileFormat.readSchemaFromFooter(_, converter))
    }

    SchemaMergeUtils.mergeSchemasInParallel(sparkSession, parameters, filesToTouch, reader)
  }

  /**
   * Reads Spark SQL schema from a Parquet footer.  If a valid serialized Spark SQL schema string
   * can be found in the file metadata, returns the deserialized [[StructType]], otherwise, returns
   * a [[StructType]] converted from the [[org.apache.parquet.schema.MessageType]] stored in this
   * footer.
   */
// TODO necessary
  def readSchemaFromFooter(
                            footer: Footer, converter: GeoParquetToSparkSchemaConverter): StructType = {
    val fileMetaData = footer.getParquetMetadata.getFileMetaData
    fileMetaData
      .getKeyValueMetaData
      .asScala.toMap
      .get(GeoParquetReadSupport.SPARK_METADATA_KEY)
      .flatMap(deserializeSchemaString)
      .getOrElse(converter.convert(fileMetaData.getSchema))
  }

  private def deserializeSchemaString(schemaString: String): Option[StructType] = {
    // Tries to deserialize the schema string as JSON first, then falls back to the case class
    // string parser (data generated by older versions of Spark SQL uses this format).
    Try(DataType.fromJson(schemaString).asInstanceOf[StructType]).recover {
      case _: Throwable =>
        logInfo(
          "Serialized Spark schema in Parquet key-value metadata is not in JSON format, " +
            "falling back to the deprecated DataType.fromCaseClassString parser.")
        LegacyTypeStringParser.parseString(schemaString).asInstanceOf[StructType]
    }.recoverWith {
      case cause: Throwable =>
        logWarning(
          "Failed to parse and ignored serialized Spark schema in " +
            s"Parquet key-value metadata:\n\t$schemaString", cause)
        Failure(cause)
    }.toOption
  }

}
