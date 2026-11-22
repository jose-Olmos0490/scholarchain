package scholarchain.storage

import java.nio.file.{Files, Path}
import org.apache.parquet.example.data.Group
import org.apache.parquet.example.data.simple.SimpleGroup
import org.apache.parquet.hadoop.ParquetFileWriter
import org.apache.parquet.hadoop.example.ExampleParquetWriter
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.io.LocalOutputFile
import org.apache.parquet.schema.{GroupType, MessageType, MessageTypeParser}

/** Column types of the fixture Parquet files, modelled on what Spark writes for the Phase 6
  * tables: every column optional, UTF-8 strings, 3-level standard lists (`list.element`) and
  * `key_value` maps.
  */
enum PType:
  case Str, I32, I64, Dbl
  case StrList
  case Struct(fields: Vector[(String, PType)])
  case StructList(fields: Vector[(String, PType)])
  case StrMap

object ParquetFixtureWriter:
  import PType.*

  private def render(name: String, t: PType): String = t match
    case Str => s"optional binary $name (STRING);"
    case I32 => s"optional int32 $name;"
    case I64 => s"optional int64 $name;"
    case Dbl => s"optional double $name;"
    case StrList =>
      s"optional group $name (LIST) { repeated group list { optional binary element (STRING); } }"
    case Struct(fs) => s"optional group $name { ${fs.map(renderField).mkString(" ")} }"
    case StructList(fs) =>
      s"optional group $name (LIST) { repeated group list { optional group element { ${fs.map(renderField).mkString(" ")} } } }"
    case StrMap =>
      s"optional group $name (MAP) { repeated group key_value { required binary key (STRING); optional binary value (STRING); } }"

  private def renderField(f: (String, PType)): String = render(f._1, f._2)

  def schema(fields: Vector[(String, PType)]): MessageType =
    MessageTypeParser.parseMessageType(
      s"message spark_schema { ${fields.map(renderField).mkString(" ")} }"
    )

  private def populate(g: Group, fields: Vector[(String, PType)], row: ujson.Obj): Unit =
    fields.foreach { (name, t) =>
      row.value.get(name) match
        case None | Some(ujson.Null) => ()
        case Some(v) => add(g, name, t, v)
    }

  private def add(g: Group, name: String, t: PType, v: ujson.Value): Unit = (t, v) match
    case (Str, ujson.Str(s)) => g.add(name, s)
    case (I32, ujson.Num(n)) => g.add(name, n.toInt)
    case (I64, ujson.Num(n)) => g.add(name, n.toLong)
    case (Dbl, ujson.Num(n)) => g.add(name, n)
    case (StrList, ujson.Arr(items)) =>
      val l = g.addGroup(name)
      items.foreach(i => l.addGroup("list").add("element", i.str))
    case (Struct(fs), o: ujson.Obj) => populate(g.addGroup(name), fs, o)
    case (StructList(fs), ujson.Arr(items)) =>
      val l = g.addGroup(name)
      items.foreach(i => populate(l.addGroup("list").addGroup("element"), fs, i.obj))
    case (StrMap, o: ujson.Obj) =>
      val m = g.addGroup(name)
      o.value.foreach { (k, x) =>
        val kv = m.addGroup("key_value")
        kv.add("key", k)
        kv.add("value", x.str)
      }
    case (other, value) =>
      throw new IllegalArgumentException(s"column $name: cannot write ${value.render()} as $other")

  /** Writes `rows` (one ujson object per record) as one snappy-compressed Parquet file. */
  def write(
      path: Path,
      fields: Vector[(String, PType)],
      rows: Vector[ujson.Obj],
      rowGroupBytes: Option[Long] = None
  ): Unit =
    Files.createDirectories(path.getParent)
    val msg = schema(fields)
    val b = ExampleParquetWriter
      .builder(new LocalOutputFile(path))
      .withType(msg)
      .withCompressionCodec(CompressionCodecName.SNAPPY)
      .withWriteMode(ParquetFileWriter.Mode.OVERWRITE)
    // a tiny row group size and a size check after every record force many row groups
    rowGroupBytes.foreach { n =>
      b.withRowGroupSize(n)
      b.withMinRowCountForPageSizeCheck(1)
      b.withMaxRowCountForPageSizeCheck(1)
    }
    val writer = b.build()
    try
      rows.foreach { r =>
        val g = new SimpleGroup(msg)
        populate(g, fields, r)
        writer.write(g)
      }
    finally writer.close()
