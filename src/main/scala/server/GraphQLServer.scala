package server

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.typesafe.scalalogging.LazyLogging
import io.circe.Json
import io.circe.optics.JsonPath.root
import sangria.ast.Document
import sangria.execution.Executor
import sangria.parser.{QueryParser, SyntaxError}
import sangria.marshalling.circe._
import sangria.schema.Schema
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}
import scala.util.control.NonFatal

class GraphQLServer[Ctx](schema: Schema[Ctx, Unit], context: Ctx) extends LazyLogging {
  private def executeGraphQLQuery(query: Document, operation: Option[String], variables: Json)(
    implicit ec: ExecutionContext
  ) =
    Executor.execute(schema, query, context, variables = variables, operationName = operation)

  private def formatError(error: Throwable): Json = error match {
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

  def endpoint(implicit ec: ExecutionContext): Route =
    post {
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
