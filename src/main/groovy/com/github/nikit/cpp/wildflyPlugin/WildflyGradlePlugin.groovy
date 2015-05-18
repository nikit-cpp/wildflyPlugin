package com.github.nikit.cpp.wildflyPlugin

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.artifacts.UnknownConfigurationException
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.tasks.bundling.Jar
import org.gradle.util.GFileUtils
import org.gradle.api.Task

/**
 * Created by nik on 12.05.15.
 */
class WildflyGradlePlugin implements Plugin<Project> {
    File buildDir
    File dependencyWorkspace
    Project projectInstance
    String extencionName = 'wildfly'
    boolean isDeploy // TODO будет убрано
    static int dependencyNumber
    // Упорядоченное хранилище зависимостей, предназначенное для предотвращения дублирования
    List<ResolvedDependency> cachedDependencies


    String confNameCompile = JavaPlugin.COMPILE_CONFIGURATION_NAME
    String confNameProvided = 'providedCompile'

    String JAR_TASK = JavaPlugin.JAR_TASK_NAME
    String AFTER_MAKE_DEPLOYMENTS_TASK = 'assemble'

    // own tasks
    String MAKE_DEPLOYMENTS_TASK = 'makeDeployments'
    String DEPLOY_DEPLOYMENTS_TASK = 'deployDeployments'

    void apply(Project project) {
        cachedDependencies = new ArrayList<>()
        projectInstance = project

        buildDir = project.buildDir
        dependencyWorkspace = new File(buildDir, 'dependency-workspace')
        dependencyWorkspace.mkdirs()

        try {
            project.configurations[confNameProvided]
        } catch (UnknownConfigurationException e) {
            // добавляем providedCompile конфигурацию, если она не существует
            addConfiguration(project.configurations, confNameProvided);
        }

        println "WildFly deployments will be stored in '" + dependencyWorkspace + "'"

        project.extensions.create(extencionName, WildflyPluginExtension)



        Task makeDeployments = project.task(MAKE_DEPLOYMENTS_TASK) << {
            isDeploy = false
            processChildDependencies(getRootDependencies(), 0);

            println "Deployment's jars must be deployed in the following order:"
            processCachedDependencies();

        }
        projectInstance.tasks[AFTER_MAKE_DEPLOYMENTS_TASK].dependsOn(makeDeployments)

        Task deployDeployments = project.task(DEPLOY_DEPLOYMENTS_TASK) << {
            isDeploy = true
            processChildDependencies(getRootDependencies(), 0);

            println "Deployment's jars will be deployed in the following order:"
            processCachedDependencies();

        }
        deployDeployments.doLast {
            println 'deployDeployments.doLast'
        }

        projectInstance.tasks[JAR_TASK].doFirst {
            Jar jarTask = it;
            Set<ResolvedDependency> firstLevelDependencies = getRootDependencies()
            String depStr = makeDependenciesString(firstLevelDependencies)
            if(projectInstance.wildfly.addFirstLevelDependenciesToManifest) {
                println "First level dependencies '${depStr}' added to manifest."
                jarTask.manifest.attributes.put("Dependencies", depStr);
            }
        }

    }

    private Configuration addConfiguration(ConfigurationContainer configurations, String name) {
        // https://github.com/gradle/gradle/blob/master/subprojects/plugins/src/main/groovy/org/gradle/api/plugins/WarPlugin.java
        // https://github.com/spring-projects/gradle-plugins/blob/master/propdeps-plugin/src/main/groovy/org/springframework/build/gradle/propdep/PropDepsPlugin.groovy
        Configuration compile = configurations.getByName(JavaPlugin.COMPILE_CONFIGURATION_NAME)
        Configuration configuration = configurations.create(name)
        compile.extendsFrom(configuration)
        configuration.visible = false
        configuration.transitive = false

        return configuration
    }




    /**
     * Последовательно обрабатывает все зависимости cachedDependencies
     */
    void processCachedDependencies() {
        for (ResolvedDependency dep : cachedDependencies) {
            File changedJar = createDeploymentInDependencyWorkspace(dep)
            if (isDeploy) {
                deployDeployment(changedJar)
            }
            if (projectInstance.wildfly.printOrder) {
                printDeployment(changedJar)
            }
        }
    }

    /**
     * Получает зависимости самого первого уровня, убирает из них providedCompile
     * @return
     */
    Set<ResolvedDependency> getRootDependencies() {
        Configuration confCompile = projectInstance.configurations[confNameCompile]
        Configuration confProvided = projectInstance.configurations[confNameProvided]
        Set<ResolvedDependency> compileDependencies = confCompile.resolvedConfiguration.firstLevelModuleDependencies
        Set<ResolvedDependency> providedDependencies = confProvided.resolvedConfiguration.firstLevelModuleDependencies
        compileDependencies.removeAll(providedDependencies)
        return compileDependencies
    }

