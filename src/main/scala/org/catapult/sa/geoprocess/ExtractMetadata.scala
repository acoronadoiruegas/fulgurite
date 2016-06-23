package org.catapult.sa.geoprocess

import java.io.{File, IOException, PrintStream}
import javax.imageio.ImageIO

import org.catapult.sa.spark.{Argument, Arguments}
import org.w3c.dom.Node

/**
  * Extract the meta data from a GeoTiff and output it as an XML file
  */
object ExtractMetadata extends Arguments {
  override def defaultArgs(): Map[String, String] = Map(
    "in" -> "C:/data/Will/OUREA_SiteB_24102915_WV_processedImg_mk2.tif"
  )

  override def allArgs(): List[Argument] = List("in")

  def main(args : Array[String]) : Unit = {
    val opts = processArgs(args, defaultArgs())
    val file = new File(opts("in"))

    if (file == null || !file.canRead || !file.isFile) {
      throw new IOException("Can not read " + opts("in"))
    }

    val iis = ImageIO.createImageInputStream(file)
    val readers = ImageIO.getImageReaders(iis)


    if (readers.hasNext) {
      val reader = readers.next()
      reader.setInput(iis, true, false)

      val meta = reader.getImageMetadata(0)

      //val output = new PrintStream(new FileOutputStream(opts("out")))

      meta.getMetadataFormatNames.foreach(k => {
        //displayMeta(output, meta.getAsTree(k), 0)
        displayMeta(System.out, meta.getAsTree(k), 0)
      })

      //output.flush()
      //output.close()

      reader.dispose()
    }

  }


  private def displayMeta(ps : PrintStream, node : Node, level : Int) : Unit = {
    indent(ps, level)

    ps.print("<" + node.getNodeName)
    val attribute = node.getAttributes
    if (attribute != null) {
      (0 until attribute.getLength).foreach(i => {
        val attr = attribute.item(i)
        ps.print(" " + attr.getNodeName + "=\"" + attr.getNodeValue + "\"")
      })
    }

    if (node.hasChildNodes) {
      ps.println(">")
      var child = node.getFirstChild
      while (child != null) {
        displayMeta(ps, child, level + 1)
        child = child.getNextSibling
      }

      indent(ps, level)
      ps.println("</" + node.getNodeName + ">")
    } else {
      ps.println("/>")
    }
  }

  private def indent(ps : PrintStream, level : Int): Unit = {
    ps.print((0 until level).map(l => "\t").mkString)
  }
}
