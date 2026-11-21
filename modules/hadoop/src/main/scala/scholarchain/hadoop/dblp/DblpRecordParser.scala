package scholarchain.hadoop.dblp

import java.io.StringReader
import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset.{Charset, CodingErrorAction}
import javax.xml.stream.{XMLInputFactory, XMLStreamConstants, XMLStreamException}
import scala.util.control.NonFatal
import scholarchain.hadoop.text.Normalize

/** One child element of a DBLP record, in document order.
  *
  * @param text
  *   all character data inside the element (mixed content such as `<i>` or `<sub>` inside a title
  *   is flattened), with whitespace runs collapsed
  */
final case class DblpField(name: String, text: String, attributes: Map[String, String])

/** A DBLP record as read from XML, before normalization. */
final case class DblpRawRecord(
    element: String,
    attributes: Map[String, String],
    fields: Vector[DblpField]
):
  def key: Option[String] = attributes.get("key").map(_.trim).filter(_.nonEmpty)
  def all(name: String): Vector[DblpField] = fields.filter(_.name == name)

  /** Text of the first `name` field that is not blank (`Normalize.isBlank`): a blank earlier field
    * never hides a later non-blank one (for example an invisible `<journal>` before a real one).
    */
  def first(name: String): Option[String] =
    fields.iterator.filter(_.name == name).map(_.text).find(t => !Normalize.isBlank(t))

/** Why a record could not be read. */
enum DblpParseFailure:
  case UnknownEntity(name: String)
  case Malformed(message: String)

/** Parses one record (as produced by [[DblpRecordReader]]) into a [[DblpRawRecord]].
  *
  * Entities are resolved with the DTD's entity table ([[DtdEntities.substitute]]); the XML parser
  * then runs with DTD support, external entities and network access disabled.
  */
object DblpRecordParser:

  private def newFactory(): XMLInputFactory =
    val f = XMLInputFactory.newInstance()
    f.setProperty(XMLInputFactory.SUPPORT_DTD, false)
    f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false)
    f.setProperty(XMLInputFactory.IS_COALESCING, true)
    f

  /** Decodes record bytes strictly: a byte sequence that is malformed or unmappable in the
    * declared `charset` is a fatal error (XML 1.0 §4.3.3), never replaced by U+FFFD. `Left` holds
    * a message naming the charset and the byte offset inside the record.
    */
  def decodeStrict(bytes: Array[Byte], length: Int, charset: Charset): Either[String, String] =
    val decoder = charset
      .newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    val in = ByteBuffer.wrap(bytes, 0, length)
    val out =
      CharBuffer.allocate(math.max(16, (length * decoder.maxCharsPerByte().toDouble).toInt + 1))
    val r1 = decoder.decode(in, out, true)
    val r = if r1.isError then r1 else decoder.flush(out)
    if r.isError then
      Left(
        s"byte sequence not legal in the declared encoding ${charset.name} at record byte offset ${in.position}"
      )
    else
      out.flip()
      Right(out.toString)

  /** Parses raw record bytes in `charset` (strictly decoded, see [[decodeStrict]]). */
  def parse(
      bytes: Array[Byte],
      length: Int,
      charset: Charset,
      entities: DtdEntities
  ): Either[DblpParseFailure, DblpRawRecord] =
    decodeStrict(bytes, length, charset) match
      case Left(msg) => Left(DblpParseFailure.Malformed(msg))
      case Right(xml) => parseString(xml, entities)

  def parseString(xml: String, entities: DtdEntities): Either[DblpParseFailure, DblpRawRecord] =
    DtdEntities.substitute(xml, entities) match
      case Left(name) => Left(DblpParseFailure.UnknownEntity(name))
      case Right(resolved) =>
        try Right(read(resolved))
        catch
          case e: XMLStreamException =>
            Left(DblpParseFailure.Malformed(Option(e.getMessage).getOrElse(e.toString)))
          case NonFatal(e) =>
            Left(DblpParseFailure.Malformed(Option(e.getMessage).getOrElse(e.toString)))

  private def attrs(r: javax.xml.stream.XMLStreamReader): Map[String, String] =
    (0 until r.getAttributeCount)
      .map(i => r.getAttributeLocalName(i) -> r.getAttributeValue(i))
      .toMap

  private def read(xml: String): DblpRawRecord =
    val r = newFactory().createXMLStreamReader(new StringReader(xml))
    try
      // Root element.
      while r.hasNext && r.next() != XMLStreamConstants.START_ELEMENT do ()
      if r.getEventType != XMLStreamConstants.START_ELEMENT then
        throw new XMLStreamException("no record element")
      val element = r.getLocalName
      val rootAttrs = attrs(r)
      val fields = Vector.newBuilder[DblpField]
      var depth = 1
      var fieldName: String = null
      var fieldAttrs: Map[String, String] = Map.empty
      val text = new StringBuilder
      while depth > 0 do
        if !r.hasNext then throw new XMLStreamException("record is truncated")
        r.next() match
          case XMLStreamConstants.START_ELEMENT =>
            depth += 1
            if depth == 2 then
              fieldName = r.getLocalName
              fieldAttrs = attrs(r)
              text.clear()
          case XMLStreamConstants.END_ELEMENT =>
            if depth == 2 then
              fields += DblpField(
                fieldName,
                Normalize.collapseWhitespace(text.toString),
                fieldAttrs
              )
              fieldName = null
            depth -= 1
          case XMLStreamConstants.CHARACTERS | XMLStreamConstants.CDATA |
              XMLStreamConstants.SPACE =>
            if depth >= 2 then text.append(r.getText)
          case _ => ()
      // Anything after the root other than whitespace or comments is malformed.
      while r.hasNext do
        r.next() match
          case XMLStreamConstants.START_ELEMENT =>
            throw new XMLStreamException("content after the record element")
          case XMLStreamConstants.CHARACTERS if !r.isWhiteSpace =>
            throw new XMLStreamException("text after the record element")
          case _ => ()
      DblpRawRecord(element, rootAttrs, fields.result())
    finally r.close()
