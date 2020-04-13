package server

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import com.typesafe.scalalogging.LazyLogging

class Server[Ctx](settings: Settings, graphQLServer: GraphQLServer[Ctx]) extends LazyLogging {

  implicit lazy val system: ActorSystem = ActorSystem("sangria-graphql-server")
  implicit lazy val materializer: ActorMaterializer = ActorMaterializer()

  import system.dispatcher

  lazy val route: Route =
    path("graphql") {
      graphQLServer.endpoint
    } ~
      (get & pathEndOrSingleSlash) {
        redirect("/graphql", StatusCodes.PermanentRedirect)
      }

  def serve(): Unit = {
    val bindF = Http().bindAndHandle(route, "0.0.0.0", settings.port)

    logger.info(s"Started server at http://localhost:${settings.port}. Press <enter> to terminate.")
    scala.io.StdIn.readLine()
    bindF
      .flatMap(_.unbind())
      .onComplete(_ => system.terminate())
  }
}
