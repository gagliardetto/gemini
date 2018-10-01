package tech.sourced.gemini


import com.datastax.spark.connector.cql.CassandraConnector
import gopkg.in.bblfsh.sdk.v1.uast.generated.Node
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.cassandra._
import org.apache.spark.sql.functions._
import org.apache.spark.sql.{DataFrame, Dataset, Row, SparkSession}
import org.apache.spark.storage.StorageLevel
import org.slf4j.{Logger => Slf4jLogger}
import tech.sourced.engine._
import tech.sourced.featurext.SparkFEClient
import tech.sourced.featurext.generated.service.Feature
import tech.sourced.gemini.util.MapAccumulator

import scala.collection.JavaConverters._


case class RDDFeatureKey(token: String, doc: String)
case class RDDFeature(key: RDDFeatureKey, weight: Long)
case class RDDHash(doc: String, wmh: Array[Array[Long]])
case class HashResult(files: DataFrame, hashes: RDD[RDDHash], docFreq: OrderedDocFreq)

/**
  * Implements hashing repositories using Apache Spark
  *
  * @param session spark session
  * @param log logger implementation
  * @param docFreqPath path to DocFreq
  */
class Hash(session: SparkSession, log: Slf4jLogger, docFreqPath: String = "") {
  import session.implicits._

  def report(header: String, countProcessed: Long, skipped: MapAccumulator): Unit = {
    log.warn(header)
    val skippedSeq = skipped.value.toSeq
    val countSkipped = skippedSeq.map(_._2).sum
    log.warn(s"Processed: $countProcessed, skipped: $countSkipped")
    skippedSeq.sortBy(-_._2) foreach { case (key, value) => log.warn(s"\t$key -> $value") }
  }

  /**
    * Calculates hashes and docFreq
    *
    * @param repos DataFrame with engine.getRepositories schema
    */
  def forRepos(repos: DataFrame): HashResult = {
    val feSkippedFiles = mapAccumulator(session.sparkContext, "FE skipped files")

    val files = filesForRepos(repos).persist(StorageLevel.MEMORY_AND_DISK_SER)
    val uasts = extractUast(files).persist(StorageLevel.MEMORY_AND_DISK_SER)
    val features = extractFeatures(uasts, feSkippedFiles).cache()
    report("Feature Extraction exceptions", features.count, feSkippedFiles)

    val docFreq = makeDocFreq(uasts, features)
    val hashes = hashFeaturesDF(docFreq.value, features)

    HashResult(files, hashes.rdd, docFreq.value)
  }

  /**
    * Save hashing results to database
    *
    * @param hashResult
    * @param keyspace
    * @param tables
    * @param docFreqPath
    */
  def save(hashResult: HashResult, keyspace: String, tables: Tables, docFreqPath: String): Unit ={
    saveMeta(hashResult.files, keyspace, tables)
    if (docFreqPath.isEmpty) {
      saveDocFreqToDB(hashResult.docFreq, keyspace, tables)
    } else {
      log.warn(s"save document frequencies to JSON")
      hashResult.docFreq.saveToJson(docFreqPath)
    }
    saveHashes(hashResult.hashes, keyspace, tables)
  }

