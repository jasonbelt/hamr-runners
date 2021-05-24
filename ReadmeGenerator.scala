// #Sireum

package org.sireum.cli.hamr_runners

import org.sireum._
import org.sireum.Cli.HamrPlatform
import org.sireum.cli.HAMR
import org.sireum.hamr.act.util.PathUtil
import org.sireum.hamr.codegen.common.{CommonUtil, StringUtil}
import org.sireum.hamr.codegen.common.properties.PropertyUtil
import org.sireum.hamr.ir
import org.sireum.message.Reporter
import org.sireum.hamr.codegen.common.symbols.{SymbolResolver, SymbolTable}
import org.sireum.hamr.codegen.common.types.TypeResolver
import org.sireum.hamr.codegen.common.util.CodeGenConfig
import org.sireum.hamr.ir.{JSON => irJSON, MsgPack => irMsgPack}

@record class ReadmeGenerator(o: Cli.HamrCodeGenOption, reporter: Reporter) {

  val airFile: Os.Path = {
    val path = Os.path(o.args(0))
    assert(path.exists, s"${path} does not exist")
    path
  }

  val model: ir.Aadl = ReadmeGenerator.getModel(airFile, o.msgpack)

  val symbolTable: SymbolTable = ReadmeGenerator.getSymbolTable(model, HAMR.toCodeGenOptions(o))

  val camkesOutputDir: Os.Path = Os.path(o.camkesOutputDir.get)
  val slangOutputDir: Os.Path = Os.path(o.outputDir.get)

  val OPT_CAKEML_ASSEMBLIES_PRESENT: String = "-DCAKEML_ASSEMBLIES_PRESENT=ON"
  val OPT_USE_PRECONFIGURED_ROOTFS: String = "-DUSE_PRECONFIGURED_ROOTFS=ON"

  def build(): B = {

    val run_camkes: Os.Path = ReadmeGenerator.getRunCamkesScript(camkesOutputDir)

    assert(run_camkes.exists, s"${run_camkes} not found")

    var continue: B = T
    val transpileSel4: Os.Path = ReadmeGenerator.getTranspileSel4Script(slangOutputDir)
    if (transpileSel4.exists) {
      continue = ReadmeGenerator.run(ISZ(transpileSel4.value), reporter)
    }

    if (continue) {
      var args: ISZ[String] = ISZ(run_camkes.value, "-n")

      var camkesOptions: ISZ[String] = ISZ()
      if (symbolTable.hasCakeMLComponents()) {
        camkesOptions = camkesOptions :+ OPT_CAKEML_ASSEMBLIES_PRESENT
      }
      if (symbolTable.hasVM()) {
        camkesOptions = camkesOptions :+ OPT_USE_PRECONFIGURED_ROOTFS
      }
      if (camkesOptions.nonEmpty) {
        args = args :+ "-o" :+ st"""${(camkesOptions, ";")}""".render
      }

      continue = ReadmeGenerator.run(args, reporter)
    }

    return continue
  }

  def simulate(timeout: Z): ST = {
    val simulateScript: Os.Path = ReadmeGenerator.getCamkesSimulatePath(o, symbolTable)
    assert(simulateScript.exists, s"${simulateScript} does not exist")

    println(s"Simulating for ${timeout / 1000} seconds")

    val p = Os.proc(ISZ(simulateScript.value)).timeout(timeout)

    val results = p.at(simulateScript.up).run()
    cprint(F, results.out)
    cprint(T, results.err)

    ReadmeGenerator.run(ISZ("pkill", "qemu"), reporter)

    val output = ReadmeGenerator.parseOutput(results.out)
    assert(output.nonEmpty, "Expected output missing")

    return st"${output.get}"
  }

