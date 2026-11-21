package scholarchain.hadoop.dblp

import scala.collection.immutable.TreeMap
import scholarchain.core.ids.{ExternalIds, StableIds, VenueNamespace, WorkNamespace}
import scholarchain.core.model.{AuthorMention, SourceType, WorkRecord}
import scholarchain.core.schema.SchemaVersions
import scholarchain.hadoop.io.Records
import scholarchain.hadoop.text.Normalize
import upickle.default.ReadWriter

/** A normalized DBLP work plus the source fields that `WorkRecord` has no slot for.
  *
  * Internal intermediate format of `dblp/work_extracted` (J01 output, J01C input). `properties`
  * keeps every child field except author, title and year, as `<field>.<i>` with attributes as
  * `<field>.<i>.<attr>` (for example `ee.0`, `ee.0.type`, `editor.1.orcid`), and the record
  * attributes as `@<attr>`.
  */
final case class DblpWork(
    work: WorkRecord,
    venueKey: Option[String],
    venueName: Option[String],
    properties: Map[String, String]
) derives ReadWriter

/** Subset rule and run identity needed to normalize DBLP records. */
final case class DblpContext(
    recordTypes: Set[String],
    yearFrom: Int,
    yearTo: Int,
    snapshot: String,
    runId: String
)

/** Outcome of normalizing one parsed record. */
enum DblpOutcome:
  case Accepted(work: DblpWork)
  case OutOfSubset(reason: String)
  case Rejected(reasonCode: String, detail: String)

object DblpNormalizer:
  private val YearPattern = "[0-9]{4}".r

  def normalize(raw: DblpRawRecord, ctx: DblpContext): DblpOutcome =
    if !ctx.recordTypes.contains(raw.element) then
      DblpOutcome.OutOfSubset(s"record type ${raw.element}")
    else
      raw.key match
        case None => DblpOutcome.Rejected("MISSING_KEY", "record has no key attribute")
        case Some(rawKey) =>
          ExternalIds.normalizeDblpKey(rawKey) match
            case Left(reason) => DblpOutcome.Rejected("INVALID_KEY", reason)
            case Right(key) =>
              val yearText = raw.first("year")
              val year = yearText.filter(YearPattern.matches).map(_.toInt)
              year match
                case None =>
                  DblpOutcome.OutOfSubset(s"no integer year (${yearText.getOrElse("absent")})")
                case Some(y) if y < ctx.yearFrom || y > ctx.yearTo =>
                  DblpOutcome.OutOfSubset(s"year $y outside ${ctx.yearFrom}-${ctx.yearTo}")
                case Some(y) => build(raw, key, y, ctx)

  private def build(raw: DblpRawRecord, key: String, year: Int, ctx: DblpContext): DblpOutcome =
    val title = raw.first("title")
    // Blankness ignores whitespace, no-break spaces, controls and Cf characters
    // (Normalize.isBlank): an invisible title or author name counts as missing.
    if title.forall(Normalize.isBlank) then
      DblpOutcome.Rejected("MISSING_TITLE", s"$key has no title")
    else
      val workId = StableIds.workId(WorkNamespace.Dblp, key).toOption.get
      val prov = Records.provenance(SourceType.DBLP, key, ctx.snapshot, ctx.runId)
      val authorFields = raw.all("author")
      val blankAuthor = authorFields.indexWhere(f => Normalize.isBlank(f.text))
      if blankAuthor >= 0 then
        DblpOutcome.Rejected("MISSING_AUTHOR_NAME", s"$key author ${blankAuthor + 1} is blank")
      else
        val mentions = authorFields.zipWithIndex.map { (f, i) =>
          val position = i + 1
          val folded = Normalize.personName(f.text)
          AuthorMention(
            schemaVersion = SchemaVersions.AuthorMention,
            mentionId = StableIds.mentionId(workId, position).toOption.get,
            workId = workId,
            position = position,
            rawName = f.text,
            normalizedName =
              if folded.nonEmpty then folded else Normalize.stripInvisible(f.text),
            sourceAuthorKey = Some(f.text),
            orcid = f.attributes.get("orcid").flatMap(o => ExternalIds.normalizeOrcid(o).toOption),
            provenance = prov
          )
        }
        val ees = raw.all("ee").map(_.text)
        val doi = ees.iterator.flatMap(e => ExternalIds.normalizeDoi(e).toOption).nextOption()
        val arxiv = ees.iterator
          .filter(_.toLowerCase(java.util.Locale.ROOT).contains("arxiv.org/abs/"))
          .flatMap { e =>
            val i = e.toLowerCase(java.util.Locale.ROOT).indexOf("arxiv.org/abs/")
            ExternalIds.normalizeArxiv(e.substring(i + "arxiv.org/abs/".length)).toOption
          }
          .nextOption()
        val venueKey = key.split('/').toVector match
          case prefix +: stream +: _ +: _ =>
            ExternalIds.normalizeDblpVenueKey(s"$prefix/$stream").toOption
          case _ => None
        val venueId = venueKey.flatMap(k => StableIds.venueId(VenueNamespace.Dblp, k).toOption)
        val venueName = raw.first("journal").orElse(raw.first("booktitle"))
        val normalizedTitle = Normalize.title(title.get)
        val work = WorkRecord(
          schemaVersion = SchemaVersions.WorkRecord,
          workId = workId,
          source = SourceType.DBLP,
          sourceRecordId = key,
          recordType = raw.element,
          title = title.get,
          normalizedTitle =
            if normalizedTitle.nonEmpty then normalizedTitle
            else Normalize.fallbackLower(title.get),
          year = Some(year),
          venueId = venueId,
          doi = doi,
          arxivId = arxiv,
          authors = mentions,
          abstractTextRef = None,
          provenance = prov
        )
        DblpOutcome.Accepted(DblpWork(work, venueKey, venueName, properties(raw)))

  /** Every field except author, title and year, plus record attributes; see [[DblpWork]].
    *
    * The one blankness rule (`Normalize.isBlank`) applies: a blank text or attribute value is not
    * stored. The index `<i>` stays the field's position among same-named fields in the source,
    * so `ee.1.type` still belongs to the second `<ee>` even when the first one is blank.
    */
  def properties(raw: DblpRawRecord): Map[String, String] =
    val b = TreeMap.newBuilder[String, String]
    def put(k: String, v: String): Unit = if !Normalize.isBlank(v) then b += k -> v
    raw.attributes.foreach((k, v) => put(s"@$k", v))
    raw.fields
      .filterNot(f => f.name == "author" || f.name == "title" || f.name == "year")
      .groupBy(_.name)
      .foreach { (name, fs) =>
        fs.zipWithIndex.foreach { (f, i) =>
          put(s"$name.$i", f.text)
          f.attributes.foreach((a, v) => put(s"$name.$i.$a", v))
        }
      }
    raw.all("author").zipWithIndex.foreach { (f, i) =>
      f.attributes.foreach((a, v) => put(s"author.$i.$a", v))
    }
    b.result()
