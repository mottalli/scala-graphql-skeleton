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

import scala.util.control.NonFatal
import scala.util.{Failure, Success}

class Settings {
  val port: Int = 8080
}

/*
class GraphQLEndpoint extends LazyLogging {
  val endpoint: Route =
}
 */

class Server(settings: Settings) extends LazyLogging {
  def formatError(message: String): Json =
    Json.obj("errors" → Json.arr(Json.obj("message" → Json.fromString(message))))

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

  def executeGraphQL(query: Document, operationName: Option[String], variables: Json): Route = ???

  def serve(): Unit = {
    implicit val system: ActorSystem = ActorSystem("sangria-server")
    implicit val materializer: ActorMaterializer = ActorMaterializer()

    import system.dispatcher

    val route: Route =
      path("graphql") {
        post {
          parameters('query.?, 'operationName.?, 'variables.?) { (queryParam, operationNameParam, variablesParam) =>
            entity(as[Json]) { body =>
              def getFieldOpt(fieldName: String): Option[String] = root.selectDynamic(fieldName).string.getOption(body)

              val query = queryParam.orElse(getFieldOpt("query"))
              val operationName = operationNameParam.orElse(getFieldOpt("operationName"))
              val variables = variablesParam.orElse(getFieldOpt("variables"))

              query.map(QueryParser.parse(_)) match {
                case None                 => complete(StatusCodes.BadRequest, formatError("No query to execute"))
                case Some(Failure(error)) => complete(StatusCodes.BadRequest, formatError(error))
                case Some(Success(ast)) =>
                  variables.map(parse) match {
                    case Some(Left(error)) => complete(StatusCodes.BadRequest, formatError(error))
                    case Some(Right(json)) => executeGraphQL(ast, operationName, json)
                    case None =>
                      val variablesJson = root.selectDynamic("variables").json.getOption(body).getOrElse(Json.obj())
                      executeGraphQL(ast, operationName, variablesJson)
                  }
              }
            }
          }
        }
      } ~
        get {
          getFromResource("playground.html")
        } ~
        (get & pathEndOrSingleSlash) {
          redirect("/graphql", StatusCodes.PermanentRedirect)
        }

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
