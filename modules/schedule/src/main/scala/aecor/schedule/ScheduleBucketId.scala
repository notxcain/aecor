package aecor.schedule

import aecor.data.Composer
import aecor.encoding.{ KeyDecoder, KeyEncoder }

final case class ScheduleBucketId(scheduleName: String, scheduleBucket: String)

object ScheduleBucketId {
  val encoder = Composer.WithSeparator('-')

  implicit val keyEncoder: KeyEncoder[ScheduleBucketId] = KeyEncoder.instance[ScheduleBucketId] {
    case ScheduleBucketId(scheduleName, scheduleBucket) =>
      encoder(scheduleName, scheduleBucket)
  }
  implicit val keyDecoder: KeyDecoder[ScheduleBucketId] =
    KeyDecoder[String].collect {
      case encoder(scheduleName :: scheduleBucket :: Nil) =>
        ScheduleBucketId(scheduleName, scheduleBucket)
    }

}
