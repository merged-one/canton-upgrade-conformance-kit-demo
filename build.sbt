lazy val root = (project in file("."))
  .settings(
    name := "canton-conformance-kit",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := "2.13.14",
    organization := "com.digitalasset.conformance",

    // Assembly settings for fat JAR
    assembly / mainClass := Some("com.digitalasset.conformance.Main"),
    assembly / assemblyJarName := "canton-conformance-kit.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) =>
        xs match {
          case "MANIFEST.MF" :: Nil => MergeStrategy.discard
          case "services" :: _      => MergeStrategy.concat
          case _                    => MergeStrategy.discard
        }
      case "reference.conf" => MergeStrategy.concat
      case x if x.endsWith(".conf") => MergeStrategy.concat
      case _ => MergeStrategy.first
    },

    libraryDependencies ++= Seq(
      // CLI parsing
      "com.github.scopt"          %% "scopt"            % "4.1.0",
      // HTTP client
      "com.softwaremill.sttp.client3" %% "core"         % "3.9.3",
      // JSON
      "io.circe"                  %% "circe-core"       % "0.14.6",
      "io.circe"                  %% "circe-generic"    % "0.14.6",
      "io.circe"                  %% "circe-parser"     % "0.14.6",
      // YAML
      "org.yaml"                  %  "snakeyaml"        % "2.2",
      // Logging
      "org.slf4j"                 %  "slf4j-simple"     % "2.0.11",
      // Testing
      "org.scalatest"             %% "scalatest"        % "3.2.17" % Test
    ),

    // Compiler options
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Xlint",
      "-Ywarn-dead-code"
    ),

    // Fork when running to avoid SBT classloader issues
    run / fork := true
  )
