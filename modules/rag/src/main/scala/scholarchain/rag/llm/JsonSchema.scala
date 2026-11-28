package scholarchain.rag.llm

import scholarchain.rag.agents.{SResult, StageFailure}

/** A strict validator for the JSON Schema subset the project's schemas use. A schema that uses a
  * keyword outside the subset is refused when it is loaded, so a schema can never look stricter
  * than it is.
  *
  * Subset: type (one name or a list), enum, required, properties, additionalProperties (boolean),
  * items, minItems, maxItems, minLength, maxLength, minimum, maximum; annotations `$schema`,
  * `$id`, `title`, `description`.
  *
  * Violation messages name the path and the rule, never the offending value or the name of a
  * property the schema does not declare, so that they are safe to log and to return.
  */
final class JsonSchema private (val version: String, root: ujson.Obj):
  import JsonSchema.*

  /** The schema as JSON text. */
  val text: String = root.render()

  def validate(value: ujson.Value): Vector[String] = check(root, value, "$")

  private def typeNames(s: ujson.Obj): Option[Vector[String]] = s.value.get("type").map {
    case ujson.Str(t) => Vector(t)
    case ujson.Arr(ts) => ts.toVector.collect { case ujson.Str(t) => t }
    case _ => Vector.empty
  }

  private def isType(t: String, v: ujson.Value): Boolean = (t, v) match
    case ("string", _: ujson.Str) => true
    case ("integer", ujson.Num(n)) => n.isWhole && !n.isInfinite
    case ("number", _: ujson.Num) => true
    case ("boolean", _: ujson.Bool) => true
    case ("array", _: ujson.Arr) => true
    case ("object", _: ujson.Obj) => true
    case ("null", ujson.Null) => true
    case _ => false

  private def check(s: ujson.Obj, v: ujson.Value, path: String): Vector[String] =
    val out = Vector.newBuilder[String]
    typeNames(s).foreach { ts =>
      if !ts.exists(isType(_, v)) then out += s"$path: wrong type (expected ${ts.mkString(" or ")})"
    }
    s.value.get("enum").foreach {
      case ujson.Arr(allowed) =>
        if !allowed.exists(_ == v) then out += s"$path: not one of the allowed values"
      case _ => ()
    }
    def num(key: String): Option[Double] = s.value.get(key).collect { case ujson.Num(n) => n }
    v match
      case ujson.Str(str) =>
        val len = str.codePointCount(0, str.length)
        num("minLength").foreach(m => if len < m then out += s"$path: shorter than minLength")
        num("maxLength").foreach(m => if len > m then out += s"$path: longer than maxLength")
      case ujson.Num(n) =>
        num("minimum").foreach(m => if n < m then out += s"$path: below minimum")
        num("maximum").foreach(m => if n > m then out += s"$path: above maximum")
      case ujson.Arr(items) =>
        num("minItems").foreach(m => if items.size < m then out += s"$path: fewer than minItems")
        num("maxItems").foreach(m => if items.size > m then out += s"$path: more than maxItems")
        s.value.get("items").collect { case o: ujson.Obj => o }.foreach { itemSchema =>
          items.zipWithIndex.foreach((item, i) => out ++= check(itemSchema, item, s"$path[$i]"))
        }
      case o: ujson.Obj =>
        val props: Map[String, ujson.Value] =
          s.value
            .get("properties")
            .collect { case p: ujson.Obj => p.value.toMap }
            .getOrElse(Map.empty)
        s.value.get("required").collect { case ujson.Arr(r) => r }.foreach { req =>
          req.collect { case ujson.Str(k) => k }.foreach { k =>
            if !o.value.contains(k) then out += s"$path.$k: required but missing"
          }
        }
        var extra = false
        o.value.foreach { (k, child) =>
          props.get(k) match
            case Some(ps: ujson.Obj) => out ++= check(ps, child, s"$path.$k")
            case _ =>
              if s.value.get("additionalProperties").contains(ujson.False) then extra = true
        }
        // the name of an extra property is chosen by the sender, so it is not repeated
        if extra then out += s"$path: a property is not allowed"
      case _ => ()
    out.result()

object JsonSchema:
  private val Annotations = Set("$schema", "$id", "title", "description")
  private val Keywords = Set(
    "type",
    "enum",
    "required",
    "properties",
    "additionalProperties",
    "items",
    "minItems",
    "maxItems",
    "minLength",
    "maxLength",
    "minimum",
    "maximum"
  ) ++ Annotations

  private def keywordProblems(s: ujson.Value, path: String): Vector[String] = s match
    case o: ujson.Obj =>
      val unknown = o.value.keys.filterNot(Keywords).toVector.sorted
      unknown.map(k => s"$path: unsupported schema keyword '$k'") ++
        o.value
          .get("properties")
          .collect { case p: ujson.Obj => p }
          .toVector
          .flatMap(p =>
            p.value.toVector.flatMap((k, c) => keywordProblems(c, s"$path.properties.$k"))
          ) ++
        o.value.get("items").toVector.flatMap(keywordProblems(_, s"$path.items"))
    case _ => Vector(s"$path: a schema must be an object")

  def parse(version: String, text: String): SResult[JsonSchema] =
    try
      ujson.read(text) match
        case o: ujson.Obj =>
          val problems = keywordProblems(o, "$")
          if problems.isEmpty then Right(new JsonSchema(version, o))
          else Left(StageFailure.Rejected("SCHEMA_UNSUPPORTED", problems.mkString("; ")))
        case _ => Left(StageFailure.Rejected("SCHEMA_INVALID", s"schema $version is not an object"))
    catch
      case scala.util.control.NonFatal(e) =>
        Left(StageFailure.Rejected("SCHEMA_INVALID", s"schema $version is not valid JSON"))