  def genLinuxRunInstructions(readmeDir: Os.Path,
                              osireumScript: Option[Os.Path],
                              outputDir: Os.Path,
                              outputCDir: Os.Path): ST = {
    val bin = outputCDir / "bin"
    assert(bin.exists, s"wheres ${bin}")

    val transpile = outputDir / "bin" / "transpile.sh"
    val compile = bin / "compile-linux.sh"
    val run = bin / "run-linux.sh"
    val stop = bin / "stop.sh"

    assert(transpile.exists)
    assert(compile.exists)
    assert(run.exists)
    assert(stop.exists)

    val osireumOrTranspile: ST =
      if (osireumScript.nonEmpty) st"./${readmeDir.relativize(osireumScript.get)}"
      else st"./${readmeDir.relativize(transpile)}"

    val ret: ST =
      st"""${osireumOrTranspile}
          |./${readmeDir.relativize(compile)}
          |./${readmeDir.relativize(run)}
          |./${readmeDir.relativize(stop)}"""
    return ret
  }

  def genRunInstructions(readmeDir: Os.Path,
                         osireumScript: Option[Os.Path]): ST = {

    val transpileSel4: Os.Path = ReadmeGenerator.getTranspileSel4Script(slangOutputDir)
    val runScript: Os.Path = ReadmeGenerator.getRunCamkesScript(camkesOutputDir)
    val cakeMlScript: Os.Path = transpileSel4.up / "compile-cakeml.sh"
    val caseArmVmSetupScript: Os.Path = runScript.up / Os.path(PathUtil.CAMKES_ARM_VM_SCRIPT_PATH).name

    val caseArmVmSetup: Option[ST] =
      if (caseArmVmSetupScript.exists) {
        Some(st"./${readmeDir.relativize(caseArmVmSetupScript)}")
      }
      else {
        None()
      }

    val cakeML: Option[ST] =
      if (cakeMlScript.exists) {
        Some(st"./${readmeDir.relativize(cakeMlScript)}")
      }
      else {
        None()
      }

    val transpile: Option[ST] =
      if (transpileSel4.exists && osireumScript.isEmpty) {
        Some(st"./${readmeDir.relativize(transpileSel4)}")
      }
      else {
        None()
      }

    var camkesOptions: ISZ[String] = ISZ()
    if (symbolTable.hasCakeMLComponents()) {
      camkesOptions = camkesOptions :+ OPT_CAKEML_ASSEMBLIES_PRESENT
    }
    if (symbolTable.hasVM()) {
      camkesOptions = camkesOptions :+ OPT_USE_PRECONFIGURED_ROOTFS
    }
    val _camkesOptions: Option[ST] =
      if (camkesOptions.nonEmpty) Some(st"""-o "${(camkesOptions, ";")}" """)
      else None()

    assert(runScript.exists, s"${runScript} not found")
    val runCamkes: ST = st"./${readmeDir.relativize(runScript)} ${_camkesOptions}-s"

    val osireum: Option[ST] =
      if (osireumScript.nonEmpty) Some(st"./${readmeDir.relativize(osireumScript.get)}")
      else None()

    val ret: ST =
      st"""${osireum}
          |${caseArmVmSetup}
          |${cakeML}
          |${transpile}
          |${runCamkes}"""
    return ret
  }

  def getCamkesArchDiagram(format: DotFormat.Type): Option[Os.Path] = {
    val dot = ReadmeGenerator.getCamkesSimulatePath(o, symbolTable).up / "graph.dot"
    val outputPath = Os.path(o.aadlRootDir.get) / "diagrams" / s"CAmkES-arch-${o.platform.string}.${format.string}"
    ReadmeGenerator.renderDot(dot, outputPath, format, reporter)

    if(outputPath.exists) {
      return Some(outputPath)
    } else {
      cprintln(T, s"${outputPath} does not exist")
      return None()
    }
  }

  def getHamrCamkesArchDiagram(format: DotFormat.Type): Option[Os.Path] = {
    val dot = Os.path(o.camkesOutputDir.get) / "graph.dot"
    val outputPath = Os.path(o.aadlRootDir.get) / "diagrams" / s"CAmkES-HAMR-arch-${o.platform.string}.${format.string}"
    assert(dot.exists, s"${dot} does not exist")
    ReadmeGenerator.renderDot(dot, outputPath, format, reporter)
    if(outputPath.exists) {
      return Some(outputPath)
    } else {
      cprintln(T, s"${outputPath} does not exist")
      return None()
    }
  }