    Set<ResolvedDependency> removeProvidedDependencies(Set<ResolvedDependency> input) {
        Configuration confProvided = projectInstance.configurations[confNameProvided]
        Set<ResolvedDependency> providedDependencies = confProvided.resolvedConfiguration.firstLevelModuleDependencies
        Iterator iterator = input.iterator()
        while(iterator.hasNext()) {
            ResolvedDependency inp = iterator.next()
            for(ResolvedDependency rd: providedDependencies) {
                if (inp.moduleGroup==rd.moduleGroup && inp.moduleName == rd.moduleName && inp.moduleVersion == rd.moduleVersion) {
                    iterator.remove()
                }
            }
        }
        return input
    }

    /**
     * Основная функция, рекурсивно обрабатывающая дерево зависимостей.
     * Заполняет cachedDependencies
     * @param allDependencies
     * @param level
     */
    void processChildDependencies(Set<ResolvedDependency> allDependencies, int level) {
        level++

        for (ResolvedDependency dep : allDependencies) {
            preChildrenProcessed(dep, level)

            Set<ResolvedDependency> childrens = getChilderns(dep)

            childrens = removeProvidedDependencies(childrens)

            // спускаемся на нижние уровни рекурсии
            processChildDependencies(childrens, level);

            postChildrenProcessed(dep)
        }

    }

    private void preChildrenProcessed(ResolvedDependency dep, int level) {
        if(projectInstance.wildfly.printTree){
            printNodeDependency(dep, level)
        }
    }

    private void postChildrenProcessed(ResolvedDependency dep) {
        if (!cachedDependencies.contains(dep)) {
            cachedDependencies.add(dep)
        }
    }

    /**
     * Получает потомков dep, удаляет всех дублирующихся потомков от других конфигураций
     * @param dep какая-то зависимость
     * @return множество её потомков
     */
    Set<ResolvedDependency> getChilderns(ResolvedDependency dep) {
        Set<ResolvedDependency> childrens = new HashSet<ResolvedDependency>();
        dep.children.each {
            if (it.configuration == confNameCompile) {
                childrens.add(it)
            }
        }

        return childrens
    }

    void printNodeDependency(ResolvedDependency dep, int level) {
        File jarSrc = getJarFromDependency(dep)
        String group = dep.module.id.group
        String name = dep.module.id.name
        String version = dep.module.id.version

        println " " + "*"*level + " id=${dep.module.id} conf=${dep.configuration} file=${jarSrc}"
    }

    /**
     * Выводит элементы нумерованного списка
     * @param dep
     */
    void printDeployment(File dep) {
        dependencyNumber++
        println dependencyNumber + ". ${dep.name}"
    }


    /**
     * Копирует .jar зависимость в папку dependency-workspace
     * Изменяет .jar: добавляет зависимостей-потомков в MANIFEST.MF
     * @param dep
     * @return
     */
    File createDeploymentInDependencyWorkspace(ResolvedDependency dep) {
        File jarSrc = getJarFromDependency(dep)
        String group = dep.module.id.group
        String name = dep.module.id.name
        String version = dep.module.id.version

        File jarDest = new File(dependencyWorkspace, jarSrc.name)
        GFileUtils.copyFile(jarSrc, jarDest)

        Set<ResolvedDependency> childrens = getChilderns(dep)

        if(childrens.size() > 0) {
            // Добавление в MANIFEST.MF зависимостей
            // https://github.com/jjzazuet/jgl/blob/master/jgl-demos/build.gradle
            projectInstance.ant.jar(destfile: jarDest, update: true) {
                delegate.manifest {
                    attribute(name: 'Dependencies', value: makeDependenciesString(childrens))
                }
            }
        }
        return jarDest
    }

    /**
     * Преобразует ResolvedDependency в File
     * @param dep
     * @return
     */
    File getJarFromDependency(ResolvedDependency dep) {
        return dep.moduleArtifacts.iterator()[0].file
    }

    String makeDependenciesString(Set<ResolvedDependency> childrens){
        StringBuilder sb = new StringBuilder()
        childrens.each {
            sb.append('deployment.')
            sb.append(getJarFromDependency(it).name)
            sb.append(' export')
            sb.append(', ')
        }
        String retStr = sb.toString()
        retStr = retStr.substring(0, retStr.size()-2) // удаляем конечную строку ", "
        return retStr
    }

    /**
     * Один из вариантов деплоя
     * @param jarSrc
     */
    void deployDeployment(File jarSrc) {
        File wildflyDeploymentsFolder = new File("${projectInstance.wildfly.wildflyHome}/standalone/deployments/")
        File jarDest = new File(wildflyDeploymentsFolder, jarSrc.name)
        GFileUtils.copyFile(jarSrc, jarDest)
    }

}

class WildflyPluginExtension {
    File wildflyHome
    boolean addFirstLevelDependenciesToManifest
    boolean printTree
    boolean printOrder
}
