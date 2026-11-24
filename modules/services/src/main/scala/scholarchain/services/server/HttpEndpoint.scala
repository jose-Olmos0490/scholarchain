package scholarchain.services.server

import io.undertow.Undertow
import scala.jdk.CollectionConverters.*
import scholarchain.services.api.ApiService

/** A running HTTP listener over [[CaskServer]]'s handler. Unlike `cask.Main.main`, it starts the
  * Undertow server itself, so it can bind port 0 (an ephemeral port, for tests) and report the
  * port it got, and so a caller can stop it.
  */
final class HttpEndpoint private (server: Undertow, val boundPort: Int):
  def stop(): Unit = server.stop()

object HttpEndpoint:
  def start(api: ApiService, maxBodyBytes: Int, host: String, port: Int): HttpEndpoint =
    val cask = new CaskServer(api, maxBodyBytes, host, port)
    val server = Undertow
      .builder()
      .addHttpListener(port, host)
      .setHandler(cask.defaultHandler)
      .build()
    server.start()
    val bound = server.getListenerInfo.asScala.head.getAddress match
      case a: java.net.InetSocketAddress => a.getPort
      case _ => port
    new HttpEndpoint(server, bound)
