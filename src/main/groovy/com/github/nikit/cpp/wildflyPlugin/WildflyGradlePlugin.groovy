package com.github.nikit.cpp.wildflyPlugin

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.util.GFileUtils

/**
 * Created by nik on 12.05.15.
 */
class WildflyGradlePlugin implements Plugin<Project> {
    File buildDir
    File dependencyWorkspace
    Project projectInstance
    String confNameCompile = 'compile'
    String confNameProvided = 'providedCompile'
    boolean isDeploy
    static int iterableDependency

    // Упорядоченное хранилище зависимостей, предназначенное для предотвращения дублирования
    List<ResolvedDependency> cachedDependencies

    void apply(Project project) {
        cachedDependencies = new ArrayList<>()
        projectInstance = project

        buildDir = project.buildDir
        dependencyWorkspace = new File(buildDir, 'dependency-workspace')
        dependencyWorkspace.mkdirs()
        Configuration conf = project.configurations[confNameCompile]

        println "WildFly dependencies will be stored in '" + dependencyWorkspace + "'"

        // Add the 'greeting' extension object
        project.extensions.create("wildfly", WildflyPluginExtension)

        project.task('deployDependencies') << {
            isDeploy = true
            processChildDependencies(getRootDependencies(), 0);

            println "Dependent jars will be deployed as below order:"
            processCachedDependencies();
        }

        project.task('prepareDependencies') << {
            isDeploy = false
            processChildDependencies(getRootDependencies(), 0);

            println "Dependent jars must be deployed as below order:"
            processCachedDependencies();
        }
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

    /**
     * Основная функция, рекурсивно обрабатывающая дерево зависимостей.
     * Заполняет cachedDependencies
     * @param allDependencies
     * @param level
     */
    void processChildDependencies(Set<ResolvedDependency> allDependencies, int level) {
        level++

        for (ResolvedDependency dep : allDependencies) {
            preChildernProcessed(dep, level)

            Set<ResolvedDependency> childrens = getChilderns(dep)

            // спускаемся на нижние уровни рекурсии
            processChildDependencies(childrens, level);

            postChildrenProcessed(dep)
        }

    }

    private void preChildernProcessed(ResolvedDependency dep, int level) {
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

        println " " + "*"*level + " id=${dep.module.id} file=${jarSrc}"
    }

    /**
     * Выводит элементы нумерованного списка
     * @param dep
     */
    void printDeployment(File dep) {
        println " " + iterableDependency++ + " ${dep}"
    }


    /**
     * Копирует .jar зависимости в папку dependency-workspace
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
            if(childrens.size() > 1) {
                sb.append(', ')
            }
        }

        return sb
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
    boolean printTree
    boolean printOrder
}