  def getAadlArchDiagram(): Option[Os.Path] = {
    val x = Os.path(o.aadlRootDir.get) / "diagrams" / "aadl-arch.png"
    if (x.exists) {
      return Some(x)
    } else {
      cprintln(T, s"${x} does not exist")
      return None()
    }
  }
}

@enum object RunType {
  'no
  'build
  'build_simulate
}

@enum object DotFormat {
  'svg
  'png
  'gif
  'pdf
}

@sig trait Level {
  def title: String
  def tag: String
}

@datatype  class SubLevel(val title: String,
                          val tag: String,
                          content: Option[ST],
                          subs: ISZ[Level]) extends Level

@datatype class ContentLevel(val title: String,
                             val tag: String,
                             content: ST) extends Level

object ReadmeTemplate {

  var existingReadmeContents: ops.StringOps = ops.StringOps("")
  var replaceExampleOutputSections: B = F

  def generateArchitectureSection(value: HashSMap[Cli.HamrPlatform.Type, Report]): Level = {
    var content: ST = st""
    val cand = value.values.filter(p => p.aadlArchDiagram.nonEmpty)
    if(cand.nonEmpty) {
      val report = cand(0)
      val rootDir = report.readmeDir
      val rel = rootDir.relativize(cand(0).aadlArchDiagram.get)
      val link = createHyperLink("AADL Arch", rel.value)
      content = st"!${link}"

      if(report.symbolTable.nonEmpty){
        val st = report.symbolTable.get

        var systemProps = st"""|System Properties|
                              ||--|
                              ||Domain Scheduling|"""

        if(st.rootSystem.getUseRawConnection()) {
          systemProps =
            st"""${systemProps}
                ||Wire Protocol|"""
        }
        content =
          st"""${content}
              |$systemProps"""

        for(thread <- st.getThreads()) {
          val name = thread.identifier
          val typ: String =
            if(CommonUtil.isPeriodic(thread.component)) s"Periodic: ${thread.period.get} ms"
            else "Sporadic"

          val compType: String =
            if(thread.isCakeMLComponent()) "CakeML"
            else if(thread.getParent(st).toVirtualMachine()) "Virtual Machine"
            else "Native"

          content =
            st"""${content}
                 |
                 ||${name} Properties|
                 ||--|
                 ||${typ}|
                 ||${compType}|
                 |
                 |"""
        }
      }
    }
    val title = "AADL Architecture"
    return ContentLevel(title, createTag(title), content)
  }

  def hackyFind(dir: Os.Path, suffix: String): Option[Os.Path] = {
    assert(dir.isDir, s"${dir}")
    for(f <- dir.list if f.isFile) {
      if (ops.StringOps(f.name).endsWith(suffix)) {
        return Some(f)
      }
    }
    for(subdir <- dir.list if subdir.isDir) {
      hackyFind(subdir, suffix) match {
        case Some(matchy) => return Some(matchy)
        case _ =>
      }
    }
    return None()
  }

