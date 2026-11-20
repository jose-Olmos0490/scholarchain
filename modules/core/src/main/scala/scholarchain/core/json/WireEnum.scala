package scholarchain.core.json

import upickle.default.*

/** Wire format for ScholarChain enums.
  *
  * Every enum is written as a bare JSON string equal to the case name (for example
  * `"ACCEPTED"`). Reading an unknown string fails decoding instead of mapping it to a default.
  * The mapping is explicit, rather than derived, so that the wire format does not depend on
  * library derivation defaults.
  */
object WireEnum:

  def readWriter[E](enumName: String, values: IndexedSeq[E]): ReadWriter[E] =
    val byName: Map[String, E] = values.map(v => v.toString -> v).toMap
    readwriter[String].bimap[E](
      _.toString,
      s =>
        byName.getOrElse(
          s,
          throw new IllegalArgumentException(
            s"unknown $enumName value '$s'; expected one of ${values.mkString(", ")}"
          )
        )
    )
