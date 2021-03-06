package com.saantiaguilera.gradle.publish.helper

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.bundling.Jar

/**
 * Created by saantiaguilera on 6/22/17.
 */
public class PublishHelperPlugin implements Plugin<Project> {

    public static final String EXTENSION_PUBLISH_CONFIGURATIONS = "publishConfigurations"
    public static final String EXTENSION_PUBLISH_GLOBAL_CONFIGURATIONS = "publishGlobalConfigurations"

    public static final String TYPE_JAR = 'jar'
    public static final String TYPE_AAR = 'aar'

    public static final String ANDROID_LIBRARY_PLUGIN_ID = "com.android.library"

    public static final String PUBLISH_ROOT_TASK = 'publishModules'
    public static final String PUBLISH_MODULE_TASK = 'publishModule'

    public PublishGlobalConfigurations globalConfigurations

    public String rootProjectName

    @Override
    void apply(Project project) {
        rootProjectName = project.name

        project.extensions.create(EXTENSION_PUBLISH_GLOBAL_CONFIGURATIONS, PublishGlobalConfigurations)
        globalConfigurations = project.publishGlobalConfigurations
        project.subprojects { subproject ->
            subproject.extensions.create(EXTENSION_PUBLISH_CONFIGURATIONS, PublishConfigurations)

            subproject.afterEvaluate {
                subproject.configurations {
                    archives {
                        extendsFrom subproject.configurations.default
                    }
                }
            }

            plugins.withType(JavaPlugin) {
                configure(subproject, TYPE_JAR)
                configureJava(subproject)
            }

            plugins.withId(ANDROID_LIBRARY_PLUGIN_ID) {
                configure(subproject, TYPE_AAR)
                configureAndroid(subproject)
            }
        }

        project.afterEvaluate {
            project.task(PUBLISH_ROOT_TASK) { Task task ->
                task.description 'Publish all the modules ordered and specified by publishGlobalConfigurations { publishOrder }'
                if (project.gradle.startParameter.taskNames.toListString().contains("publishModules") &&
                        globalConfigurations.publishOrder && !globalConfigurations.publishOrder.isEmpty()) {
                    def order = []
                    globalConfigurations.publishOrder.each { String moduleName ->
                        order.add(project.subprojects.find { it.name == moduleName }.tasks.publishModule)
                    }
                    for (int i = order.size() - 1; i >= 0; i--) {
                        if (i - 1 >= 0) {
                            order[i].dependsOn order[i - 1]
                        }
                    }
                    task.dependsOn order[order.size() - 1]
                }
            }
        }
    }