  def generatePlatformSections(reports: HashSMap[HamrPlatform.Type, Report]): ISZ[Level] = {
    var subLevels: ISZ[Level] = ISZ()
    for(e <- reports.entries){
      val platform = e._1
      val report = e._2

      var subs: ISZ[Level] = ISZ()

      val configuration: ST =
        if(report.runHamrScript.nonEmpty) {
          val rel = report.readmeDir.relativize(report.runHamrScript.get)
          st"refer to [${rel}](${rel})"
        } else {
          val packageNameOption: Option[ST] =
            if(report.options.packageName.nonEmpty) { Some(st"| package-name | ${report.options.packageName.get} |")}
            else { None() }
          st"""|HAMR Codegen Configuration| |
               ||--|--|
               ||${packageNameOption}
               || exclude-component-impl | ${report.options.excludeComponentImpl} |
               || bit-width | ${report.options.bitWidth} |
               || max-string-size | ${report.options.maxStringSize} |
               || max-array-size | ${report.options.maxArraySize} |
               |"""
        }

      var title = s"HAMR Configuration: ${platform}"
      subs = subs :+ ContentLevel(title, createTag(title), configuration)

      if(report.symbolTable.nonEmpty) {
        val symtable =report.symbolTable.get
        title = s"Behavior Code: ${platform}"
        var locs: ISZ[ST] = ISZ()
        for(t <- symtable.getThreads()) {

          val id = t.identifier
          val suffix = s"${id}.c"

          platform match {
            case Cli.HamrPlatform.Linux =>
              val cdir = Os.path(report.options.slangOutputCDir.get) / "ext-c"
              val suffix = s"${id}.c"
              hackyFind(cdir, suffix) match {
                case Some(p) =>
                  locs = locs :+ createHyperLink(id, report.readmeDir.relativize(p).value)
                case _ => halt(s" didn't find ${suffix} in ${cdir}")
              }
            case Cli.HamrPlatform.SeL4 =>
              val cdir = report.options.slangOutputCDir.get
              val camkesDir = report.options.camkesOutputDir.get
              val isVM = t.getParent(symtable).toVirtualMachine()
              if(isVM) {
                val camkesDir = Os.path(report.options.camkesOutputDir.get) / "components" / "VM" / "apps"
                hackyFind(camkesDir, suffix) match {
                  case Some(p) =>
                    locs = locs :+ createHyperLink(s"$id (includes VM glue code)", report.readmeDir.relativize(p).value)
                  case _ => halt(s" didn't find ${suffix} in ${camkesDir}")
                }
              } else {
                val cdir = Os.path(report.options.slangOutputCDir.get) / "ext-c"
                hackyFind(cdir, suffix) match {
                  case Some(p) =>
                    locs = locs :+ createHyperLink(id, report.readmeDir.relativize(p).value)
                  case _ => halt(s" didn't find ${suffix} in $cdir")
                }
              }
            case _ => halt("NOOOO")
          }
        }

        subs = subs :+ ContentLevel(title, createTag(title),
          st"""${(locs.map(s => st"  * ${s}"), "\n\n")}"""
        )
      }


      title = s"How to Build/Run: ${platform}"
      subs = subs :+ ContentLevel(title, createTag(title),
        st"""```
            |${report.runInstructions}
            |```""")

      if(report.expectedOutput.nonEmpty) {
        title = s"Example Output: ${platform}"
        subs = subs :+ ContentLevel(title, createTag(title),
          st"""Timeout = ${report.timeout / 1000} seconds
              |```
              |${report.expectedOutput.get}
              |```""")
      }

      def toLevel(rootDir: Os.Path, p: Os.Path, title: String, tag: String) : Level = {
        assert(p.exists, s"no ${p}")
        val rel = rootDir.relativize(p)
        val l: ST = createHyperLink(title, s"${rel}")
        return ContentLevel(title, tag, st"!${l}")
      }

      if(report.camkesArchDiagram.nonEmpty) {
        title = s"CAmkES Architecture: ${platform}"
        subs = subs :+ toLevel(report.readmeDir, report.camkesArchDiagram.get, title, createTag(title))
      }

      if(report.hamrCamkesArchDiagram.nonEmpty) {
        title = s"HAMR CAmkES Architecture: ${platform}"
        subs = subs :+ toLevel(report.readmeDir, report.hamrCamkesArchDiagram.get, title, createTag(title))
      }

      val sl = SubLevel(
        title = platform.string,
        tag = platform.string,
        content = None(),
        subs = subs
      )

      subLevels = subLevels :+ sl
    }
    return subLevels
  }

  def expand(count: Z, c: C) : String = {
    var ret: ST = st""
    for(i <- 0 until count) { ret = st"${ret}${c}" }
    return ret.render
  }

  def createHyperLink(title: String, target: String): ST = {
    return st"[${title}](${target})"
  }

