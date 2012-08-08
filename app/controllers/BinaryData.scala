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
import models.Actors._
import models.Role
import brainflight.binary._
import brainflight.security.Secured
import brainflight.tools.geometry.{ Point3D, Cuboid }
import models.DataSet
import akka.pattern.AskTimeoutException

/**
 * scalableminds - brainflight
 * User: tmbo
 * Date: 11.12.11
 * Time: 13:21
 */

object BinaryData extends Controller with Secured {
  
  override val DefaultAccessRole = Role.User
  
  val conf = Play.configuration
  val scaleFactors = Array(1,1,2)

  implicit val timeout = Timeout( (conf.getInt("actor.defaultTimeout") getOrElse 5 ) seconds ) // needed for `?` below
    
  def calculateBinaryData( dataSet: DataSet, cuboid: Cuboid, resolution: Int ): Future[Array[Byte]] = {
    // rotate the model and generate the requested data
    val future = (DataSetActor ? CubeRequest( dataSet, resolution, cuboid )) recover { 
      case e: AskTimeoutException => 
        new Array[Byte](0) }
    
    future.mapTo[Array[Byte]]
  }
  
  def resolutionFromExponent( exp: Int ) =
    math.pow( 2, exp ).toInt
  
  def cuboidFromPosition( position: Point3D, cubeSize: Int ) = {  
    val cubeCorner = position.scale{
        case (x,i) => 
          x - x % (cubeSize/scaleFactors(i))
    }
    Cuboid( cubeCorner, cubeSize / scaleFactors(0), cubeSize / scaleFactors(1), cubeSize / scaleFactors(2) )
  }

  /**
   * Handles a request for binary data via a HTTP POST. The content of the
   * POST body is specified in the BinaryProtokoll.parseAjax functions.
   */
  def requestViaAjax( dataSetId: String, cubeSize: Int ) = Authenticated( parser = parse.raw ) { implicit request =>
      Async {
        ( for {
          payload <- request.body.asBytes()
          message <- BinaryProtocol.parseAjax( payload )
          dataSet <- DataSet.findOneById( dataSetId )
        } yield {
          message match {
            case RequestData( exp, position ) =>
              val resolution = resolutionFromExponent( exp )
              val cuboid = cuboidFromPosition( position, cubeSize )
              calculateBinaryData( dataSet, cuboid, resolution ).asPromise.map(
                result => Ok( result ) )
            case _ =>
              Akka.future {
                BadRequest( "Unknown message." )
              }
          }
        } ) getOrElse ( Akka.future { BadRequest( "Request body is to short: %d bytes".format( request.body.size ) ) } )
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
  def requestViaWebsocket( dataSetId: String, cubeSize: Int ) = AuthenticatedWebSocket[Array[Byte]]() { user =>
    request =>
      val dataSetOpt = DataSet.findOneById( dataSetId )
      val (output, channel) = Concurrent.broadcast[Array[Byte]]
      val input = Iteratee.foreach[Array[Byte]]( in => {
        // first 4 bytes are always used as a client handle
        BinaryProtocol.parseWebsocket( in ).map {
          case message @ RequestData( exp, position ) =>
            val resolution = resolutionFromExponent( exp )
            val cuboid = cuboidFromPosition( position, cubeSize )
            for {
              dataSet <- dataSetOpt
              result <- calculateBinaryData( dataSet, cuboid, resolution )
            } {
              channel.push( message.handle ++ result )
            }
          case _ =>
            Logger.error("Received unhandled message!")
        }
      } )
      ( input, output )
  }
}