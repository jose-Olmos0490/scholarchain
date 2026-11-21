package scholarchain.er.evaluation

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.util.control.NonFatal
import scholarchain.er.review.ReviewQueue

/** Human gold label of one review-queue pair. Only a human assigns it; this code only reads it. */
enum GoldLabel:
  case SAME, DIFFERENT, UNSURE

/** One labeled row of the human gold set. `notes` is human free text, kept as data. */
final case class GoldRow(pairId: String, kind: String, label: GoldLabel, notes: String)

/** Read-only parser for the human gold-set CSV (same columns as `review-queue-v1.csv`, with
  * `human_label` filled in).
  *
  * This package is the only code that reads a gold set, and it reads it only from the path given
  * on the [[EvaluateGoldSet]] command line. Matching, feature, threshold and policy code never
  * references it (asserted by `GoldSetLeakageSpec`).
  *
  * Strict vocabulary: `human_label` must be exactly `SAME`, `DIFFERENT` or `UNSURE`. Any other
  * value (blank, lower case, a typo) is an error, never a guess. `kind` must be `researcher` or
  * `work`. `pair_id` must be non-blank and unique.
  */
object GoldSet:
  val Kinds: Set[String] = Set("researcher", "work")
  private val Required = Vector("pair_id", "kind", "human_label")

  def parse(text: String): Either[String, Vector[GoldRow]] =
    val rows = ReviewQueue.parseCsv(text.stripPrefix("﻿"))
    if rows.isEmpty then Left("gold set is empty: no header row")
    else
      val header = rows.head
      val missing = Required.filterNot(header.contains)
      if missing.nonEmpty then Left(s"gold set header lacks column(s): ${missing.mkString(", ")}")
      else
        val ix = header.zipWithIndex.toMap
        val notesIx = ix.get("notes")
        val parsed = rows.tail.zipWithIndex.map { (r, i) =>
          val line = i + 2
          def col(name: String): String = r.lift(ix(name)).getOrElse("")
          if r.size != header.size then
            Left(s"gold set line $line has ${r.size} fields, header has ${header.size}")
          else
            val pairId = col("pair_id")
            val kind = col("kind")
            val raw = col("human_label")
            if pairId.isBlank then Left(s"gold set line $line: blank pair_id")
            else if !Kinds.contains(kind) then
              Left(s"gold set line $line ($pairId): kind '$kind' is not researcher or work")
            else
              GoldLabel.values.find(_.toString == raw) match
                case None =>
                  Left(
                    s"gold set line $line ($pairId): human_label '$raw' is not one of " +
                      "SAME, DIFFERENT, UNSURE"
                  )
                case Some(l) =>
                  Right(GoldRow(pairId, kind, l, notesIx.flatMap(r.lift).getOrElse("")))
        }
        parsed.collectFirst { case Left(e) => e } match
          case Some(e) => Left(e)
          case None =>
            val good = parsed.collect { case Right(g) => g }
            val dups = good.groupBy(_.pairId).collect { case (k, v) if v.size > 1 => k }
            if dups.nonEmpty then
              Left(s"gold set repeats pair_id(s): ${dups.toVector.sorted.mkString(", ")}")
            else if good.isEmpty then Left("gold set has no labeled rows")
            else Right(good.sortBy(_.pairId))

  /** Reads the file (never writes it) and returns its rows and `sha256:<hex>` of its bytes. */
  def load(path: Path): Either[String, (Vector[GoldRow], String)] =
    try
      if !Files.isRegularFile(path) then Left(s"gold set not found: $path")
      else
        val bytes = Files.readAllBytes(path)
        val sha = Files256.ofBytes(bytes)
        parse(new String(bytes, StandardCharsets.UTF_8)).map(_ -> sha)
    catch case NonFatal(e) => Left(s"cannot read gold set $path: ${e.getMessage}")

/** `sha256:<hex>` of raw bytes. */
object Files256:
  def ofBytes(bytes: Array[Byte]): String =
    val d = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
    "sha256:" + d.map(b => f"${b & 0xff}%02x").mkString

  def ofFile(path: Path): String = ofBytes(Files.readAllBytes(path))