  protected def filesForRepos(repos: DataFrame): DataFrame = {
    log.warn("Listing files")

    repos
      .getHEAD
      .getCommits
      .getTreeEntries
      .getBlobs
      .filter('is_binary === false)
  }

  protected def extractUast(files: DataFrame): DataFrame = {
    log.warn("Extracting UASTs")

    files
      .dropDuplicates("blob_id")
      .classifyLanguages
      .extractUASTs
      .select("repository_id", "path", "blob_id", "uast")
      .filter(_.getAs[Seq[Array[Byte]]]("uast").nonEmpty)
      .withColumn("document", expr("CONCAT(repository_id, '//', path, '@', blob_id)"))
      .select("document", "uast")
  }

  def extractFeatures(uastsDF: DataFrame, skippedFiles: MapAccumulator): DataFrame = {
    log.warn("Extracting features")

    val feConfig = SparkFEClient.getConfig(session)
    uastsDF
      .flatMap { row =>
        val uastArr = row.getAs[Seq[Array[Byte]]]("uast")
        val features = uastArr.flatMap { byteArr =>
          val uast = Node.parseFrom(byteArr)
          SparkFEClient.extract(uast, feConfig, Some(skippedFiles))
       }
        features.map(feat => (feat.name, row.getAs[String]("document"), feat.weight.toLong))
       }
      .toDF("feature", "doc", "weight")
  }

  def makeDocFreq(uasts: DataFrame, features: DataFrame): Broadcast[OrderedDocFreq] = {
    log.warn("creating document frequencies")
    val docs = uasts.select("document").distinct().count()
    val df = features
      .select("feature", "doc")
      .distinct
      .groupBy("feature")
      .agg(count("*").alias("cnt"))
      .map(row => (row.getAs[String]("feature"), row.getAs[Long]("cnt").toInt))
      .collect.toMap
    val tokens = df.keys.toArray.sorted
    session.sparkContext.broadcast(OrderedDocFreq(docs.toInt, tokens, df))
  }

  /**
    * Hash features using RDD representation.
    *
    * This impl is retrofitted to new Dataset API so it is a dorp-in replacement for hashFeaturesDF(),
    * but it uses RDD inside in order to benefit from groupByKey/mapPartitions perf optimizations.
    *
    * This is expected to scale better to full PGA, as hashing is done in parallel for each partition.
    *
    * @param docFreq
    * @param features
    * @return
    */
  def hashFeaturesRDD(
    docFreq: OrderedDocFreq,
    features: DataFrame
  ): Dataset[RDDHash] = {
    log.warn("hashing features")

    val tf = features.rdd
      .map { case Row(feature: String, doc: String, weight: Long) => (RDDFeatureKey(feature, doc), weight) }
      .reduceByKey(_ + _)

    val tfIdf = tf
      .map(row => (row._1.doc, Feature(row._1.token, row._2)))
      .groupByKey(session.sparkContext.defaultParallelism)
      .mapPartitions { partIter =>
        val wmh = FeaturesHash.initWmh(docFreq.tokens.size) // ~1.6 Gb (for 1 PGA bucket)
        partIter.map { case (doc, features) =>
          RDDHash(doc, wmh.hash(FeaturesHash.toBagOfFeatures(features.iterator, docFreq)))
        }
      }
    tfIdf.toDS()
  }

  /**
    * Hash features using Dataset representation.
    */
  def hashFeaturesDF(
    docFreq: OrderedDocFreq,
    features: DataFrame
  ): Dataset[RDDHash] = {
    log.warn("hashing features")

    val tf = features.groupBy("feature", "doc").sum("weight").alias("weight")
    val tfIdf = tf
      .map { case Row(token: String, doc: String, weight: Long) => (doc, Feature(token, weight)) }
      .groupByKey { case (doc, _) => doc }
      .mapGroups { (doc, features) =>
        val wmh = FeaturesHash.initWmh(docFreq.tokens.size) // ~1.6 Gb RAM (for 1 PGA bucket)
        RDDHash(doc, wmh.hash(FeaturesHash.toBagOfFeatures(features.map(_._2), docFreq)))
      }
    tfIdf
  }

  protected def saveDocFreqToDB(docFreq: OrderedDocFreq, keyspace: String, tables: Tables): Unit = {
    log.warn(s"save document frequencies to DB")
    CassandraConnector(session.sparkContext).withSessionDo { cassandra =>
      val cols = tables.docFreqCols
      val javaMap = docFreq.df.asJava

      cassandra.execute(
        s"INSERT INTO $keyspace.${tables.docFreq} (${cols.id}, ${cols.docs}, ${cols.df}) VALUES (?, ?, ?)",
        Gemini.docFreqId, int2Integer(docFreq.docs), javaMap
      )
    }
  }

  protected def saveMeta(files: DataFrame, keyspace: String, tables: Tables): Unit = {
    log.warn("save meta to DB")

    val cols = tables.metaCols
    val renamedFiles = files
      .select("blob_id", "repository_id", "commit_hash", "path")
      .withColumnRenamed("blob_id", cols.sha)
      .withColumnRenamed("repository_id", cols.repo)
      .withColumnRenamed("commit_hash", cols.commit)
      .withColumnRenamed("path", cols.path)

    val approxFileCount = renamedFiles.rdd.countApprox(10000L)
    log.info(s"Writing $approxFileCount files to DB")
    renamedFiles.write
      .mode("append")
      .cassandraFormat(tables.meta, keyspace)
      .save()
  }

  protected def saveHashes(rdd: RDD[RDDHash], keyspace: String, tables: Tables): Unit = {
    log.warn("save hashtables to DB")

    val cols = tables.hashtablesCols
    rdd
      .flatMap { case RDDHash(doc, wmh) =>
        FeaturesHash.wmhToBands(wmh).zipWithIndex.map{ case (band, i) => (doc, i, band) }
      }
      .toDF(cols.sha, cols.hashtable, cols.value)
      .write
      .mode("append")
      .cassandraFormat(tables.hashtables, keyspace)
      .save()
  }

   def mapAccumulator(sc: SparkContext, name: String): MapAccumulator = {
    val acc = new MapAccumulator
    sc.register(acc, name)
    acc
  }
}

object Hash {
    def apply(s: SparkSession, log: Slf4jLogger): Hash = new Hash(s, log)
}
