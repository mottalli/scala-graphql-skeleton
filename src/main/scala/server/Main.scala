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
import sangria.marshalling.circe._
import sangria.macros.derive._
import sangria.schema._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import sangria.execution.Executor

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

class Settings {
  val port: Int = 8080
}

case class Foo(bar: String, baz: Int)

class FooRepository {
  def getFoos(): List[Foo] = List(
    Foo("One", 1),
    Foo("Two", 2),
    Foo("Three", 3)
  )
}

object GraphQLSchema {
  val FooType = deriveObjectType[Unit, Foo]()

  val QueryType = ObjectType(
    "Query",
    fields[FooRepository, Unit](
      Field("foos", ListType(FooType), resolve = _.ctx.getFoos())
    )
  )

  val schema: Schema[FooRepository, Unit] = Schema(QueryType)
}

object Main {
  def main(args: Array[String]): Unit = {
    import com.softwaremill.macwire._

    lazy val settings: Settings = new Settings()
    lazy val context: FooRepository = new FooRepository()
    lazy val schema: Schema[FooRepository, Unit] = GraphQLSchema.schema
    lazy val graphQLServer: GraphQLServer[FooRepository] = wire[GraphQLServer[FooRepository]]
    lazy val server: Server[FooRepository] = wire[Server[FooRepository]]

    server.serve()
  }
}
