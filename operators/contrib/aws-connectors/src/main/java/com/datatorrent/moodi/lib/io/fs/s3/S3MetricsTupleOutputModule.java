/*
 * Copyright (c) 2012-2017 DataTorrent, Inc.
 * All Rights Reserved.
 * The use of this source code is governed by the Limited License located at
 * https://www.datatorrent.com/datatorrent-openview-software-license/
 */

package com.datatorrent.moodi.lib.io.fs.s3;

import java.util.Arrays;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

import org.apache.apex.malhar.lib.fs.GenericFileOutputOperator.NoOpConverter;
import org.apache.apex.malhar.lib.fs.GenericFileOutputOperator.StringToBytesConverter;
import org.apache.apex.malhar.lib.fs.s3.*;
import org.apache.hadoop.conf.Configuration;

import com.google.common.base.Preconditions;

import com.datatorrent.api.Context;
import com.datatorrent.api.Context.OperatorContext;
import com.datatorrent.api.DAG;
import com.datatorrent.api.Module;
import com.datatorrent.api.StatsListener;
import com.datatorrent.lib.converter.Converter;
import com.datatorrent.lib.partitioner.StatelessThroughputBasedPartitioner;
import com.datatorrent.moodi.lib.io.fs.FSRecordCompactionOperator;

/**
 * S3MetricsTupleOutputModule writes incoming tuples into files and uploads these files on Amazon S3.
 *
 * @param <INPUT> Type of incoming Tuple.Converter needs to be defined which converts these tuples to byte[].
 * Default converters for String, byte[] tuples are provided in
 * S3MetricsTupleOutputModule.S3BytesOutputModule, S3MetricsTupleOutputModule.S3StringOutputModule
 *
 * @displayName S3 Tuple Output Module
 * @tags S3, Output
 *
 * @since 3.7.0
 */
@org.apache.hadoop.classification.InterfaceStability.Evolving
public abstract class S3MetricsTupleOutputModule<INPUT> implements Module
{
  public final transient ProxyInputPort<INPUT> input = new ProxyInputPort<INPUT>();
  public final transient ProxyOutputPort<org.apache.apex.malhar.lib.fs.FSRecordCompactionOperator.OutputMetaData> output = new ProxyOutputPort<org.apache.apex.malhar.lib.fs.FSRecordCompactionOperator.OutputMetaData>();

  /**
   * AWS access key
   */
  @NotNull
  private String accessKey;
  /**
   * AWS secret access key
   */
  @NotNull
  private String secretAccessKey;

  /**
   * S3 Region
   */
  private String region;
  /**
   * Name of the bucket in which to upload the files
   */
  @NotNull
  private String bucketName;

  /**
   * Path of the output directory. Relative path of the files copied will be
   * maintained w.r.t. source directory and output directory
   */
  @NotNull
  private String outputDirectoryPath;

  /**
   * Max number of idle windows for which no new data is added to current part
   * file. Part file will be finalized after these many idle windows after last
   * new data.
   */
  private long maxIdleWindows = 30;

  /**
   * The maximum length in bytes of a rolling file. The default value of this is
   * 1MB.
   */
  @Min(1)
  protected Long maxLength = 128 * 1024 * 1024L;

  /**
   * The stream is expired (closed and evicted from cache) after the specified duration has passed since it was last
   * accessed by a read or write.
   */
  private Long expireStreamAfterAccessMillis;

  /**
   * The files are rotated periodically after the specified value of windows have ended. If set to 0 this feature is
   * disabled.
   */
  protected Integer rotationWindows;

  /**
   * Separator between the tuples
   */
  private String tupleSeparator;

  /**
   * Minimum number of tuples per sec per partition for HDFS write.
   */
  private long minTuplesPerSecPerPartition = 30000;


  private boolean isCompactionParallelPartition = false;

  public void populateDAG(DAG dag, Configuration conf)
  {
    FSRecordCompactionOperator<INPUT> s3compaction = dag.addOperator("S3Compaction", new FSRecordCompactionOperator<INPUT>());
    s3compaction.setConverter(getConverter());
    s3compaction.setMaxIdleWindows(maxIdleWindows);
    s3compaction.setMaxLength(maxLength);
    if (expireStreamAfterAccessMillis != null) {
      s3compaction.setExpireStreamAfterAccessMillis(expireStreamAfterAccessMillis);
    }
    if (rotationWindows != null) {
      s3compaction.setRotationWindows(rotationWindows);
    }
    if (tupleSeparator != null) {
      s3compaction.setTupleSeparator(tupleSeparator);
    }

    dag.setInputPortAttribute(s3compaction.input, Context.PortContext.PARTITION_PARALLEL, isCompactionParallelPartition);
    S3Reconciler s3Reconciler = dag.addOperator("S3Reconciler", new S3Reconciler());
    s3Reconciler.setAccessKey(accessKey);
    s3Reconciler.setSecretKey(secretAccessKey);
    s3Reconciler.setBucketName(bucketName);
    if (region != null) {
      s3Reconciler.setRegion(region);
    }
    s3Reconciler.setDirectoryName(outputDirectoryPath);

    dag.addStream("write-to-s3", s3compaction.output, s3Reconciler.input);
    input.set(s3compaction.input);
    output.set(s3Reconciler.outputPort);
  }

