package aecor.schedule

import aecor.data.CorrelationId
import aecor.encoding.{ KeyDecoder, KeyEncoder }

final case class ScheduleBucketId(scheduleName: String, scheduleBucket: String)

object ScheduleBucketId {
  implicit val keyEncoder: KeyEncoder[ScheduleBucketId] = KeyEncoder.instance[ScheduleBucketId] {
    case ScheduleBucketId(scheduleName, scheduleBucket) =>
      CorrelationId.composite("-", scheduleName, scheduleBucket)
  }
  implicit val keyDecoder: KeyDecoder[ScheduleBucketId] =
    KeyDecoder
      .split("-")
      .collect {
        case scheduleName :: scheduleBucket :: Nil =>
          ScheduleBucketId(scheduleName, scheduleBucket)
      }

}
