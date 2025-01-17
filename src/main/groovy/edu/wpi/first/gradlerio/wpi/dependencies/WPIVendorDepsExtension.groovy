package edu.wpi.first.gradlerio.wpi.dependencies

import edu.wpi.first.gradlerio.wpi.WPIExtension
import edu.wpi.first.gradlerio.wpi.WPIMavenRepo
import edu.wpi.first.toolchain.NativePlatforms
import groovy.json.JsonSlurper
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import jaci.gradle.log.ETLogger
import jaci.gradle.log.ETLoggerFactory
import jaci.gradle.nativedeps.DelegatedDependencySet
import jaci.gradle.nativedeps.DependencySpecExtension
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.nativeplatform.NativeBinarySpec
import org.gradle.platform.base.VariantComponentSpec

@CompileStatic
public class WPIVendorDepsExtension {

    final WPIDepsExtension wpiDeps
    final WPIExtension wpiExt

    List<JsonDependency> dependencies = []
    final List<DelegatedDependencySet> nativeDependenciesList = []

    final File vendorFolder;

    private final ETLogger log;
    private final JsonSlurper slurper;

    public static final String VENDORDEPS_FOLDER_NAME = 'vendordeps'

    WPIVendorDepsExtension(WPIDepsExtension wpiDeps, WPIExtension wpiExt) {
        this.wpiDeps = wpiDeps
        this.wpiExt = wpiExt
        this.vendorFolder = wpiDeps.wpi.project.file(VENDORDEPS_FOLDER_NAME)
        this.log = ETLoggerFactory.INSTANCE.create('WPIVendorDeps')
        this.slurper = new JsonSlurper()
    }

    @CompileDynamic
    JsonDependency parseSlurped(Object slurped) {
        return new JsonDependency(slurped)
    }

    static List<File> vendorFiles(File directory) {
        if (directory.exists()) {
            return directory.listFiles(new FileFilter() {
                @Override
                boolean accept(File pathname) {
                    return pathname.name.endsWith(".json")
                }
            }) as List<File>
        } else
            return []
    }

    void loadAll() {
        loadFrom(vendorFolder)
    }

    void loadFrom(File directory) {
        vendorFiles(directory).each { File f ->
            def dep = parse(f)
            if (dep != null)
                load(dep)
        }
    }

    void loadFrom(Project project) {
        loadFrom(project.file(VENDORDEPS_FOLDER_NAME))
    }

    JsonDependency parse(File f) {
        JsonDependency dep = null
        f.withReader {
            try {
                dep = parseSlurped(slurper.parse(it))
            } catch (ex) {
                log.logError("Malformed Vendor Deps File: ${f.toString()}")
            }
        }
        return dep
    }

    void load(JsonDependency dep) {
        // Don't double-add a dependency!
        if (dependencies.find { it.uuid.equals(dep.uuid) } == null) {
            dependencies.add(dep)

            if (dep != null && dep.mavenUrls != null) {
                int i = 0
                dep.mavenUrls.each { url ->
                    // Only add if the maven doesn't yet exist.
                    if (wpiExt.maven.find { it.release.equals(url) } == null) {
                        def name = "${dep.uuid}_${i++}"
                        log.info("Registering vendor dep maven: $name on project ${wpiExt.project.path}")
                        wpiExt.maven.vendor(name) { WPIMavenRepo repo ->
                            repo.release = url
                        }
                    }
                }
            }
        }
    }

    static String getVersion(String inputVersion, WPIExtension wpiExt) {
        return inputVersion == 'wpilib' ? wpiExt.wpilibVersion : inputVersion
    }

    List<String> java(String... ignore) {
        if (dependencies == null) return []

        return dependencies.findAll { !isIgnored(ignore, it) }.collectMany { JsonDependency dep ->
            dep.javaDependencies.collect { JavaArtifact art ->
                "${art.groupId}:${art.artifactId}:${getVersion(art.version, wpiExt)}".toString()
            } as List<String>
        }
    }

    List<String> jni(String platform, String... ignore) {
        return jniInternal(false, platform, ignore)
    }

    List<String> jniDebug(String platform, String... ignore) {
        return jniInternal(true, platform, ignore)
    }

    List<String> jniInternal(boolean debug, String platform, String... ignore) {
        if (dependencies == null) return []

        def deps = [] as List<String>

        dependencies.each { JsonDependency dep ->
            if (!isIgnored(ignore, dep)) {
                dep.jniDependencies.each { JniArtifact jni ->
                    boolean applies = jni.validPlatforms.contains(platform)
                    if (!applies && !jni.skipInvalidPlatforms)
                        throw new WPIDependenciesPlugin.MissingJniDependencyException(dep.name, platform, jni)

                    if (applies) {
                        def debugString = debug ? "debug" : ""
                        deps.add("${jni.groupId}:${jni.artifactId}:${getVersion(jni.version, wpiExt)}:${platform}${debugString}@${jni.isJar ? 'jar' : 'zip'}".toString())
                    }
                }
            }
        }

        return deps
    }

    void cpp(Object scope, String... ignore) {
        def dse = wpiDeps.wpi.project.extensions.getByType(DependencySpecExtension)
        if (scope in VariantComponentSpec) {
            ((VariantComponentSpec)scope).binaries.withType(NativeBinarySpec).all { NativeBinarySpec bin ->
                cppVendorLibForBin(dse, bin, ignore)
            }
        } else if (scope in NativeBinarySpec) {
            cppVendorLibForBin(dse, (NativeBinarySpec)scope, ignore)
        } else {
            throw new GradleException('Unknown type for useVendorLibraries target. You put this declaration in a weird place.')
        }
    }

    private void cppVendorLibForBin(DependencySpecExtension dse, NativeBinarySpec bin, String[] ignore) {
        Set<DelegatedDependencySet> dds = []
        dependencies.each { JsonDependency dep ->
            if (!isIgnored(ignore, dep)) {
                dep.cppDependencies.each { CppArtifact cpp ->
                    if (cpp.headerClassifier != null)
                        dds.add(new DelegatedDependencySet(dep.uuid + cpp.libName + "_headers", bin, dse, cpp.skipInvalidPlatforms))
                    if (cpp.sourcesClassifier != null)
                        dds.add(new DelegatedDependencySet(dep.uuid + cpp.libName + "_sources", bin, dse, cpp.skipInvalidPlatforms))
                    if (cpp.binaryPlatforms != null && cpp.binaryPlatforms.length > 0)
                        dds.add(new DelegatedDependencySet(dep.uuid + cpp.libName + "_binaries", bin, dse, cpp.skipInvalidPlatforms))
                }
            }
        }

        dds.each { DelegatedDependencySet set ->
            bin.lib(set)
        }
    }

    private boolean isIgnored(String[] ignore, JsonDependency dep) {
        return ignore.find { it.equals(dep.name) || it.equals(dep.uuid) } != null
    }

    static class JavaArtifact {
        String groupId
        String artifactId
        String version
    }

    static class JniArtifact {
        String groupId
        String artifactId
        String version

        boolean isJar

        String[] validPlatforms
        boolean skipInvalidPlatforms
    }

    static class CppArtifact {
        String groupId
        String artifactId
        String version
        String libName
        String configuration

        String headerClassifier
        String sourcesClassifier
        String[] binaryPlatforms
        boolean skipInvalidPlatforms

        boolean sharedLibrary
    }

    static class JsonDependency {
        String name
        String version
        String uuid
        String[] mavenUrls
        String jsonUrl
        String fileName
        JavaArtifact[] javaDependencies
        JniArtifact[] jniDependencies
        CppArtifact[] cppDependencies
    }

}