  /**
   * Get the AWS access key
   *
   * @return AWS access key
   */
  public String getAccessKey()
  {
    return accessKey;
  }

  /**
   * Set the AWS access key
   *
   * @param accessKey
   *          access key
   */
  public void setAccessKey(@NotNull String accessKey)
  {
    this.accessKey = Preconditions.checkNotNull(accessKey);
  }

  /**
   * Return the AWS secret access key
   *
   * @return AWS secret access key
   */
  public String getSecretAccessKey()
  {
    return secretAccessKey;
  }

  /**
   * Set the AWS secret access key
   *
   * @param secretAccessKey
   *          AWS secret access key
   */
  public void setSecretAccessKey(@NotNull String secretAccessKey)
  {
    this.secretAccessKey = Preconditions.checkNotNull(secretAccessKey);
  }

  /**
   * Get the name of the bucket in which to upload the files
   *
   * @return bucket name
   */
  public String getBucketName()
  {
    return bucketName;
  }

  /**
   * Set the name of the bucket in which to upload the files
   *
   * @param bucketName
   *          name of the bucket
   */
  public void setBucketName(@NotNull String bucketName)
  {
    this.bucketName = Preconditions.checkNotNull(bucketName);
  }

  /**
   * Get the S3 Region
   * @return region
   */
  public String getRegion()
  {
    return region;
  }

  /**
   * Set the AWS S3 region
   * @param region region
   */
  public void setRegion(String region)
  {
    this.region = region;
  }

  /**
   * Get the path of the output directory.
   *
   * @return path of output directory
   */
  public String getOutputDirectoryPath()
  {
    return outputDirectoryPath;
  }

  /**
   * Set the path of the output directory.
   *
   * @param outputDirectoryPath
   *          path of output directory
   */
  public void setOutputDirectoryPath(@NotNull String outputDirectoryPath)
  {
    this.outputDirectoryPath = Preconditions.checkNotNull(outputDirectoryPath);
  }

  /**
   * No. of idle window after which file should be rolled over
   *
   * @return max number of idle windows for rollover
   */
  public long getMaxIdleWindows()
  {
    return maxIdleWindows;
  }

  /**
   * No. of idle window after which file should be rolled over
   *
   * @param maxIdleWindows
   *          max number of idle windows for rollover
   */
  public void setMaxIdleWindows(long maxIdleWindows)
  {
    this.maxIdleWindows = maxIdleWindows;
  }

  /**
   * Get max length of file after which file should be rolled over
   *
   * @return max length of file
   */
  public Long getMaxLength()
  {
    return maxLength;
  }

  /**
   * Set max length of file after which file should be rolled over
   *
   * @param maxLength
   *          max length of file
   */
  public void setMaxLength(Long maxLength)
  {
    this.maxLength = maxLength;
  }

  /**
   * Returns whether the compaction operator is parallel partitioned with the upstream operator
   * @return isCompactionParallelPartition
   */
  public boolean isCompactionParallelPartition()
  {
    return isCompactionParallelPartition;
  }

  /**
   * Sets whether the compaction operator would be partitioned parallel or not.
   * @param compactionParallelPartition isCompactionParallelPartition
   */
  public void setCompactionParallelPartition(boolean compactionParallelPartition)
  {
    isCompactionParallelPartition = compactionParallelPartition;
  }

  /**
   * Returns the duration of stream expired in milliseconds
   * @return expireStreamAfterAccessMillis
   */
  public Long getExpireStreamAfterAccessMillis()
  {
    return expireStreamAfterAccessMillis;
  }

  /**
   * Sets the duration of stream expired in milliseconds
   * @param expireStreamAfterAccessMillis given expireStreamAfterAccessMillis
   */
  public void setExpireStreamAfterAccessMillis(Long expireStreamAfterAccessMillis)
  {
    this.expireStreamAfterAccessMillis = expireStreamAfterAccessMillis;
  }

  /**
   * Returns the file rotation interval.
   * @return rotationWindows
   */
  public Integer getRotationWindows()
  {
    return rotationWindows;
  }

  /**
   * Sets the file rotation interval.
   * @param rotationWindows given rotationWindows
   */
  public void setRotationWindows(Integer rotationWindows)
  {
    this.rotationWindows = rotationWindows;
  }

  /**
   * Return the tuple separator string
   * @return tupleSeparator
   */
  public String getTupleSeparator()
  {
    return tupleSeparator;
  }

  /**
   * Sets the tuple separator string
   * @param tupleSeparator given tupleSeparator
   */
  public void setTupleSeparator(String tupleSeparator)
  {
    this.tupleSeparator = tupleSeparator;
  }

  /**
   * Converter for conversion of input tuples to byte[]
   *
   * @return converter
   */
  protected abstract Converter<INPUT, byte[]> getConverter();

  public static class S3BytesOutputModule extends S3MetricsTupleOutputModule<byte[]>
  {
    @Override
    protected Converter<byte[], byte[]> getConverter()
    {
      return new NoOpConverter();
    }
  }

  public static class S3StringOutputModule extends S3MetricsTupleOutputModule<String>
  {
    @Override
    protected Converter<String, byte[]> getConverter()
    {
      return new StringToBytesConverter();
    }
  }
}
