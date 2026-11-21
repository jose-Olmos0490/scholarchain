package scholarchain.hadoop.dblp

import java.io.{ByteArrayInputStream, StringReader}
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.ext.DeclHandler
import org.xml.sax.{EntityResolver, InputSource, SAXException}

/** The general-entity table of a DTD, read by the JDK XML parser from the DTD bytes.
  *
  * Nothing is hardcoded: every name and replacement text comes from `<!ENTITY ...>` declarations
  * of the configured DTD (shipped through the distributed cache). Network and external-entity
  * resolution are off: the only external resource the parser may open is the DTD itself, served
  * from memory by an `EntityResolver`; any other system ID fails the load.
  */
final class DtdEntities private (val table: Map[String, String]):
  def contains(name: String): Boolean = table.contains(name)
  def replacement(name: String): Option[String] = table.get(name)
  def size: Int = table.size

object DtdEntities:
  /** XML's predefined entities; they are never looked up in the DTD. */
  val Predefined: Set[String] = Set("amp", "lt", "gt", "quot", "apos")

  private val MarkerSystemId = "urn:scholarchain:dblp-dtd"

  /** Parses the DTD and returns its general (non-parameter) entities. Throws on a malformed DTD
    * or on any attempt to load another external resource.
    */
  def load(dtd: Array[Byte]): DtdEntities =
    val factory = SAXParserFactory.newInstance()
    factory.setNamespaceAware(false)
    factory.setValidating(false)
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
    val parser = factory.newSAXParser()
    // No URL protocol is allowed; the DTD arrives through the resolver below.
    parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
    parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    val reader = parser.getXMLReader
    val entities = scala.collection.mutable.TreeMap.empty[String, String]
    reader.setProperty(
      "http://xml.org/sax/properties/declaration-handler",
      new DeclHandler:
        def elementDecl(name: String, model: String): Unit = ()
        def attributeDecl(e: String, a: String, t: String, mode: String, v: String): Unit = ()
        def internalEntityDecl(name: String, value: String): Unit =
          if !name.startsWith("%") && !entities.contains(name) then entities(name) = value
        def externalEntityDecl(name: String, publicId: String, systemId: String): Unit = ()
    )
    reader.setEntityResolver(new EntityResolver:
      def resolveEntity(publicId: String, systemId: String): InputSource =
        if systemId != null && systemId.endsWith(MarkerSystemId) || systemId == MarkerSystemId
        then
          val src = new InputSource(new ByteArrayInputStream(dtd))
          src.setSystemId(MarkerSystemId)
          src
        else throw new SAXException(s"external resource '$systemId' is not allowed"))
    val doc = s"""<?xml version="1.0"?><!DOCTYPE dblp SYSTEM "$MarkerSystemId"><dblp/>"""
    reader.parse(new InputSource(new StringReader(doc)))
    new DtdEntities(entities.toMap)

  /** XML 1.0 (5th ed.) `NameStartChar` production, §2.3. */
  private val NameStartChar =
    ":A-Z_a-z\\x{C0}-\\x{D6}\\x{D8}-\\x{F6}\\x{F8}-\\x{2FF}\\x{370}-\\x{37D}\\x{37F}-\\x{1FFF}" +
      "\\x{200C}-\\x{200D}\\x{2070}-\\x{218F}\\x{2C00}-\\x{2FEF}\\x{3001}-\\x{D7FF}" +
      "\\x{F900}-\\x{FDCF}\\x{FDF0}-\\x{FFFD}\\x{10000}-\\x{EFFFF}"

  /** XML 1.0 `NameChar` = `NameStartChar` plus these. */
  private val NameChar =
    NameStartChar + "\\-.0-9\\x{B7}\\x{300}-\\x{36F}\\x{203F}-\\x{2040}"

  /** An entity reference `&Name;` with the full XML `Name` production (non-ASCII names included),
    * so an undeclared non-ASCII entity is reported as unknown instead of reaching the XML parser.
    */
  private val EntityRef = s"&([$NameStartChar][$NameChar]*);".r

  /** Replaces every non-predefined entity reference in `xml` by numeric character references of
    * its DTD replacement text. Returns `Left(name)` for the first entity the DTD does not declare.
    * Character references (`&#...;`) and predefined entities are left to the XML parser.
    */
  def substitute(xml: String, entities: DtdEntities): Either[String, String] =
    if xml.indexOf('&') < 0 then Right(xml)
    else
      val sb = new java.lang.StringBuilder(xml.length + 32)
      var last = 0
      var unknown: Option[String] = None
      val it = EntityRef.findAllMatchIn(xml)
      while unknown.isEmpty && it.hasNext do
        val m = it.next()
        val name = m.group(1)
        if !Predefined.contains(name) then
          entities.replacement(name) match
            case None => unknown = Some(name)
            case Some(text) =>
              sb.append(xml, last, m.start)
              var i = 0
              while i < text.length do
                val cp = text.codePointAt(i)
                sb.append("&#").append(cp).append(';')
                i += Character.charCount(cp)
              last = m.end
      unknown match
        case Some(n) => Left(n)
        case None =>
          sb.append(xml, last, xml.length)
          Right(sb.toString)
