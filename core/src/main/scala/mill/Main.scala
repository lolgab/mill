package mill

import ammonite.interp.Interpreter
import ammonite.main.Scripts
import ammonite.ops._
import ammonite.util.{Colors, Res}
import mill.define.Task
import mill.discover._
import mill.eval.{Evaluator, Result}
import mill.util.OSet
import ammonite.main.Scripts.pathScoptRead
import ammonite.repl.Repl

object Main {

  def parseSelector(input: String) = {
    import fastparse.all._
    val segment = P( CharsWhileIn(('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9')).! )
    val crossSegment = P( "[" ~ CharsWhile(c => c != ',' && c != ']').!.rep(1, sep=",") ~ "]" )
    val query = P( segment ~ ("." ~ segment.map(Left(_)) | crossSegment.map(Right(_))).rep ~ End ).map{
      case (h, rest) => Left(h) :: rest.toList
    }
    query.parse(input)
  }

  def parseArgs(args: Seq[String]): Either[Throwable, List[scala.util.Either[String,Seq[String]]]] = {
    import fastparse.all.Parsed

    val Seq(selectorString, rest @_*) = args

    parseSelector(selectorString) match {
      case f: Parsed.Failure =>
        Left(new Exception("Parsing exception"+f.msg))
      case Parsed.Success(selector, _) =>
        Right(selector)
    }
  }

  def resolve[T, V](
      selector: List[Either[String, Seq[String]]],
      hierarchy: Mirror[T, V])(implicit
      obj: T,
      rest: Seq[String],
      crossSelectors: List[List[String]]): Either[Throwable, Task[Any]] = {

    selector match{
      case Right(_) :: Nil =>
        Left(new Exception("No target or command selected"))
      case Left(last) :: Nil =>
        def target: Option[Task[Any]] =
          hierarchy.targets.find(_.label == last).map(_.run(hierarchy.node(obj, crossSelectors)))

        def command: Either[Throwable, Task[Any]] =
          hierarchy.commands.find(_.name == last).map{ x =>
            Option(hierarchy.node(obj, crossSelectors)).map(inst =>
              x.invoke(inst, ammonite.main.Scripts.groupArgs(rest.toList)) match {
                case Router.Result.Success(v) =>
                  Right(v)
                case _ =>
                  Left(new Exception(s"Method not found $last"))
              }
            ).getOrElse(
              Left(new Exception(s"Instance not found for calling $last"))
            )
          }.getOrElse(Left(new Exception(s"Command not found last")))

        target.map(Right(_)) getOrElse command
      case head :: tail =>
        head match{
          case Left(singleLabel) =>
            hierarchy.children
              .find{ case (label, child) => label == singleLabel }
              .map{ case (label, child) => resolve(tail, child) }
              .getOrElse(
                Left(new Exception(s"Single label not found $singleLabel"))
              )
          case Right(cross) =>
            resolve(tail, hierarchy.crossChildren.get._2)
        }

      case Nil => Left(new Exception("Nothing to run"))
    }
  }

  def discoverMirror[T: Discovered](obj: T): Either[Throwable, Discovered[T]] = {
    val discovered = implicitly[Discovered[T]]
    val consistencyErrors = Discovered.consistencyCheck(obj, discovered)
    if (consistencyErrors.nonEmpty) {
      Left(new Exception(s"Failed Discovered.consistencyCheck: $consistencyErrors"))
    } else {
      Right(discovered)
    }
  }

  def evaluate(evaluator: Evaluator,
      target: Task[Any],
      watch: Path => Unit): Either[Throwable, Int] = {
    val evaluated = evaluator.evaluate(OSet(target))
    evaluated.transitive.foreach {
      case t: define.Source => watch(t.handle.path)
      case _ => // do nothing
    }

    val errorStr =
      (for((k, fs) <- evaluated.failing.items) yield {
        val ks = k match{
          case Left(t) => t.toString
          case Right(t) => t.segments.mkString(".")
        }
        val fss = fs.map{
          case Result.Exception(t) => t.toString
          case Result.Failure(t) => t
        }
        s"$ks ${fss.mkString(", ")}"
      }).mkString("\n")

    evaluated.failing.keyCount match {
      case 0 =>
        Right(0)
      case n =>
        Left(new Exception(s"$n targets failed\n$errorStr"))
    }
  }

  def apply[T: Discovered](args: Seq[String],
                           obj: T,
                           watch: Path => Unit,
                           coloredOutput: Boolean): Int = {

    val log = new Logger(coloredOutput)

    val Seq(_, rest @_*) = args

    (for {
      sel <- parseArgs(args)
      disc <- discoverMirror(obj)
      val crossSelectors = sel.collect{case Right(x) => x.toList}
      target <- resolve(sel, disc.mirror)(obj, rest, crossSelectors)
      val mapping = Discovered.mapping(obj)(disc)
      val workspacePath = pwd / 'out
      val evaluator = new Evaluator(workspacePath, mapping, log.info)
      r <- evaluate(evaluator, target, watch)
    } yield {
      r
    }) match {
      case Left(err) =>
        log.error(err.getMessage)
        1
      case Right(n) =>
        n
    }
  }

