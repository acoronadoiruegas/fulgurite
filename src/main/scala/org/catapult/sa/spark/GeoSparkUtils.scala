package org.catapult.sa.spark

import java.io.File
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadata

import com.github.jaiimageio.impl.plugins.tiff.TIFFImageMetadata
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.{BytesWritable, LongWritable}
import org.apache.hadoop.mapreduce.lib.output.SequenceFileAsBinaryOutputFormat
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.catapult.sa.geotiff.{DataPoint, GeoTiffMeta, Index}

import scala.collection.mutable

/**
  * Utility functions for working with geo tiff files.
  */
object GeoSparkUtils {

  /**
    * Read a geotiff and return pixels as records.
    *
    * @param path path to the file to read
    * @param meta the metadata from the file.
    * @param sc spark context to use.
    * @return RDD of Index to DataPoint
    */
  def GeoTiffRDD(path : String, meta: GeoTiffMeta, sc : SparkContext) : RDD[(Index, DataPoint)] = {
    val bytesPerRecord = (meta.bitsPerSample.sum + 7) / 8 // int division make sure it always rounds up hence +7

    val inputConf = new Configuration()
    inputConf.setInt(GeoTiffBinaryInputFormat.RECORD_LENGTH_PROPERTY, bytesPerRecord)
    inputConf.setLong(GeoTiffBinaryInputFormat.RECORD_START_OFFSET_PROPERTY, meta.startOffset)
    inputConf.setLong(GeoTiffBinaryInputFormat.RECORD_END_OFFSET_PROPERTY, meta.endOffset)

    sc.newAPIHadoopFile[LongWritable, BytesWritable, GeoTiffBinaryInputFormat](
      path,
      classOf[GeoTiffBinaryInputFormat],
      classOf[LongWritable],
      classOf[BytesWritable],
      inputConf
    ).map(createKeyValue(meta, bytesPerRecord))
  }

  def saveGeoTiff[T <: DataPoint](rdd : RDD[(Index,  T)], meta : GeoTiffMeta, baseMeta: IIOMetadata, path : String) : Unit = {
    rdd.map { case (i, d) =>
        i.y -> (i.i -> d)
      }
      .aggregateByKey(mutable.Buffer.empty[(Long, T)], meta.height.toInt)({ (b, d) => b.append(d); b }, {(a, b) => a.appendAll(b); a })
      .sortByKey()
      .map { case (x, b) =>
        val converter = createBytes(meta)
        new BytesWritable(Array.emptyByteArray) -> new BytesWritable(b.sortBy(_._1).foldLeft(mutable.Buffer.empty[Byte]) { (b, v) => b.appendAll(converter(v._2)); b}.padTo(meta.width.toInt, 0x0.asInstanceOf[Byte]).toArray)
      }
      .saveAsNewAPIHadoopFile(path, classOf[BytesWritable], classOf[BytesWritable], classOf[SequenceFileAsBinaryOutputFormat])
    saveMetaData(meta, baseMeta, path)
  }

  def saveMetaData(meta : GeoTiffMeta, baseMeta: IIOMetadata, path : String) : Unit = {
    val clonedMeta = baseMeta

    val ios = ImageIO.createImageOutputStream(new File(path, "header.tiff"))
    val writers = ImageIO.getImageWritersByFormatName("tiff")
    if (writers.hasNext) {
      val writer = writers.next()
      writer.setOutput(ios)
      writer.prepareWriteSequence(clonedMeta)

      var headerNext = ios.getStreamPosition
      val header = headerNext - 4
      ios.seek(header)

      // Ensure IFD is written on a word boundary
      headerNext = (headerNext + 3) & ~0x3

      // Write the pointer to the first IFD after the header.
      ios.writeInt(headerNext.toInt)

      clonedMeta.asInstanceOf[TIFFImageMetadata].getRootIFD.writeToStream(ios)

      /*if (ios.getStreamPosition < meta.startOffset) {
        println("writing padding in header." + (meta.startOffset - ios.getStreamPosition))
        ios.write(Array.fill[Byte]((meta.startOffset - ios.getStreamPosition).toInt) { 0.asInstanceOf[Byte] } )
      }*/

      ios.flush()
      ios.close()
    }
  }

  private def createBytes[T <: DataPoint](meta : GeoTiffMeta) : (T) => Array[Byte] = {
    meta.samplesPerPixel match {
      case 3 => if (meta.bitsPerSample(0) == 8 && meta.bitsPerSample(1) == 8  && meta.bitsPerSample(2) == 8) {
        (e : T) => Array(e.r.asInstanceOf[Byte], e.g.asInstanceOf[Byte], e.b.asInstanceOf[Byte])
      } else {
        throw new UnsupportedOperationException("can not yet handle more than one byte colours. Find this error and implement")
      }
      case _ => throw new UnsupportedOperationException("can not yet handle more than three colours. Find this error and implement")
    }
  }

  private def createKeyValue(meta : GeoTiffMeta, bytesPerRecord : Int) : ((LongWritable, BytesWritable)) => (Index, DataPoint) = {
    val conv = DataPoint.buildConverter(meta)
    val width = meta.width / bytesPerRecord
    (e : (LongWritable, BytesWritable)) => Index(e._1.get(), width) -> conv(e._2.getBytes)
  }

}
