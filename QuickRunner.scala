// #Sireum
package org.sireum.cli.hamr_runners

import org.sireum._
import Cli.SireumHamrCodegenHamrPlatform._

@datatype class Project (val aadlDir : Os.Path,
                         val json: String,
                         val packageName: Option[String],
                         val platforms: ISZ[Cli.SireumHamrCodegenHamrPlatform.Type]) {
  def jsonLoc : Os.Path = { return aadlDir / ".slang" / json }
}

object QuickRunner extends App {

  val clearFiles: B = F

  val mult_thread_vm: Project = Project(
    aadlDir = Os.home / "CASE/Sireum/hamr/codegen/jvm/src/test/scala/models/CodeGenTest_Base/vm-with-multiple-threads/aadl",
    json = "model_m_impl_Instance.json",
    packageName = None(),
    platforms = ISZ(SeL4))

  val hardened: Project = Project (
    aadlDir = Os.home / "devel/case/case-loonwerks/CASE_Simple_Example_V4/Hardened",
    json = "MC_MissionComputer_Impl_Instance.json",
    packageName = None(),
    platforms = ISZ(JVM))

  val isolette: Project = Project (
    aadlDir = Os.home / "devel"/ "gumbo" / "isolette" / "aadl",
    json = "Isolette_isolette_single_sensor_Instance.json",
    packageName = Some("isolette"),
    platforms = ISZ(SeL4))

  val pingpong: Project = Project (
    aadlDir = Os.home / "devel/camkes-vm/camkes-ping-pong/ping-pong/aadl",
    json = "Ping_Pong_top_impl_Instance.json",
    packageName = Some("slang"),
    platforms = ISZ(SeL4))


  val building: Project = Project(
    aadlDir = Os.home / "temp/x/building-control-art-scheduling/aadl",
    json = "BuildingControl_BuildingControlDemo_i_Instance.json",
    packageName = None(),
    platforms = ISZ(JVM))

  val voter: Project = Project(
    aadlDir = Os.home / "devel/gumbo/gumbo-models/voter/RedundantSensors_Bless",
    json = "SensorSystem_redundant_sensors_impl_Instance.json",
    packageName = None(),
    platforms = ISZ(JVM))

  val aeic2020_tc: Project = Project (
    aadlDir = Os.home / "devel/aeic2002_tc_module/aadl",
    json = "TemperatureControl_TempControlSystem_i_Instance.json",
    packageName = None(),
    platforms = ISZ(JVM))

  val project: Project = pingpong

  val aadlDir: Os.Path = project.aadlDir
  val rootDir: Os.Path = aadlDir.up / "hamr"
  val outputDir: Os.Path = rootDir / "slang"
  val slangOutputCDir: Os.Path = rootDir / "c"
  val camkesOutputDir: Os.Path = rootDir / "camkes"

  def o(platform: Cli.SireumHamrCodegenHamrPlatform.Type): Cli.SireumHamrCodegenOption = {
    return Cli.SireumHamrCodegenOption(
      help = "",
      args = ISZ(project.jsonLoc.value),
      msgpack = F,
      verbose = T,
      platform = platform,

      packageName = project.packageName,
      noProyekIve = T,
      noEmbedArt = F,
      devicesAsThreads = F,
      excludeComponentImpl = T,

      bitWidth = 32,
      maxStringSize = 256,
      maxArraySize = 1,
      runTranspiler = F,

      slangAuxCodeDirs = ISZ(),
      slangOutputCDir = Some(slangOutputCDir.value),
      outputDir = Some(outputDir.value),

      camkesOutputDir = Some(camkesOutputDir.value),
      camkesAuxCodeDirs = ISZ(),
      aadlRootDir = Some(project.aadlDir.value),

      experimentalOptions = ISZ("PROCESS_BTS_NODES", "GENERATE_REFINEMENT_PROOF")
    )
  }

  override def main(args: ISZ[String]): Z = {
    assert(project.aadlDir.exists)
    assert(project.jsonLoc.exists)

    if(clearFiles) {
      ISZ(outputDir / "bin", outputDir / "src", outputDir / "build.sbt", outputDir / "build.sc", outputDir / "versions.properties").foreach(f => f.removeAll())

      slangOutputCDir.removeAll()

      camkesOutputDir.removeAll()
    }

    for(p <- project.platforms) {
      val exitCode = org.sireum.cli.HAMR.codeGen(o(p))
      if(exitCode != 0) {
        eprintln(s"Error while generating ${p} - ${exitCode}")
      } else {
        println(s"${aadlDir.name} ${p} completed with ${exitCode}")
      }
    }

    //proc"git checkout ${(outputDir / "src/main/bridge").string}".at(outputDir).console.runCheck()
    return 0
  }
}