  case class Config(home: ammonite.ops.Path = pwd/'out/'ammonite,
                    colored: Option[Boolean] = None,
                    help: Boolean = false,
                    repl: Boolean = false,
                    watch: Boolean = false)

  def main(args: Array[String]): Unit = {
    val startTime = System.currentTimeMillis()


    import ammonite.main.Cli.Arg
    val signature = Seq(
      Arg[Config, Path](
        "home", Some('h'),
        "The home directory of the REPL; where it looks for config and caches",
        (c, v) => c.copy(home = v)
      ),
      Arg[Config, Unit](
        "help", None,
        """Print this message""".stripMargin,
        (c, v) => c.copy(help = true)
      ),
      Arg[Config, Boolean](
        "color", None,
        """Enable or disable colored output; by default colors are enabled
          |in both REPL and scripts if the console is interactive, and disabled
          |otherwise""".stripMargin,
        (c, v) => c.copy(colored = Some(v))
      ),
      Arg[Config, Unit](
        "repl", Some('r'),
        "Open a build REPL",
        (c, v) => c.copy(repl = true)
      ),
      Arg[Config, Unit](
        "watch", Some('w'),
        "Watch and re-run your build when it changes",
        (c, v) => c.copy(watch = true)
      )
    )

    ammonite.main.Cli.groupArgs(args.toList, signature, Config()) match{
      case Left(err) =>
      case Right((config, leftover)) =>
        if (config.help) {
          val leftMargin = signature.map(ammonite.main.Cli.showArg(_).length).max + 2
          System.err.println(ammonite.main.Cli.formatBlock(signature, leftMargin).mkString("\n"))
          System.exit(0)
        } else {
          val res = new Main(config).run(leftover, startTime)
          System.exit(res)
        }
    }
  }
}

class Logger(coloredOutput: Boolean){
  val colors =
    if(coloredOutput) Colors.Default
    else Colors.BlackWhite

  def info(s: String) = System.err.println(colors.info()(s))
  def error(s: String) = System.err.println(colors.error()(s))
}
class Main(config: Main.Config){
  val coloredOutput = config.colored.getOrElse(ammonite.Main.isInteractive())
  val log = new Logger(coloredOutput)


  def watchAndWait(watched: Seq[(Path, Long)]) = {
    log.info(s"Watching for changes to ${watched.length} files... (Ctrl-C to exit)")
    def statAll() = watched.forall{ case (file, lastMTime) =>
      Interpreter.pathSignature(file) == lastMTime
    }

    while(statAll()) Thread.sleep(100)
  }

  def handleWatchRes[T](res: Res[T], printing: Boolean) = res match {
    case Res.Failure(msg) =>
      log.error(msg)
      false

    case Res.Exception(ex, s) =>
      log.error(
        Repl.showException(ex, fansi.Color.Red, fansi.Attr.Reset, fansi.Color.Green)
      )
      false

    case Res.Success(value) =>
      if (printing && value != ()) println(pprint.PPrinter.BlackWhite(value))
      true

    case Res.Skip   => true // do nothing on success, everything's already happened
    case Res.Exit(_) => ???
  }

  def run(leftover: List[String], startTime0: Long): Int = {

    var exitCode = 0
    var startTime = startTime0
    val loop = config.watch

    do {
      val watchedFiles = if (config.repl) {
        val repl = ammonite.Main(
          predefFile = Some(pwd / "build.sc")
        ).instantiateRepl(remoteLogger = None).right.get
        repl.interp.initializePredef()
        repl.run()
        repl.interp.watchedFiles
      } else {
        val interp = ammonite.Main(
          predefFile = Some(pwd / "build.sc")
        ).instantiateInterpreter().right.get

        interp.initializePredef()
        val syntheticPath = pwd / 'out / "run.sc"
        write.over(
          syntheticPath,
          s"""@main def run(args: String*) = mill.Main(args, ammonite.predef.FilePredef, interp.watch, $coloredOutput)
            |
            |@main def idea() = mill.scalaplugin.GenIdea(ammonite.predef.FilePredef)
          """.stripMargin
        )

        val res = ammonite.main.Scripts.runScript(
          pwd,
          syntheticPath,
          interp,
          Scripts.groupArgs(leftover)
        )
        res match{
          case Res.Success(v: Int) => exitCode = v
          case _ => exitCode = 1
        }

        handleWatchRes(res, false)
        interp.watchedFiles
      }

      val delta = System.currentTimeMillis() - startTime
      log.info("Finished in " + delta/1000.0 + "s")
      if (loop) watchAndWait(watchedFiles)
      startTime = System.currentTimeMillis()
    } while(loop)
    exitCode
  }
}