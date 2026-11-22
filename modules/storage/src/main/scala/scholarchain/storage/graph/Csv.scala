package scholarchain.storage.graph

import java.io.{BufferedReader, Reader}
import scala.collection.mutable.ArrayBuffer

/** RFC 4180 CSV, UTF-8. Records end with CRLF. A field is quoted only when it contains a comma, a
  * double quote, CR or LF; a quote inside a quoted field is doubled.
  */
object Csv:
  val RecordSeparator = "\r\n"

  def field(value: String): String =
    if value.exists(c => c == ',' || c == '"' || c == '\r' || c == '\n') then
      "\"" + value.replace("\"", "\"\"") + "\""
    else value

  def record(fields: Seq[String]): String = fields.map(field).mkString(",") + RecordSeparator

  /** Streaming parser: yields one record at a time, honouring quoted commas, quotes and line
    * breaks. Accepts CRLF or LF record ends. A trailing empty line is not a record.
    */
  def parse(reader: Reader): Iterator[Vector[String]] = new Iterator[Vector[String]]:
    private val in = new BufferedReader(reader)
    private var peeked: Option[Vector[String]] = None
    private var done = false

    private def readRecord(): Option[Vector[String]] =
      var c = in.read()
      if c < 0 then None
      else
        val fields = ArrayBuffer.empty[String]
        val sb = new java.lang.StringBuilder
        var inQuotes = false
        var finished = false
        var sawAnything = false
        while !finished do
          if c < 0 then finished = true
          else
            sawAnything = true
            val ch = c.toChar
            if inQuotes then
              if ch == '"' then
                in.mark(1)
                val nxt = in.read()
                if nxt == '"'.toInt then sb.append('"')
                else
                  inQuotes = false
                  if nxt >= 0 then in.reset()
              else sb.append(ch)
            else if ch == '"' then inQuotes = true
            else if ch == ',' then
              fields += sb.toString
              sb.setLength(0)
            else if ch == '\r' then
              in.mark(1)
              val nxt = in.read()
              if nxt != '\n'.toInt && nxt >= 0 then in.reset()
              finished = true
            else if ch == '\n' then finished = true
            else sb.append(ch)
            if !finished then c = in.read()
        if !sawAnything then None
        else
          fields += sb.toString
          Some(fields.toVector)

    def hasNext: Boolean =
      if peeked.isDefined then true
      else if done then false
      else
        readRecord() match
          case None =>
            done = true
            false
          case some =>
            peeked = some
            true

    def next(): Vector[String] =
      if !hasNext then throw new NoSuchElementException("end of CSV")
      val r = peeked.get
      peeked = None
      r