  def createTag(s: String): String = {
    val lc = StringUtil.toLowerCase(s)
    val d = StringUtil.replaceAll(lc, " ", "-")
    val cis = conversions.String.toCis(d)

    // only keep numbers, lowercase letters, '-' and '_'
    val cis_ = cis.filter(c =>
      (c.value >= 48 && c.value <= 57) || (c.value >= 97 && c.value <= 122) ||
      (c == '-') || (c == '_'))
    val d_ = conversions.String.fromCis(cis_)
    return d_
  }

  def toGithubLink(s: String): String = {
    return s"#${createTag(s)}"
  }

  def generateTOC(levels: ISZ[Level]): Level = {

    def gen(level: Level, levelNum: Z): ST = {
      val spaces = expand(levelNum, ' ')

      var ret = st"${spaces}* ${createHyperLink(level.title, toGithubLink(level.title))}"

      level match {
        case s: SubLevel =>
          val subs = s.subs.map(sub => gen(sub, levelNum + 2))
          ret = st"""${ret}
                    |${(subs,"\n")}"""
        case s: ContentLevel =>
      }

      return ret
    }

    val subs = levels.map(level => gen(level, 2))
    val content: ST = st"${(subs, "\n")}"

    var title = "Table of Contents"
    return ContentLevel(
      title = title,
      tag = createTag(title),
      content = content)
  }

  def wrapWithTag(tag: String, content: ST): ST = {
    val (start, end): (String, String) = (s"<!--${tag}_start-->", s"<!--${tag}_end-->")
    val ret: ST =
      if(content.render.size == 0) {
        st"${start}${end}"
      } else {
        st"""${start}
            |${content}
            |${end}"""
      }
    return ret
  }

  def replaceContent(tag: String, level: Level): Unit = {
    if(ops.StringOps(level.title).contains("Example Output") && !replaceExampleOutputSections) {
      return
    }

    val (start, end): (String, String) = (s"<!--${tag}_start-->", s"<!--${tag}_end-->")

    def doit(content: ST): Unit = {
      val posStart = existingReadmeContents.stringIndexOf(start) + start.size // include the tag
      if(posStart > 0) {
        val posEnd = existingReadmeContents.stringIndexOfFrom(end, posStart)
        assert(posEnd > 0, s"didn't find end tag ${end} -- postStart:${posStart}, posEnd:${posEnd} ${existingReadmeContents.substring(posStart, existingReadmeContents.size - 1)}")
        val prelude: String = existingReadmeContents.substring(0, posStart)
        val postlude: String = existingReadmeContents.substring(posEnd, existingReadmeContents.size)
        val newContent: String =
          st"""${prelude}
              |${content}
              |${postlude}""".render
        existingReadmeContents = ops.StringOps(newContent)
      } else {
        cprintln(T, s"Couldn't find tag ${start}")
      }
    }

    level match {
      case s: ContentLevel => doit(s.content)
      case s: SubLevel =>
        s.content match {
          case Some(content) => doit(content)
          case _ =>
        }
    }
  }

  def level2ST(level: Level, levelNum: Z, readmeExists: B): ST = {
    val hashes = expand(levelNum, '#')
    val title = s"${hashes} ${level.title}"

    val content: ST = level match {
      case s: ContentLevel => wrapWithTag(level.tag, s.content)
      case s: SubLevel => {
        val x = s.subs.map(sub => level2ST(sub, levelNum + 1, readmeExists))
        val levelContent = wrapWithTag(level.tag, st"${s.content}")
        st"""$levelContent
            |
            |${(x, "\n\n")}"""
      }
    }

    if(readmeExists) {
      replaceContent(level.tag, level)
    }

    val ret: ST =
        st"""${title}
            |${content}
            |"""

    return ret
  }

  def generateReport(title: String, reports: HashSMap[Cli.HamrPlatform.Type, Report]): ST = {

    val readmeExists: B = (reports.values(0).readmeDir / "readme.md").exists

    val archSection: Level = generateArchitectureSection(reports)

    val platformSections: ISZ[Level]= generatePlatformSections(reports)

    val toc: Level = generateTOC(archSection +: platformSections)


    val ret: ST = st"""# ${title}
                      |
                      |${level2ST(toc, 0, readmeExists)}
                      |
                      |${level2ST(archSection, 2, readmeExists)}
                      |
                      |${(platformSections.map(st => level2ST(st, 2, readmeExists)), "\n")}"""

    return ret
  }
}

