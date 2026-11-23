package scholarchain.services.query

import scala.util.control.NonFatal
import scholarchain.core.ids.{IdKind, StableId}
import scholarchain.rag.agents.{OperationAllowlist, StructuredFields}
import scholarchain.services.config.{HttpSettings, RuntimeLimits}
import scholarchain.services.error.ApiError
import scholarchain.services.security.Sha256Hex

enum QueryMode:
  case Sync, Async

/** A validated `POST /v1/query` body. */
final case class QueryRequest(
    mode: QueryMode,
    question: Option[String],
    fields: StructuredFields
):
  /** The canonical form of the request: fixed key order, so equal requests have equal text. */
  def canonical: String =
    val f = fields
    ujson
      .Obj(
        "mode" -> mode.toString,
        "question" -> question.fold[ujson.Value](ujson.Null)(ujson.Str(_)),
        "operations" -> ujson.Arr.from(f.operations.map(ujson.Str(_))),
        "researcherNames" -> ujson.Arr.from(f.researcherNames.map(ujson.Str(_))),
        "researcherIds" -> ujson.Arr.from(f.researcherIds.map(ujson.Str(_))),
        "windowStartYear" -> opt(f.windowStartYear),
        "windowEndYear" -> opt(f.windowEndYear),
        "topics" -> ujson.Arr.from(f.topics.map(ujson.Str(_))),
        "venues" -> ujson.Arr.from(f.venues.map(ujson.Str(_))),
        "institutions" -> ujson.Arr.from(f.institutions.map(ujson.Str(_))),
        "rankingMetric" -> f.rankingMetric.fold[ujson.Value](ujson.Null)(ujson.Str(_)),
        "graphLimit" -> opt(f.graphLimit),
        "vectorLimit" -> opt(f.vectorLimit),
        "maxPathDepth" -> opt(f.maxPathDepth)
      )
      .render()

  def fingerprint: String = s"sha256:${Sha256Hex.of(canonical)}"

  private def opt(i: Option[Int]): ujson.Value = i.fold[ujson.Value](ujson.Null)(ujson.Num(_))

