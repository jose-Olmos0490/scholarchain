package scholarchain.storage

import java.io.StringReader
import scholarchain.storage.graph.Csv

class CsvSpec extends StorageSuite:
  private def parse(s: String) = Csv.parse(new StringReader(s)).toVector

  test(
    "RFC 4180 quoting: only fields with a comma, quote, CR or LF are quoted; quotes are doubled"
  ) {
    Csv.field("plain") shouldBe "plain"
    Csv.field("a,b") shouldBe "\"a,b\""
    Csv.field("say \"hi\"") shouldBe "\"say \"\"hi\"\"\""
    Csv.field("line1\nline2") shouldBe "\"line1\nline2\""
    Csv.field("cr\rx") shouldBe "\"cr\rx\""
    Csv.field("") shouldBe ""
    Csv.record(Vector("a", "b,c", "")) shouldBe "a,\"b,c\",\r\n"
  }

  test("round trip: records with commas, quotes, line breaks, empty fields and unicode") {
    val rows = Vector(
      Vector(":ID", "name:String", "n:Int"),
      Vector("x:1", "Sünke \"the\" Mehrgardt, Jr.", "3"),
      Vector("x:2", "two\r\nlines", ""),
      Vector("x:3", "", "7"),
      Vector("x:4", "日本語", "8")
    )
    val text = rows.map(Csv.record).mkString
    parse(text) shouldBe rows
  }

  test("the parser accepts LF record ends and a missing final newline") {
    parse("a,b\nc,d") shouldBe Vector(Vector("a", "b"), Vector("c", "d"))
    parse("a,b\r\n") shouldBe Vector(Vector("a", "b"))
    parse("") shouldBe empty
  }
