package scholarchain.er.adjudication

/** Parses untrusted JSON (provider answers, model output) with a nesting bound.
  *
  * Rendering a ujson value is recursive, and a type-mismatch exception renders the whole value, so
  * a deeply nested answer would overflow the stack. The depth is checked on the text, before any
  * value is built. Same rule and bound as `rag`'s `BoundedJson` (the modules cannot share code).
  */
object BoundedJson:
  /** A structural safety bound, not a tunable: the envelopes and the adjudication schema nest
    * less than ten levels.
    */
  val MaxDepth = 64

  /** The parsed value. Throws, with a message that holds no input text, when the text nests deeper
    * than [[MaxDepth]]; throws the parser's exception when it is not JSON.
    */
  def read(text: String): ujson.Value =
    if tooDeep(text) then throw new IllegalArgumentException("the JSON is nested too deeply")
    ujson.read(text)

  def tooDeep(text: String): Boolean =
    var depth = 0
    var inString = false
    var i = 0
    while i < text.length && depth <= MaxDepth do
      val c = text.charAt(i)
      if inString then
        if c == '\\' then i += 1
        else if c == '"' then inString = false
      else if c == '"' then inString = true
      else if c == '[' || c == '{' then depth += 1
      else if c == ']' || c == '}' then depth -= 1
      i += 1
    depth > MaxDepth