object QueryRequest:
  private val Fields = Set(
    "mode",
    "question",
    "operations",
    "researcherNames",
    "researcherIds",
    "windowStartYear",
    "windowEndYear",
    "topics",
    "venues",
    "institutions",
    "rankingMetric",
    "graphLimit",
    "vectorLimit",
    "maxPathDepth"
  )

  /** Longest accepted item of a list field, in characters. */
  private val MaxItemChars = 200

  def parse(
      body: Array[Byte],
      http: HttpSettings,
      limits: RuntimeLimits
  ): Either[ApiError, QueryRequest] =
    if body.isEmpty then Left(ApiError.badRequest("EMPTY_BODY", "the request body is empty"))
    else
      readJson(body).flatMap {
        case obj: ujson.Obj => validate(obj, http, limits)
        case _ =>
          Left(ApiError.badRequest("INVALID_BODY", "the request body must be a JSON object"))
      }

  private def readJson(body: Array[Byte]): Either[ApiError, ujson.Value] =
    try Right(ujson.read(new String(body, java.nio.charset.StandardCharsets.UTF_8)))
    catch
      case NonFatal(_) | _: StackOverflowError =>
        Left(ApiError.badRequest("INVALID_JSON", "the request body is not valid JSON"))

  private def bad(field: String, what: String): ApiError =
    ApiError.badRequest("INVALID_FIELD", s"field '$field' $what")

  private def str(o: ujson.Obj, k: String, max: Int): Either[ApiError, Option[String]] =
    o.value.get(k) match
      case None | Some(ujson.Null) => Right(None)
      case Some(ujson.Str(s)) if s.length <= max => Right(Some(s))
      case Some(_: ujson.Str) => Left(bad(k, s"is longer than $max characters"))
      case Some(_) => Left(bad(k, "must be a string"))

  private def int(o: ujson.Obj, k: String): Either[ApiError, Option[Int]] =
    o.value.get(k) match
      case None | Some(ujson.Null) => Right(None)
      case Some(ujson.Num(d)) if d == d.floor && d.abs < 1e9 => Right(Some(d.toInt))
      case Some(_) => Left(bad(k, "must be an integer"))

  private def strings(o: ujson.Obj, k: String, maxItems: Int): Either[ApiError, Vector[String]] =
    o.value.get(k) match
      case None | Some(ujson.Null) => Right(Vector.empty)
      case Some(ujson.Arr(items)) =>
        if items.size > maxItems then Left(bad(k, s"has more than $maxItems items"))
        else
          items.toVector.foldLeft[Either[ApiError, Vector[String]]](Right(Vector.empty)) {
            (acc, v) =>
              acc.flatMap(done =>
                v match
                  case ujson.Str(s) if s.nonEmpty && s.length <= MaxItemChars => Right(done :+ s)
                  case _ =>
                    Left(bad(k, s"must hold non-empty strings of at most $MaxItemChars characters"))
              )
          }
      case Some(_) => Left(bad(k, "must be an array of strings"))

  private def validate(
      o: ujson.Obj,
      http: HttpSettings,
      limits: RuntimeLimits
  ): Either[ApiError, QueryRequest] =
    for
      _ <-
        if o.value.keySet.subsetOf(Fields) then Right(())
        else
          Left(
            ApiError
              .badRequest("UNKNOWN_FIELD", "the request has a field that is not part of the API")
          )
      mode <- str(o, "mode", 16).flatMap {
        case None | Some("sync") => Right(QueryMode.Sync)
        case Some("async") => Right(QueryMode.Async)
        case Some(_) => Left(bad("mode", "must be 'sync' or 'async'"))
      }
      question <- str(o, "question", http.maxQuestionChars).map(_.filter(_.trim.nonEmpty))
      ops <- strings(o, "operations", http.maxListItems)
      names <- strings(o, "researcherNames", http.maxListItems)
      ids <- strings(o, "researcherIds", http.maxListItems)
      topics <- strings(o, "topics", http.maxListItems)
      venues <- strings(o, "venues", http.maxListItems)
      institutions <- strings(o, "institutions", http.maxListItems)
      metric <- str(o, "rankingMetric", 64)
      ws <- int(o, "windowStartYear")
      we <- int(o, "windowEndYear")
      gl <- int(o, "graphLimit")
      vl <- int(o, "vectorLimit")
      pd <- int(o, "maxPathDepth")
      _ <-
        if question.isEmpty && ops.isEmpty then
          Left(
            ApiError.badRequest("MISSING_QUERY", "a question or at least one operation is required")
          )
        else Right(())
      _ <- ids
        .find(id => StableId.parseAs(id, Set(IdKind.Researcher)).isLeft)
        .fold[Either[ApiError, Unit]](Right(()))(_ =>
          Left(bad("researcherIds", "must hold researcher IDs"))
        )
      // ---- constraints the runtime does not support: 422 ----------------------------------
      _ <- ops
        .find(n => OperationAllowlist.parseOperation(n).isEmpty)
        .fold[Either[ApiError, Unit]](Right(()))(_ =>
          Left(
            ApiError.unprocessable(
              "UNSUPPORTED_OPERATION",
              "an operation is not in the allowlist of runtime operations"
            )
          )
        )
      _ <- metric
        .filter(m => OperationAllowlist.parseMetric(m).isEmpty)
        .fold[Either[ApiError, Unit]](Right(()))(_ =>
          Left(
            ApiError
              .unprocessable("UNSUPPORTED_METRIC", "the ranking metric is not in the allowlist")
          )
        )
      _ <- (ws, we) match
        case (None, None) => Right(())
        case (Some(s), Some(e)) if s <= e => Right(())
        case (Some(_), Some(_)) =>
          Left(ApiError.unprocessable("WINDOW_INVALID", "the window start is after the window end"))
        case _ =>
          Left(
            ApiError.unprocessable(
              "WINDOW_INCOMPLETE",
              "a window needs both windowStartYear and windowEndYear"
            )
          )
      _ <- bound("graphLimit", gl, 1, limits.graphLimit)
      _ <- bound("vectorLimit", vl, 0, limits.vectorLimit)
      _ <- bound("maxPathDepth", pd, 1, limits.maxPathDepth)
    yield QueryRequest(
      mode,
      question,
      StructuredFields(ops, names, ids, ws, we, topics, venues, institutions, metric, gl, vl, pd)
    )

  private def bound(
      field: String,
      v: Option[Int],
      min: Int,
      max: Int
  ): Either[ApiError, Unit] =
    v match
      case Some(x) if x < min || x > max =>
        Left(
          ApiError.unprocessable(
            "LIMIT_OUT_OF_BOUNDS",
            s"field '$field' must be between $min and $max"
          )
        )
      case _ => Right(())
