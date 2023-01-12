package mill.scalajslib.worker

import org.scalajs.logging._

object LoggerConverter {
  def toScalaJS(logger: api.Logger) = new Logger {
    def log(level: Level, message: => String): Unit = level match {
      case Level.Info => logger.info(message)
      case Level.Error => logger.error(message)
      case Level.Warn => logger.warn(message)
      case Level.Debug => logger.info(message)
    }
    def trace(t: => Throwable): Unit = logger.trace(t)
  }
}
