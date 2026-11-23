package scholarchain.services.security

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.collection.mutable
import scala.util.control.NonFatal
import scholarchain.services.config.RateLimitSettings
import scholarchain.services.http.ServiceClock

/** What a caller may do. Scopes are coarse and per API area. */
object Scopes:
  val QueryWrite = "query:write"
  val QueryRead = "query:read"
  val EvidenceRead = "evidence:read"
  val GraphRead = "graph:read"
  val OpsRead = "ops:read"
  val all: Set[String] = Set(QueryWrite, QueryRead, EvidenceRead, GraphRead, OpsRead)

/** An authenticated caller. `id` is a non-secret label from the key record. */
final case class Principal(id: String, scopes: Set[String]):
  def has(scope: String): Boolean = scopes.contains(scope)

/** One API key as it is stored: the SHA-256 of the token, never the token itself. */
final case class ApiKeyRecord(id: String, tokenSha256: String, scopes: Set[String])

enum AuthFailure:
  case Missing, Malformed, Unknown

/** Authentication port. The course deployment uses bearer API keys held in Secrets Manager. */
trait Authenticator:
  def authenticate(authorizationHeader: Option[String]): Either[AuthFailure, Principal]

object Sha256Hex:
  def of(text: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes(StandardCharsets.UTF_8))
      .map(b => f"${b & 0xff}%02x")
      .mkString

/** Bearer-token authentication against SHA-256 digests of the issued tokens. Comparison is
  * constant-time per record and visits every record, so timing does not tell which key matched.
  */
final class BearerAuthenticator(keys: => Vector[ApiKeyRecord], maxTokenChars: Int)
    extends Authenticator:
  private val Bearer = "(?i)bearer +(.+)".r

  def authenticate(header: Option[String]): Either[AuthFailure, Principal] =
    header match
      case None => Left(AuthFailure.Missing)
      case Some(Bearer(token)) if token.trim.nonEmpty && token.trim.length <= maxTokenChars =>
        val digest = Sha256Hex.of(token.trim).getBytes(StandardCharsets.UTF_8)
        val hit = keys.foldLeft(Option.empty[ApiKeyRecord]) { (found, k) =>
          val same =
            MessageDigest.isEqual(
              digest,
              k.tokenSha256.toLowerCase.getBytes(StandardCharsets.UTF_8)
            )
          if same && found.isEmpty then Some(k) else found
        }
        hit.map(k => Principal(k.id, k.scopes)).toRight(AuthFailure.Unknown)
      case Some(_) => Left(AuthFailure.Malformed)

/** Where API-key records come from. The production source is Secrets Manager. */
trait ApiKeySource:
  def load(): Either[String, Vector[ApiKeyRecord]]

object ApiKeyJson:
  /** `[{"id": "...", "sha256": "<64 hex>", "scopes": ["query:write", ...]}]`. */
  def parse(json: String): Either[String, Vector[ApiKeyRecord]] =
    try
      val records = ujson.read(json).arr.toVector.map { v =>
        val id = v("id").str
        val sha = v("sha256").str.toLowerCase
        val scopes = v("scopes").arr.map(_.str).toSet
        require(id.nonEmpty && !id.contains('#') && !id.contains(':'), "bad key id")
        require(sha.matches("[0-9a-f]{64}"), "bad key digest")
        require(scopes.nonEmpty && scopes.subsetOf(Scopes.all), "bad key scopes")
        ApiKeyRecord(id, sha, scopes)
      }
      Right(records)
    catch case NonFatal(_) => Left("the API key document is not valid") // never echo the content

final class StaticApiKeySource(keys: Vector[ApiKeyRecord]) extends ApiKeySource:
  def load(): Either[String, Vector[ApiKeyRecord]] = Right(keys)

/** A fixed-window limiter per principal. */
final class RateLimiter(settings: RateLimitSettings, clock: ServiceClock):
  final private class Window(var startMillis: Long, var count: Int)
  private val windows = mutable.HashMap.empty[String, Window]

  /** `Right(())` when the call may proceed, else the seconds until the window ends. */
  def tryAcquire(principalId: String): Either[Long, Unit] = synchronized {
    val now = clock.nowMillis
    val lengthMillis = settings.windowSeconds * 1000L
    val w = windows.getOrElseUpdate(principalId, new Window(now, 0))
    if now - w.startMillis >= lengthMillis then
      w.startMillis = now
      w.count = 0
    if w.count < settings.requestsPerWindow then
      w.count += 1
      Right(())
    else Left(math.max(1L, (w.startMillis + lengthMillis - now + 999) / 1000))
  }
