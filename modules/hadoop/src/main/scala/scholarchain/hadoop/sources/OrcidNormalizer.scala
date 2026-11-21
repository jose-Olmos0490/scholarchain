package scholarchain.hadoop.sources

import java.io.StringReader
import javax.xml.stream.{XMLInputFactory, XMLStreamConstants}
import scala.collection.immutable.TreeMap
import scala.collection.mutable
import scala.util.control.NonFatal
import scholarchain.core.ids.{ExternalIds, ResearcherNamespace, StableIds}
import scholarchain.core.model.*
import scholarchain.core.schema.SchemaVersions
import scholarchain.hadoop.io.Records
import scholarchain.hadoop.text.Normalize

/** ORCID record XML (message schema v3.0), one record per file.
  *
  * Privacy (manifest `datasets.orcid.privacy_policy`): only four categories are read, by an
  * allowlist of element paths: the iD, the name (given, family, credit name), affiliations
  * (department, role, start/end date, organization name and disambiguated organization ID) and
  * work external identifiers (type, value, relationship). Every other element (emails,
  * biographies, addresses, other names, keywords, URLs, person external identifiers, work titles,
  * sources, history) is skipped without being stored anywhere.
  *
  * Table (dataset `orcid`): `researcher_node`, keyed by `researcher:orcid:...` (an ORCID record is
  * the registry's own assertion of the iD, so the iD is validated by its checksum).
  */
object OrcidNormalizer:

  private val AffiliationSections = Set(
    "employments",
    "educations",
    "distinctions",
    "invited-positions",
    "memberships",
    "qualifications",
    "services"
  )

  private def factory(): XMLInputFactory =
    val f = XMLInputFactory.newInstance()
    f.setProperty(XMLInputFactory.SUPPORT_DTD, false)
    f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true)
    f.setProperty(XMLInputFactory.IS_COALESCING, true)
    f

  /** Allowlisted fields of one record, in document order. */
  final case class OrcidFields(
      orcidPath: Option[String],
      givenNames: Option[String],
      familyName: Option[String],
      creditName: Option[String],
      affiliations: Vector[Map[String, String]],
      workIds: Vector[Map[String, String]]
  )

  def extract(xml: String): OrcidFields =
    val r = factory().createXMLStreamReader(new StringReader(xml))
    val stack = mutable.ArrayBuffer.empty[String]
    val text = new StringBuilder
    var orcidPath: Option[String] = None
    var givenNames: Option[String] = None
    var family: Option[String] = None
    var credit: Option[String] = None
    val affiliations = Vector.newBuilder[Map[String, String]]
    val workIds = Vector.newBuilder[Map[String, String]]
    var current: mutable.TreeMap[String, String] = null
    var currentWorkId: mutable.TreeMap[String, String] = null
    def under(name: String): Boolean = stack.contains(name)
    try
      while r.hasNext do
        r.next() match
          case XMLStreamConstants.START_ELEMENT =>
            val name = r.getLocalName
            stack += name
            text.clear()
            if name.endsWith("-summary") && stack.size >= 3 &&
              AffiliationSections.contains(stack(stack.size - 3))
            then current = mutable.TreeMap("type" -> name.stripSuffix("-summary"))
            if name == "external-id" && under("works") && under("external-ids") then
              currentWorkId = mutable.TreeMap.empty
          case XMLStreamConstants.CHARACTERS | XMLStreamConstants.CDATA =>
            text.append(r.getText)
          case XMLStreamConstants.END_ELEMENT =>
            val name = r.getLocalName
            val value = Normalize.collapseWhitespace(text.toString)
            val parent = if stack.size >= 2 then stack(stack.size - 2) else ""
            // A value made only of whitespace, controls or Cf characters is absent.
            if !Normalize.isBlank(value) then
              if name == "path" && parent == "orcid-identifier" then orcidPath = Some(value)
              else if parent == "name" && under("person") && current == null then
                name match
                  case "given-names" => givenNames = Some(value)
                  case "family-name" => family = Some(value)
                  case "credit-name" => credit = Some(value)
                  case _ => ()
              else if current != null then
                name match
                  case "department-name" => current("department") = value
                  case "role-title" => current("role") = value
                  case "name" if parent == "organization" => current("organization") = value
                  case "disambiguated-organization-identifier" => current("disambiguatedId") = value
                  case "disambiguation-source" => current("disambiguationSource") = value
                  case "year" | "month" | "day" if parent == "start-date" || parent == "end-date" =>
                    val k = if parent == "start-date" then "startDate" else "endDate"
                    current(k) = current.get(k).map(_ + "-" + value).getOrElse(value)
                  case _ => ()
              else if currentWorkId != null then
                name match
                  case "external-id-type" => currentWorkId("type") = value
                  case "external-id-value" => currentWorkId("value") = value
                  case "external-id-relationship" => currentWorkId("relationship") = value
                  case _ => ()
            if current != null && name.endsWith("-summary") then
              affiliations += current.toMap
              current = null
            if currentWorkId != null && name == "external-id" then
              workIds += currentWorkId.toMap
              currentWorkId = null
            stack.remove(stack.size - 1)
            text.clear()
          case _ => ()
    finally r.close()
    OrcidFields(orcidPath, givenNames, family, credit, affiliations.result(), workIds.result())

  def record(xml: String, ctx: SourceContext): Vector[Emitted] =
    val out = new EmitBuffer
    try
      val f = extract(xml)
      f.orcidPath.flatMap(p => ExternalIds.normalizeOrcid(p).toOption) match
        case None => out.reject("MISSING_KEY", ctx.inputLocation, xml)
        case Some(orcid) =>
          val nodeId =
            StableIds.researcherIdFromExternal(ResearcherNamespace.Orcid, orcid).toOption.get
          val prov = Records.provenance(SourceType.ORCID, orcid, ctx.snapshot, ctx.runId)
          val props = TreeMap.newBuilder[String, String]
          props += "orcid" -> orcid
          f.givenNames.foreach(v => props += "givenNames" -> v)
          f.familyName.foreach(v => props += "familyName" -> v)
          f.creditName.foreach(v => props += "creditName" -> v)
          f.affiliations.zipWithIndex.foreach((m, j) =>
            m.foreach((k, v) => props += s"affiliation.$j.$k" -> v)
          )
          f.workIds.zipWithIndex.foreach { (m, j) =>
            m.foreach((k, v) => props += s"workExternalId.$j.$k" -> v)
            if m.get("type").contains("doi") then
              m.get("value")
                .flatMap(d => ExternalIds.normalizeDoi(d).toOption)
                .foreach(d => props += s"workExternalId.$j.normalizedDoi" -> d)
          }
          val label = f.creditName
            .orElse(Seq(f.givenNames, f.familyName).flatten.reduceOption(_ + " " + _))
            .getOrElse(orcid)
          out.row(
            "researcher_node",
            nodeId,
            GraphNode(
              SchemaVersions.GraphNode,
              nodeId,
              NodeType.Researcher,
              label,
              props.result(),
              prov
            )
          )
    catch
      case NonFatal(e) =>
        out.reject("MALFORMED_XML", ctx.inputLocation, s"${e.getMessage} | $xml")
    out.result()
