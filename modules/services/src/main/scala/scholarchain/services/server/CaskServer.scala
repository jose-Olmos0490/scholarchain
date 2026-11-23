package scholarchain.services.server

import scala.jdk.CollectionConverters.*
import scholarchain.services.api.ApiService
import scholarchain.services.http.{HttpRequest, QueryString}

/** The HTTP transport: a thin cask adapter. It reads the request into an [[HttpRequest]], calls
  * [[ApiService.handle]] and writes the [[scholarchain.services.http.HttpResponse]]. It holds no
  * routing or business logic. The body is read up to `maxBodyBytes + 1` bytes, so an oversized
  * body is refused by the service without being held in memory.
  */
final class CaskServer(api: ApiService, maxBodyBytes: Int, bindHost: String, bindPort: Int)
    extends cask.Main:
  override def host: String = bindHost
  override def port: Int = bindPort

  object Gateway extends cask.Routes:
    @cask.route(
      "/",
      methods = Seq("get", "post", "put", "delete", "patch", "head", "options"),
      subpath = true
    )
    def all(request: cask.Request): cask.Response[String] =
      val ex = request.exchange
      val headers = ex.getRequestHeaders.getHeaderNames.asScala.map { n =>
        n.toString.toLowerCase -> ex.getRequestHeaders.getFirst(n)
      }.toMap
      val body = ex.getInputStream.readNBytes(maxBodyBytes + 1)
      val response = QueryString.parse(ex.getQueryString) match
        case Some(q) =>
          api.handle(
            HttpRequest.of(ex.getRequestMethod.toString, ex.getRequestPath, q, headers, body)
          )
        case None =>
          // a malformed query string is a caller error; the service answers it like any other
          api.handle(
            HttpRequest.of(
              ex.getRequestMethod.toString,
              "/invalid-query-string",
              Map.empty,
              headers,
              body
            )
          )
      cask.Response(response.body, response.status, response.headers.toSeq)

    initialize()

  def allRoutes: Seq[cask.Routes] = Seq(Gateway)
