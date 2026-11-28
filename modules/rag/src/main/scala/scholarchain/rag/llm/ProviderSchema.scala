package scholarchain.rag.llm

/** Rewrites one of the project's versioned JSON Schemas into the subset that the Anthropic
  * structured-output request accepts:
  *
  *   - a `type` list becomes `anyOf` with one branch per type (an `enum` is split with it)
  *   - length, numeric-range and `maxItems` bounds are removed; `minItems` stays only as 0 or 1
  *   - every object is closed (`additionalProperties: false`)
  *
  * The result only guides generation. It is weaker than the versioned schema, which
  * [[StructuredLlmCall]] still applies in full to every answer.
  */
object ProviderSchema:
  def of(schema: ujson.Obj): ujson.Obj =
    val types = schema.value.get("type") match
      case Some(ujson.Str(t)) => Vector(t)
      case Some(ujson.Arr(ts)) => ts.toVector.collect { case ujson.Str(t) => t }
      case _ => Vector.empty
    types match
      case Vector() => branch(schema, None)
      case Vector(one) => branch(schema, Some(one))
      case many => ujson.Obj("anyOf" -> ujson.Arr.from(many.map(t => branch(schema, Some(t)))))

  private def matches(t: String, v: ujson.Value): Boolean = (t, v) match
    case ("string", _: ujson.Str) => true
    case ("integer" | "number", _: ujson.Num) => true
    case ("boolean", _: ujson.Bool) => true
    case _ => false

  private def branch(s: ujson.Obj, t: Option[String]): ujson.Obj =
    val out = ujson.Obj()
    t.foreach(name => out("type") = name)
    if !t.contains("null") then
      s.value.get("description").foreach(d => out("description") = ujson.copy(d))
      s.value.get("enum").collect { case ujson.Arr(values) =>
        out("enum") = ujson.Arr.from(values.filter(v => t.forall(matches(_, v))))
      }
    if t.contains("object") then
      val converted = ujson.Obj()
      s.value.get("properties").collect { case p: ujson.Obj => p }.foreach { props =>
        props.value.foreach {
          case (name, child: ujson.Obj) => converted(name) = of(child)
          case _ => ()
        }
      }
      out("properties") = converted
      s.value.get("required").foreach(r => out("required") = ujson.copy(r))
      out("additionalProperties") = false
    if t.contains("array") then
      s.value.get("items").collect { case i: ujson.Obj => i }.foreach(i => out("items") = of(i))
      s.value.get("minItems").collect {
        case ujson.Num(n) if n == 0 || n == 1 => out("minItems") = n
      }
    out
