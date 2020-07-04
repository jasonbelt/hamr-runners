package org.sireum.cli.hamr_runners

import org.sireum._
import org.sireum.cli._

object Util {
  val o = Cli.HamrCodeGenOption(
    help = "",
    args = ISZ(),
    json = T,
    verbose = T,
    platform = Cli.HamrPlatform.Linux,
    outputDir = None(),
    packageName = None(),
    embedArt = T,
    devicesAsThreads = F,
    slangAuxCodeDirs = ISZ(),
    slangOutputCDir = None(),
    excludeComponentImpl = T,
    bitWidth = 32,
    maxStringSize = 256,
    maxArraySize = 16,
    runTranspiler = T,

    camkesOutputDir = None(),
    camkesAuxCodeDirs = ISZ(),
    aadlRootDir = None()
  )
}
