package controllers

import java.nio.ByteBuffer
import akka.actor._
import akka.dispatch._
import akka.util.duration._
import akka.pattern.{ ask, pipe }
import akka.util.Timeout
import play.api._
import play.api.mvc._
import play.api.mvc.AsyncResult
import play.api.data._
import play.api.libs.json.Json._
import play.api.Play.current
import play.api.libs.iteratee._
import Input.EOF
import play.api.libs.concurrent._
import play.api.libs.json.JsValue
import play.libs.Akka._
import models.security.Role
import models.binary.DataSet
import brainflight.binary._
import brainflight.security.Secured
import brainflight.tools.geometry.{ Point3D, Cuboid }
import akka.pattern.AskTimeoutException
import play.api.libs.iteratee.Concurrent.Channel
import scala.collection.mutable.ArrayBuffer
import akka.routing.RoundRobinRouter
import play.api.libs.concurrent.execution.defaultContext
import brainflight.tools.geometry.Vector3D
import models.knowledge.Level
//import scala.concurrent.ExecutionContext.Implicits.global

/**
 * scalableminds - brainflight
 * User: tmbo
 * Date: 11.12.11
 * Time: 13:21
 */

object BinaryData extends Controller with Secured {
  override val DefaultAccessRole = Role.User

  val dataSetActor = Akka.system.actorOf(Props(new DataSetActor).withRouter(
    RoundRobinRouter(nrOfInstances = 8)))

  implicit val dispatcher = Akka.system.dispatcher
  val conf = Play.configuration
  val scaleFactors = Array(1, 1, 1)

  implicit val timeout = Timeout((conf.getInt("actor.defaultTimeout") getOrElse 5) seconds) // needed for `?` below

  def resolutionFromExponent(resolutionExponent: Int) =
    math.pow(2, resolutionExponent).toInt

  def cuboidFromPosition(position: Point3D, cubeSize: Int) = {
    val cubeCorner = position.scale {
      case (x, i) =>
        x - x % (cubeSize / scaleFactors(i))
    }
    Cuboid(cubeCorner, cubeSize / scaleFactors(0), cubeSize / scaleFactors(1), cubeSize / scaleFactors(2))
  }

  def handleMultiDataRequest( multi: MultipleDataRequest, cubeSize: Int, dataSet: DataSet, halfByte: Boolean) = {
    val cubeRequests = multi.requests.map{ request =>
      val resolution = resolutionFromExponent( request.resolutionExponent )
      val cuboid = cuboidFromPosition( request.position, cubeSize )
      CubeRequest( dataSet, resolution, cuboid, halfByte )
    }

    val future = (dataSetActor ? MultiCubeRequest(cubeRequests)) recover {
      case e: AskTimeoutException =>
        new Array[Byte](0)
    }

    future.mapTo[Array[Byte]]
  }

  def arbitraryViaAjax(levelId: String, taskId: String) = Authenticated(parser = parse.raw) { implicit request =>
    Async {
      Level.findOneById(levelId).map{ level =>
        val t = System.currentTimeMillis()
        val dataSet = DataSet.default
        val position = Point3D(554, 543, 523)
        val direction = (1.0, 1.0, 1.0)
  
        val point = (position.x.toDouble, position.y.toDouble, position.z.toDouble)
        val m = new CubeModel(level.width, level.height, level.depth)
        val points = m.rotateAndMove(point, direction)
        val future = dataSetActor ? ArbitraryRequest(dataSet, 1, points) recover {
          case e: AskTimeoutException =>
            Logger.error("calculateImages: AskTimeoutException")
            Array.fill[Byte](level.height * level.width * level.depth)(0)
        }
        future.mapTo[Array[Byte]].asPromise.map{data => 
          Logger.debug("total: %d ms".format(System.currentTimeMillis - t))
          Ok(data)
        }
      } getOrElse {
        Akka.future( BadRequest("Level not found.") )
      }
    }
  }

  /**
   * Handles a request for binary data via a HTTP POST. The content of the
   * POST body is specified in the BinaryProtokoll.parseAjax functions.
   */
  def requestViaAjax( dataSetId: String, cubeSize: Int, halfByte: Boolean ) = Authenticated( parser = parse.raw ) { implicit request =>
    Async {
      (for {
        payload <- request.body.asBytes()
        message <- BinaryProtocol.parseAjax(payload)
        dataSet <- DataSet.findOneById(dataSetId)
      } yield {
        message match {
          case dataRequests @ MultipleDataRequest(_) =>
            handleMultiDataRequest(dataRequests, cubeSize, dataSet, halfByte).asPromise.map( result =>
              Ok( result ) )
          case _ =>
            Akka.future {
              BadRequest("Unknown message.")
            }
        }
      }) getOrElse (Akka.future { BadRequest("Request body is to short: %d bytes".format(request.body.size)) })
    }
  }
  /**
   * Handles a request for binary data via websockets. The content of a websocket
   * message is defined in the BinaryProtokoll.parseWebsocket function.
   * If the message is valid the result is posted onto the websocket.
   *
   * @param
   * 	modelType:	id of the model to use
   */
  def requestViaWebsocket( dataSetId: String, cubeSize: Int, halfByte: Boolean ) = AuthenticatedWebSocket[Array[Byte]]() { user =>
    request =>
      val dataSetOpt = DataSet.findOneById(dataSetId)
      var channelOpt: Option[Channel[Array[Byte]]] = None

      val output = Concurrent.unicast[Array[Byte]](
        { c => channelOpt = Some(c) },
        { Logger.debug("Data websocket completed") },
        { case (e, i) => Logger.error("An error ocourd on websocket stream: " + e) })

      val input = Iteratee.foreach[Array[Byte]](in => {
        for {
          dataSet <- dataSetOpt
          channel <- channelOpt
        } {
          BinaryProtocol.parseWebsocket( in ).map {
            case dataRequests : MultipleDataRequest =>
              Logger.trace("Websocket DataRequests: " + dataRequests.requests.mkString(", "))
              handleMultiDataRequest(dataRequests, cubeSize, dataSet, halfByte).map{ result => 
                Logger.trace("Websocket result size: " + result.size)
                channel.push(result ++ dataRequests.handle )
              }
            case _ =>
              Logger.error("Received unhandled message!")
          }
        }
      })
      (input, output)
  }
}