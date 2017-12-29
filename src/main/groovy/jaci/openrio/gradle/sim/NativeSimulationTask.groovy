package jaci.openrio.gradle.sim

import groovy.transform.CompileStatic
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.util.PatternFilterable
import org.gradle.internal.os.OperatingSystem
import org.gradle.nativeplatform.NativeExecutableBinarySpec
import org.gradle.nativeplatform.tasks.InstallExecutable
import org.gradle.process.ExecSpec

@CompileStatic
class NativeSimulationTask extends DefaultTask {

    NativeExecutableBinarySpec binary

    @TaskAction
    void run() {
        // TODO: Spawn in new window (gradle daemon keeps this process alive)
        // OR: Write a script that does this for the user?
        //          i.e. task outputs a runnable file that the user then uses.
        def installTask = binary.tasks.withType(InstallExecutable).first()
        def env = SimulationPlugin.getHALExtensionsEnvVar(project)
        println "Using Environment: HALSIM_EXTENSIONS=${env}"
        project.exec { ExecSpec spec ->
            spec.environment.put("HALSIM_EXTENSIONS", env)
            spec.commandLine(installTask.runScript)

        }
    }

}