object ReadmeGenerator {
  def run(args: ISZ[String], reporter: Reporter): B = {
    val results = Os.proc(args).console.runCheck()
    if(!results.ok) {
      reporter.error(None(), "", results.err)
    }
    return results.ok
  }

  def renderDot(dot: Os.Path, outputPath: Os.Path, format: DotFormat.Type, reporter: Reporter): B = {
    val args: ISZ[String] = ISZ("dot", s"-T${format.string}", dot.canon.value, "-o", outputPath.value)
    val results = Os.proc(args).console.run()
    if(!results.ok) {
      reporter.error(None(), "", results.err)
    }
    return results.ok
  }


  def parseOutput(out: String): Option[String] = {
    val o = ops.StringOps(out)
    val pos = o.stringIndexOf("Booting all finished")
    if(pos > 0) {
      return Some(o.substring(pos, o.size))
    } else {
      return Some("Didn't find 'Booting all finished'!")
    }
  }

  def getCamkesSimulatePath(o: Cli.HamrCodeGenOption, symbolTable: SymbolTable): Os.Path = {
    val camkesPath: Os.Path = ReadmeGenerator.getCamkesDir(symbolTable)
    assert(camkesPath.exists, s"${camkesPath} doesn't exist")

    val camkesOutputDir = Os.path(o.camkesOutputDir.get)

    return camkesPath / s"build_${camkesOutputDir.name}" / "simulate"
  }

  def getCamkesDir(symbolTable: SymbolTable): Os.Path = {
    val ret: String =
      if(symbolTable.hasVM()) "camkes-arm-vm"
      else "camkes"

    return Os.home / "CASE" / ret
  }

  def getTranspileSel4Script(slangOutputDir: Os.Path): Os.Path = {
    return slangOutputDir / "bin" / "transpile-sel4.sh"
  }

  def getRunCamkesScript(camkesOutputDir: Os.Path): Os.Path = {
    return camkesOutputDir / "bin" / "run-camkes.sh"
  }


  def getModel(inputFile: Os.Path, isMsgpack: B): ir.Aadl = {
    val input: String = if (inputFile.exists && inputFile.isFile) {
      inputFile.read
    } else {
      halt(s"AIR input file ${inputFile} not found")
    }

    val model: ir.Aadl = if (isMsgpack) {
      org.sireum.conversions.String.fromBase64(input) match {
        case Either.Left(u) =>
          irMsgPack.toAadl(u) match {
            case Either.Left(m) => m
            case Either.Right(m) =>
              halt(s"MsgPack deserialization error at offset ${m.offset}: ${m.message}")
          }
        case Either.Right(m) =>
          halt(m)
      }
    }
    else {
      irJSON.toAadl(input) match {
        case Either.Left(m) => m
        case Either.Right(m) =>
          halt(s"Json deserialization error at (${m.line}, ${m.column}): ${m.message}")
      }
    }
    return model
  }

  def getSymbolTable(model: ir.Aadl, options: CodeGenConfig): SymbolTable = {
    val reporter = Reporter.create

    val rawConnections: B = PropertyUtil.getUseRawConnection(model.components(0).properties)
    val aadlTypes = TypeResolver.processDataTypes(model, rawConnections, 256, 32, "")

    val s = SymbolResolver.resolve(model, aadlTypes, options, reporter)
    reporter.printMessages()
    return s
  }
}

@record class Report (readmeDir: Os.Path,

                      options: Cli.HamrCodeGenOption,
                      runHamrScript: Option[Os.Path],
                      timeout: Z,

                      runInstructions: ST,
                      expectedOutput: Option[ST],

                      aadlArchDiagram: Option[Os.Path],

                      hamrCamkesArchDiagram: Option[Os.Path],

                      camkesArchDiagram: Option[Os.Path],

                      symbolTable: Option[SymbolTable]
                     )