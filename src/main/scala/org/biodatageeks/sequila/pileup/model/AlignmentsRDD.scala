package org.biodatageeks.sequila.pileup.model

import java.util

import htsjdk.samtools.SAMRecord
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.biodatageeks.sequila.datasources.BAM.BAMTableReader
import org.biodatageeks.sequila.pileup.conf.Conf
import org.biodatageeks.sequila.pileup.model.Alts.MultiLociAlts
import org.biodatageeks.sequila.pileup.model.Quals.MultiLociQuals
import org.biodatageeks.sequila.pileup.model.ReadOperations.implicits._
import org.biodatageeks.sequila.pileup.partitioning.{LowerPartitionBoundAlignmentRecord, PartitionBounds, PartitionUtils, RangePartitionCoalescer}
import org.biodatageeks.sequila.rangejoins.IntervalTree.Interval
import org.biodatageeks.sequila.rangejoins.methods.IntervalTree.IntervalHolderChromosome
import org.biodatageeks.sequila.utils.{DataQualityFuncs, FastMath, InternalParams}
import org.seqdoop.hadoop_bam.BAMBDGInputFormat
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.JavaConverters._
import scala.collection.{JavaConverters, mutable}

object AlignmentsRDDOperations {
  object implicits {
    implicit def alignmentsRdd(rdd: RDD[SAMRecord]) = AlignmentsRDD(rdd)
  }
}