    def configure(Project proj, String packagingType) {
        proj.apply plugin: 'maven'
        proj.apply plugin: 'com.jfrog.bintray'

        ConfigurationHelper configHelper = new ConfigurationHelper(globalConfigurations, proj)

        proj.afterEvaluate {
            proj.uploadArchives {
                repositories {
                    mavenDeployer {
                        repository(url: "file://${System.properties['user.home']}/.m2/repository")

                        pom {
                            version = configHelper.version
                            artifactId = configHelper.artifact
                            groupId = configHelper.group
                            packaging = packagingType

                            project {
                                licenses {
                                    license {
                                        name configHelper.licenseName
                                        url configHelper.licenseUrl
                                        distribution 'repo'
                                    }
                                }
                                packaging packagingType
                                url configHelper.url
                            }

                            generatedDependencies.findAll {
                                    (it.version == 'unspecified' || it.version == 'undefined') &&
                                    it.groupId == rootProjectName }.each { dep ->
                                dep.version = configHelper.version
                                dep.groupId = configHelper.group

                                if (configHelper.getLocalArtifact(dep.artifactId)) {
                                    dep.artifactId = configHelper.getLocalArtifact(dep.artifactId)
                                }
                            }
                        }
                    }
                }
            }
        }

        proj.task(PUBLISH_MODULE_TASK) {
            description 'Publishes a new release version of the module to Bintray.'
            finalizedBy 'bintrayUpload'
            doLast {
                proj.group = configHelper.group
                proj.version = configHelper.version

                proj.bintrayUpload {
                    repoName = configHelper.bintrayRepositoryName
                    packageVcsUrl =
                            "${configHelper.url}/releases/tag/v${proj.version}"
                    versionVcsTag = "v${proj.version}"
                    user = configHelper.bintrayUser
                    apiKey = configHelper.bintrayApiKey
                    dryRun = false
                    publish = true
                    configurations = ['archives']
                    packageName = "${configHelper.group}.${configHelper.artifact}"
                    packageIssueTrackerUrl = "${configHelper.url}/issues"
                    packageWebsiteUrl = configHelper.url
                    versionName = "${proj.version}"
                    packagePublicDownloadNumbers = false
                    packageLicenses = [ configHelper.licenseName ]
                }

                proj.pom {
                    version = configHelper.version
                    artifactId = configHelper.artifact
                    groupId = configHelper.group
                    packaging = packagingType

                    project {
                        licenses {
                            license {
                                name configHelper.licenseName
                                url configHelper.licenseUrl
                                distribution 'repo'
                            }
                        }
                        packaging packagingType
                        url configHelper.url
                    }

                    generatedDependencies.findAll {
                            (it.version == 'unspecified' || it.version == 'undefined') &&
                            it.groupId == rootProjectName }.each { dep ->
                        dep.version = configHelper.version
                        dep.groupId = configHelper.group

                        if (configHelper.getLocalArtifact(dep.artifactId)) {
                            dep.artifactId = configHelper.getLocalArtifact(dep.artifactId)
                        }
                    }
                }.writeTo("build/poms/pom-default.xml")

                proj.file("$proj.buildDir/libs/${proj.name}-sources.jar")
                        .renameTo("$proj.buildDir/libs/${proj.name}-${proj.version}-sources.jar")

                proj.configurations.archives.artifacts.clear()

                addArchives(packagingType, proj)

                println "Publishing: ${String.format("%s:%s:%s", proj.group, proj.name, proj.version)}"
            }
        }

        proj.tasks.uploadArchives.dependsOn.clear()
    }

    def configureJava(Project project) {
        def sourcesJarTask = project.tasks.create "sourcesJar", Jar
        sourcesJarTask.dependsOn project.tasks.getByName("compileJava")
        sourcesJarTask.classifier = 'sources'
        sourcesJarTask.from project.tasks.getByName("compileJava").source

        project.tasks.publishModule.dependsOn 'assemble', 'test', 'check', 'sourcesJar'
    }

    def configureAndroid(Project project) {
        project.apply plugin: 'com.github.dcendents.android-maven'

        def sourcesJarTask = project.tasks.create "sourcesJar", Jar
        sourcesJarTask.classifier = 'sources'
        sourcesJarTask.from project.android.sourceSets.main.java.srcDirs

        project.tasks.publishModule.dependsOn 'assembleRelease', 'testReleaseUnitTest', 'check', 'sourcesJar'
    }

    def addArchives(String packagingType,
                    Project project) {
        ConfigurationHelper configHelper = new ConfigurationHelper(globalConfigurations, project)
        switch (packagingType) {
            case TYPE_JAR:
                def jarParentDirectory = "$project.buildDir/libs/"
                def prevFile = project.file(jarParentDirectory + "${project.name}.jar");
                def actualFile = project.file(jarParentDirectory + "${configHelper.artifact}-${configHelper.version}.jar")

                if (prevFile.exists() && prevFile.path != actualFile.path) {
                    if (actualFile.exists()) {
                        actualFile.delete()
                    }
                    actualFile << prevFile.bytes
                }

                project.artifacts.add('archives', actualFile)
                break;
            case TYPE_AAR:
                def aarParentDirectory = "$project.buildDir/outputs/aar/"
                File aarFile = project.file(aarParentDirectory + "${project.name}-release.aar")
                File actualFile = project.file(aarParentDirectory + "${configHelper.artifact}.aar")

                if (aarFile.exists() != aarFile.path != actualFile.path) {
                    if (actualFile.exists()) {
                        actualFile.delete()
                    }
                    actualFile << aarFile.bytes
                }

                project.artifacts.add('archives', actualFile)
                break;
        }

        project.artifacts.add('archives', project.tasks['sourcesJar'])
        if (project.tasks.findByName('javadocJar')) {
            project.artifacts.add('archives', project.tasks.javadocJar)
        }
    }

}
