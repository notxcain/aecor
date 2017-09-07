package aecor.schedule

import java.util.regex.Pattern

import aecor.data.CorrelationId
import aecor.encoding.{ KeyDecoder, KeyEncoder }

final case class ScheduleBucketId(scheduleName: String, scheduleBucket: String)

object ScheduleBucketId {
  implicit val keyEncoder: KeyEncoder[ScheduleBucketId] = KeyEncoder.instance[ScheduleBucketId] {
    case ScheduleBucketId(scheduleName, scheduleBucket) =>
      CorrelationId.composite("-", scheduleName, scheduleBucket)
  }
  implicit val keyDecoder: KeyDecoder[ScheduleBucketId] = {
    val pattern = Pattern.compile("(?<!\\\\)-")
    KeyDecoder[String]
      .map(pattern.split(_))
      .map(_.toList)
      .collect {
        case scheduleName :: scheduleBucket :: Nil =>
          ScheduleBucketId(scheduleName, scheduleBucket)
      }
  }
}
