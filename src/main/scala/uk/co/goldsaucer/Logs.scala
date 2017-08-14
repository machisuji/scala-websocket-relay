package uk.co.goldsaucer

trait Logs {
  val log = new Log
}

class Log {
  def debug(message: String): Unit = log(message, Log.Level.Debug)
  def info(message: String): Unit = log(message, Log.Level.Info)
  def warning(message: String): Unit = log(message, Log.Level.Warning)
  def error(message: String): Unit = log(message, Log.Level.Error)

  def log(message: String, level: Log.Level): Unit = {
    if (level.value >= Log.level.value) {
      logMessage(message, level)
    }
  }

  def logMessage(message: String, level: Log.Level): Unit = println(formatMessage(message, level))

  def formatMessage(message: String, level: Log.Level): String =
    s"${format.format(new java.util.Date)} ${level.toString.toUpperCase} | $message"

  val format = new java.text.SimpleDateFormat("dd.MM.yyyy hh:mm:ss")
}

object Log {
  sealed abstract class Level(val value: Int)

  object Level {
    case object Debug extends Level(0)
    case object Info extends Level(1)
    case object Warning extends Level(2)
    case object Error extends Level(3)
  }

  var level: Level = Level.Info
}
