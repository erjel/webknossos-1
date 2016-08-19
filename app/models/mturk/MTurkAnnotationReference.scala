package models.mturk

import play.api.libs.json.Json
import reactivemongo.bson.BSONObjectID
import reactivemongo.play.json.BSONFormats._

case class MTurkAnnotationReference(_annotation: BSONObjectID, _user: BSONObjectID, assignmentId: String)

object MTurkAnnotationReference {
  implicit val mturkAnnotationReferenceFormat = Json.format[MTurkAnnotationReference]
}
