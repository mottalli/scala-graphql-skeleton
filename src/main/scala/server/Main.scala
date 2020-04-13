package server

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import com.typesafe.scalalogging.LazyLogging
import io.circe._
import io.circe.optics.JsonPath._
import io.circe.parser._
import sangria.parser.{QueryParser, SyntaxError}
import sangria.ast.Document
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

class Settings {
  val port: Int = 8080
}

class GraphQLServer extends LazyLogging {
  private def executeGraphQLQuery(query: Document, operation: Option[String], variables: Json) = ???

  def formatError(error: Throwable): Json = error match {
    case syntaxError: SyntaxError ⇒
      Json.obj(
        "errors" → Json.arr(
          Json.obj(
            "message" → Json.fromString(syntaxError.getMessage),
            "locations" → Json.arr(
              Json.obj(
                "line" → Json.fromBigInt(syntaxError.originalError.position.line),
                "column" → Json.fromBigInt(syntaxError.originalError.position.column)
              )
            )
          )
        )
      )
    case NonFatal(e) ⇒
      formatError(e.getMessage)
    case e ⇒
      throw e
  }

  private def formatError(message: String): Json =
    Json.obj("errors" -> Json.arr(Json.obj("message" -> Json.fromString(message))))

  val endpoint: Route = post {
    entity(as[Json]) { requestJson =>
      val queryOpt: Option[String] = root.selectDynamic("query").string.getOption(requestJson)

      queryOpt match {
        case None => complete(StatusCodes.BadRequest, formatError("No GraphQL query to execute"))
        case Some(query) =>
          QueryParser.parse(query) match {
            case Failure(error) => complete(StatusCodes.BadRequest, formatError(error))
            case Success(queryAst) =>
              val operationName: Option[String] = root.selectDynamic("operationName").string.getOption(requestJson)
              val variables: Json = root.selectDynamic("variables").json.getOption(requestJson).getOrElse(Json.obj())
              complete(executeGraphQLQuery(queryAst, operationName, variables))
          }
      }
    }
  } ~
    get { getFromResource("playground.html") }
}

class Server(settings: Settings) extends LazyLogging {

  implicit lazy val system: ActorSystem = ActorSystem("sangria-graphql-server")
  implicit lazy val materializer: ActorMaterializer = ActorMaterializer()

  import system.dispatcher

  lazy val graphQLServer = new GraphQLServer

  lazy val route: Route =
    path("graphql") {
      graphQLServer.endpoint
    } ~
      (get & pathEndOrSingleSlash) {
        redirect("/graphql", StatusCodes.PermanentRedirect)
      }

  def serve(): Unit = {
    Http().bindAndHandle(route, "0.0.0.0", settings.port)
  }
}

object Main {
  def main(args: Array[String]): Unit = {
    import com.softwaremill.macwire._

    lazy val settings: Settings = new Settings()
    lazy val server: Server = wire[Server]

    server.serve()
  }
}
