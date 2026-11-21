package scholarchain.hadoop

import java.io.File
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import scholarchain.core.ids.ExternalIds
import scholarchain.core.json.WireCodec
import scholarchain.core.model.*
import scholarchain.hadoop.blocking.{BlockingKeys, MatchFeatures}
import scholarchain.hadoop.io.Layout
import scholarchain.hadoop.sources.*
import scholarchain.hadoop.text.Normalize

class KeysAndSourcesSpec extends AnyFunSuite:
  import HadoopTestSupport.*

  private val ctx = SourceContext("2026-10-14", "run:" + "c" * 64, "openalex/x.jsonl")

  private def rowsOf(e: Vector[Emitted], table: String): Vector[String] =
    e.collect { case Emitted.Row(t, _, j) if t == table => j }

  test("DOI forms normalize to one value") {
    val forms = Seq(
      "10.1145/ABC.Def",
      "https://doi.org/10.1145/abc.def",
      "http://dx.doi.org/10.1145/ABC.DEF",
      "doi:10.1145/abc.DEF"
    )
    assert(forms.map(ExternalIds.normalizeDoi(_).toOption).distinct == Seq(Some("10.1145/abc.def")))
    assert(ExternalIds.normalizeDoi("https://ieeexplore.ieee.org/document/1").isLeft)
  }

  test("ORCID checksum: valid iD accepted (also with X check digit), wrong check digit rejected") {
    assert(
      ExternalIds.normalizeOrcid("https://orcid.org/0000-0002-1825-0097") == Right(
        "0000-0002-1825-0097"
      )
    )
    assert(ExternalIds.normalizeOrcid("0000-0003-4790-136x") == Right("0000-0003-4790-136X"))
    assert(ExternalIds.normalizeOrcid("0000-0002-1825-0098").isLeft)
  }

  test("arXiv IDs: new and old style, version suffix removed") {
    assert(ExternalIds.normalizeArxiv("https://arxiv.org/abs/2101.00001v3") == Right("2101.00001"))
    assert(ExternalIds.normalizeArxiv("cs.DC/0101001") == Right("cs.DC/0101001"))
    assert(ExternalIds.normalizeArxiv("not-an-id").isLeft)
  }

  test("name folding strips homonym suffixes and diacritics deterministically") {
    assert(Normalize.personName("Manish Singh 0001") == "manish singh")
    assert(Normalize.personName("Frédéric  MALLET") == "frederic mallet")
    assert(Normalize.surnameAndInitial("Ümit Y. Ogras") == Some(("ogras", "u")))
    assert(Normalize.surnameAndInitial("...") == None)
  }

  test(
    "invisible format characters (soft hyphen, zero-width space, ZWJ, BOM) never split a token"
  ) {
    assert(Normalize.surnameAndInitial("John Smi\u00ADth") == Some(("smith", "j")))
    assert(Normalize.surnameAndInitial("John Smi\u200Bth") == Some(("smith", "j")))
    assert(Normalize.surnameAndInitial("\uFEFFJohn Smi\u200Dth") == Some(("smith", "j")))
    assert(Normalize.title("Multi\u00ADthreaded  sys\u200Btems") == "multithreaded systems")
    // Visible separators still separate.
    assert(Normalize.surnameAndInitial("John Smi-th") == Some(("th", "j")))
  }

  private def member(name: String, coauthors: Vector[String], year: Option[Int]) =
    BlockingKeys
      .members(
        WorkRecord(
          "work-v1",
          "work:dblp:" + "0" * 64,
          SourceType.DBLP,
          "conf/x/y",
          "article",
          "Deep Learning for Graphs",
          "deep learning for graphs",
          year,
          None,
          None,
          None,
          (name +: coauthors).zipWithIndex.map { (n, i) =>
            AuthorMention("author-mention-v1", s"mention:${i}", "w", i + 1, n, n, None, None, null)
          },
          None,
          null
        )
      )

  test("blocking keys: researcher surname+initial, work title prefix+year bucket, and fallbacks") {
    val ms = member("Li Wei", Vector("Bo Chen", "Ann Zhu"), Some(2003))
    val work = ms.head
    val li = ms(1)
    assert(BlockingKeys.primary(li).contains("r|wei|l"))
    assert(BlockingKeys.fallback(li).contains("r|wei|l|co:chen"))
    assert(BlockingKeys.primary(work).contains("w|deep learning for|2000-2004"))
    assert(BlockingKeys.fallback(work).contains("w|deep learning for|2000-2004|fa:wei"))
    val solo = member("Li Wei", Vector.empty, Some(2003))(1)
    assert(BlockingKeys.fallback(solo).contains("r|wei|l|y:2000-2004"))
    val noYear = member("Li Wei", Vector.empty, None).head
    assert(BlockingKeys.primary(noYear).isEmpty, "a work without a year has no key (MISSING_KEY)")
  }

  test("match features are deterministic, finite and symmetric; same-work mentions are skipped") {
    val a = member("Li Wei", Vector("Bo Chen"), Some(2003))
    val run = "run:" + "d" * 64
    val f1 = MatchFeatures.features(a(1), a(2))
    assert(f1 == MatchFeatures.features(a(2), a(1)))
    assert(f1.values.forall(v => !v.isNaN && !v.isInfinite))
    assert(f1.keys.toVector == f1.keys.toVector.sorted)
    assert(MatchFeatures.candidate("k", a(1), a(2), "s", run).isEmpty)
  }

  test("OpenAlex works keep every source ID, affiliations and topics") {
    val line = Files
      .readAllLines(
        new File(samplesDir, "openalex/openalex-works-snapshot-2026-10-14-excerpt.jsonl").toPath
      )
      .get(0)
    val out = OpenAlexNormalizer.works(line, ctx)
    assert(!out.exists(_.isInstanceOf[Emitted.Reject]))
    val w = WireCodec.decode[WorkRecord](rowsOf(out, "work").head).toOption.get
    assert(w.sourceRecordId == "W4417174174" && w.doi.contains("10.2117/psysoc.1964.121"))
    assert(w.authors.size == 2)
    assert(rowsOf(out, "institution").nonEmpty)
    assert(rowsOf(out, "topic").size == 3)
    val edges = rowsOf(out, "graph_edge").map(WireCodec.decode[GraphEdge](_).toOption.get)
    assert(edges.count(_.edgeType == EdgeType.HAS_TOPIC) == 3)
    assert(edges.count(_.edgeType == EdgeType.AFFILIATED_WITH) == 2)
    val node = WireCodec.decode[GraphNode](rowsOf(out, "work_node").head).toOption.get
    assert(node.properties.get("ids.openalex").contains("https://openalex.org/W4417174174"))
    val mnode = WireCodec.decode[GraphNode](rowsOf(out, "mention_node").head).toOption.get
    assert(mnode.properties.get("rawAffiliation.0").contains("University of Genova"))
  }

  test("OpenAlex work without a title is rejected, malformed JSON is rejected") {
    assert(
      OpenAlexNormalizer.works("""{"id":"https://openalex.org/W1","title":null}""", ctx).collect {
        case Emitted.Reject(c, _, _) => c
      } == Vector("MISSING_TITLE")
    )
    assert(OpenAlexNormalizer.works("{not json", ctx).collect { case Emitted.Reject(c, _, _) =>
      c
    } == Vector("MALFORMED_JSON"))
  }

  test("Crossref items keep the DOI work ID, ORCIDs and name-only affiliations") {
    val item =
      """{"DOI":"10.1145/X.Y","title":["T"],"issued":{"date-parts":[[2021,3]]},"type":"journal-article",
        |"ISSN":["0738-100X"],"container-title":["J"],"abstract":"<p>secret</p>",
        |"author":[{"given":"Sünke","family":"M","ORCID":"https://orcid.org/0000-0002-1825-0097","authenticated-orcid":true,"affiliation":[{"name":"Uni X"}]},{"name":"Consortium"}]}""".stripMargin
        .replace("\n", "")
    val out = CrossrefNormalizer.works(item, ctx.copy(inputLocation = "crossref/c.jsonl"))
    val w = WireCodec.decode[WorkRecord](rowsOf(out, "work").head).toOption.get
    assert(w.doi.contains("10.1145/x.y") && w.year.contains(2021))
    assert(w.authors.map(_.orcid) == Vector(Some("0000-0002-1825-0097"), None))
    assert(w.abstractTextRef.contains("crossref/c.jsonl#10.1145/x.y/abstract"))
    assert(
      !out.exists { case Emitted.Row(_, _, j) => j.contains("secret"); case _ => false },
      "abstract text is never copied"
    )
    assert(rowsOf(out, "mention_node").exists(_.contains("Uni X")))
    assert(rowsOf(out, "venue").size == 1)
  }

  test("ORCID: only the allowlisted categories survive (no email, biography, address, titles)") {
    val xml =
      """<?xml version="1.0" encoding="UTF-8"?>
        |<record:record xmlns:record="r" xmlns:common="c" xmlns:person="p" xmlns:personal-details="pd" xmlns:email="e" xmlns:activities="a" xmlns:employment="em" xmlns:work="w">
        |<common:orcid-identifier><common:path>0000-0002-1825-0097</common:path></common:orcid-identifier>
        |<person:person><person:name><personal-details:given-names>Josiah</personal-details:given-names><personal-details:family-name>Carberry</personal-details:family-name></person:name>
        |<person:biography><personal-details:content>SECRET-BIO</personal-details:content></person:biography>
        |<email:emails><email:email><email:email>secret@example.org</email:email></email:email></email:emails>
        |<person:addresses><person:address><person:country>XX-SECRET</person:country></person:address></person:addresses></person:person>
        |<activities:activities-summary><activities:employments><activities:affiliation-group><employment:employment-summary>
        |<common:department-name>Psychoceramics</common:department-name><common:organization><common:name>Wesleyan</common:name>
        |<common:address><common:city>SECRET-CITY</common:city></common:address></common:organization></employment:employment-summary></activities:affiliation-group></activities:employments>
        |<activities:works><activities:group><common:external-ids><common:external-id><common:external-id-type>doi</common:external-id-type><common:external-id-value>10.5555/12345678</common:external-id-value></common:external-id></common:external-ids>
        |<work:work-summary><work:title><common:title>SECRET-TITLE</common:title></work:title></work:work-summary></activities:group></activities:works>
        |</activities:activities-summary></record:record>""".stripMargin
    val out = OrcidNormalizer.record(xml, ctx.copy(inputLocation = "orcid/r.xml"))
    val rows = rowsOf(out, "researcher_node")
    assert(rows.size == 1)
    assert(!rows.head.contains("SECRET") && !rows.head.contains("secret@"))
    val n = WireCodec.decode[GraphNode](rows.head).toOption.get
    assert(n.properties("orcid") == "0000-0002-1825-0097")
    assert(n.properties("affiliation.0.organization") == "Wesleyan")
    assert(n.properties("affiliation.0.department") == "Psychoceramics")
    assert(n.properties("workExternalId.0.normalizedDoi") == "10.5555/12345678")
    assert(n.label == "Josiah Carberry")
  }

  test("the committed ORCID sample normalizes to one researcher node") {
    val xml = new String(
      Files.readAllBytes(new File(samplesDir, "orcid/orcid-0000-0002-1825-0097-record.xml").toPath),
      "UTF-8"
    )
    val rows = rowsOf(OrcidNormalizer.record(xml, ctx), "researcher_node")
    val n = WireCodec.decode[GraphNode](rows.head).toOption.get
    assert(n.properties.keys.count(_.endsWith(".organization")) == 2)
    assert(
      n.properties.keys.count(k => k.startsWith("workExternalId.") && k.endsWith(".value")) == 7
    )
  }

  test("layout: table paths carry snapshot and run digest and parse back") {
    val d = Layout.tableDir("dblp", "work", "2026-11-01", "run:" + "e" * 64)
    assert(d == s"dblp/work/snapshot=2026-11-01/run_id=${"e" * 64}")
    assert(
      Layout
        .parseTableRef(s"file:/x/out/$d/part-r-00000.gz")
        .contains(Layout.TableRef("dblp", "work", "2026-11-01", "e" * 64))
    )
    assertThrows[IllegalArgumentException](
      Layout.tableDir("dblp", "work", "bad/label", "run:" + "e" * 64)
    )
  }