case class AlignmentsRDD(rdd: RDD[SAMRecord]) {
  val logger: Logger = LoggerFactory.getLogger(this.getClass.getCanonicalName)

  /**
    * Collects "interesting" (read start, stop, ref/nonref counting) events on alignments
    *
    * @return distributed collection of PileupRecords
    */
  def assembleContigAggregates(conf: Broadcast[Conf]): RDD[ContigAggregate] = {
    val contigLenMap = initContigLengths(this.rdd.first())

    this.rdd.mapPartitions { partition =>
      val aggMap = new mutable.HashMap[String, ContigAggregate]()
      var contigIter, contigCleanIter  = ""
      var currentContig = ""
      var contigAggregate: ContigAggregate = null
        while (partition.hasNext) {
          val read = partition.next()
          val contig =
            if(read.getContig == contigIter)
              contigCleanIter
            else {
              contigIter = read.getContig
              contigCleanIter =  DataQualityFuncs.cleanContig(contigIter)
              contigCleanIter
            }
          if ( contig != currentContig ) {
              handleFirstReadForContigInPartition(read, contig, contigLenMap, aggMap, conf)
              currentContig = contig
          }
          contigAggregate = aggMap(contig)
          read.analyzeRead(contigAggregate, conf)
        }
        val aggregates = prepareOutputAggregates(aggMap, conf).toIterator
        aggregates

    }
  }


  /**
    * transforms map structure of contigEventAggregates, by reducing number of last zeroes in the cov array
    * also adds calculated maxCigar len to output
    *
    * @param aggMap   mapper between contig and contigEventAggregate
    * @param cigarMap mapper between contig and max length of cigar in given
    * @return
    */
  def prepareOutputAggregates(aggMap: mutable.HashMap[String, ContigAggregate],
                             conf: Broadcast[Conf]): Array[ContigAggregate] = {
    val output = new Array[ContigAggregate](aggMap.size)
    var i = 0
    val iter = aggMap.toIterator
    while(iter.hasNext){
      val nextVal = iter.next()
      val contig = nextVal._1
      val contigEventAgg = nextVal._2

      val maxIndex: Int = FastMath.findMaxIndex(contigEventAgg.events)
      val agg = ContigAggregate(
        contig,
        contigEventAgg.contigLen,
        util.Arrays.copyOfRange(contigEventAgg.events, 0, maxIndex + 1), //FIXME: https://stackoverflow.com/questions/37969193/why-is-array-slice-so-shockingly-slow
        contigEventAgg.alts,
        contigEventAgg.trimQuals,
        contigEventAgg.startPosition,
        contigEventAgg.startPosition + maxIndex,
        conf
      )
      output(i) = agg
      i += 1
    }
    output
  }


  private def handleFirstReadForContigInPartition(read: SAMRecord, contig: String, contigLenMap: Map[String, Int],
                                                  aggMap: mutable.HashMap[String, ContigAggregate],
                                                  conf: Broadcast[Conf]
                                                  ):Unit = {
    val contigLen = contigLenMap(contig)
    val arrayLen = contigLen - read.getStart + 10

    val contigEventAggregate = ContigAggregate(
      contig = contig,
      contigLen = contigLen,
      events = new Array[Short](arrayLen),
      alts = new MultiLociAlts(),
      quals = if(conf.value.includeBaseQualities ) new MultiLociQuals() else null,
      startPosition = read.getStart,
      maxPosition = contigLen - 1,
      conf = conf )
    aggMap += contig -> contigEventAggregate
  }

  /**
    * initializes mapper between contig and its length basing on header values
    *
    * @param read single aligned read (its header contains info about all contigs)
    * @return
    */
  def initContigLengths(read: SAMRecord): Map[String, Int] = {
    val contigLenMap = new mutable.HashMap[String, Int]()
    val sequenceList = read.getHeader.getSequenceDictionary.getSequences
    val sequenceSeq = JavaConverters.asScalaIteratorConverter(sequenceList.iterator()).asScala.toSeq

    for (sequence <- sequenceSeq) {
      val contigName = DataQualityFuncs.cleanContig(sequence.getSequenceName)
      contigLenMap += contigName -> sequence.getSequenceLength
    }
    contigLenMap.toMap
  }

  def filterByConfig(conf : Broadcast[Conf], spark: SparkSession): RDD[SAMRecord] = {
    // any other filtering conditions should go here
    val filterFlag = spark.conf.get(InternalParams.filterReadsByFlag, conf.value.filterFlag).toInt
    val cleaned = this.rdd.filter(read => read.getContig != null && (read.getFlags & filterFlag) == 0)
    if(logger.isDebugEnabled()) logger.debug("Processing {} cleaned reads in total", cleaned.count() )
    cleaned
  }


  def getPartitionLowerBound: Array[LowerPartitionBoundAlignmentRecord] = {
    this.rdd.mapPartitionsWithIndex{
      (i, p) => Iterator(LowerPartitionBoundAlignmentRecord(i ,p.next()) )
    }.collect()
  }

  def getPartitionBounds(reader: BAMTableReader[BAMBDGInputFormat],
                         conf: Conf,
                         lowerBounds: Array[LowerPartitionBoundAlignmentRecord]
                        ): Array[PartitionBounds] = {

    val contigsList = reader
      .readFile
      .first()
      .getHeader
      .getSequenceDictionary
      .getSequences
      .asScala
      .map(r => r.getContig)
      .toArray


    SparkSession.builder.config(this.rdd.sparkContext.getConf).getOrCreate()
      .sqlContext
      .setConf(InternalParams.AlignmentIntervals, PartitionUtils.boundsToIntervals(lowerBounds))
    logger.info(s"Getting bounds overlapping reads for intervals: ${PartitionUtils.boundsToIntervals(lowerBounds)}")
    val boundsOverlappingReads = reader.readFile
      .filter(r => !r.getReadUnmappedFlag )
      .map( r => (r.getContig, Interval(r.getStart, r.getEnd), TruncRead(r.getReadName, r.getContig, r.getStart, r.getEnd)) )
      .collect()

    SparkSession.builder.config(this.rdd.sparkContext.getConf).getOrCreate()
      .sqlContext
      .setConf(InternalParams.AlignmentIntervals, "")
    logger.info(s"Found ${boundsOverlappingReads.length} overlapping reads")
    val tree = new IntervalHolderChromosome[TruncRead](boundsOverlappingReads, "org.biodatageeks.sequila.rangejoins.methods.IntervalTree.IntervalTreeRedBlack")

    PartitionUtils.getAdjustedPartitionBounds(lowerBounds, tree, conf, contigsList)
  }

  def repartition(reader:BAMTableReader[BAMBDGInputFormat], conf: Conf): (RDD[SAMRecord], Broadcast[Array[PartitionBounds]]) = {

    val alignments = this.rdd
    val numPartitions = alignments.getNumPartitions
    val lowerBounds = getPartitionLowerBound // get the start of first read in partition
    val adjBounds = getPartitionBounds(reader, conf, lowerBounds)
    val maxEndIndex = PartitionUtils.getMaxEndPartitionIndex(adjBounds, lowerBounds)
    val broadcastBounds = this.rdd.sparkContext.broadcast(adjBounds)
    logger.info(s"Final partition bounds: ${adjBounds.mkString("|")}")
    logger.info(s"MaxEndIndex list ${maxEndIndex.mkString("|")}")
    val alignments2 = alignments.coalesce(alignments.getNumPartitions,false, Some(new RangePartitionCoalescer(maxEndIndex.map(r=> new Integer(r )).asJava )) )
    val adjustedAlignments = alignments2.mapPartitionsWithIndex {
      (i, p) => {
        val bounds = broadcastBounds.value(i)
        p.takeWhile(r =>
          if (r.getReadUnmappedFlag) true
          else if (i == numPartitions - 1) true // read the whole last partition
          else if (bounds.wholeContigs.contains(r.getContig) ) true //read all records between upper and lower contigs
          else if (
            (r.getContig == bounds.contigStart && r.getAlignmentStart  <= bounds.posEnd
              ) ||
              (r.getContig == bounds.contigEnd  && r.getAlignmentEnd <= bounds.posEnd ) ) true
          else {
            logger.info(s"Finishing reading partition with read ${r.getReadName}, ${r.getContig}:${r.getAlignmentEnd}")
            false
          })
      }
    }
    (adjustedAlignments, broadcastBounds)
  }
}