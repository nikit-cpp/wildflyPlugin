import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.ResolvedDependency

/**
 * Created by nik on 12.05.15.
 */
class WildflyGradlePlugin implements Plugin<Project> {

    File buildDir
    File dependencyWorkspace
    Project projectInstance
    String confName = 'compile'
    boolean isDeploy
    boolean printDeployOrder

    void apply(Project project) {
        projectInstance = project
        buildDir = project.buildDir
        dependencyWorkspace = new File(buildDir, 'dependency-workspace')
        dependencyWorkspace.mkdirs()
        Configuration conf = project.configurations[confName]
        conf = conf.copy() // remove unnecessary dependencies from super configuration

        println "WildFly dependencies will be stored in '" + dependencyWorkspace + "'"

        // Add the 'greeting' extension object
        project.extensions.create("wildfly", WildflyPluginExtension)

        project.task('deployDependencies') << {
            isDeploy = true
            // http://gradle.org/docs/current/javadoc/org/gradle/api/artifacts/ResolvedConfiguration.html
            // http://gradle.org/docs/current/javadoc/org/gradle/api/artifacts/ResolvedDependency.html
            Set<ResolvedDependency> allDependencies = conf.resolvedConfiguration.firstLevelModuleDependencies

            processChildDependencies(allDependencies, 0);
        }

        project.task('prepareDependencies') << {
            isDeploy = false
            println "Dependent jars must be deployed as below order:"
            printDeployOrder = true
            projectInstance.wildfly.printTree = false
            Set<ResolvedDependency> allDependencies = conf.resolvedConfiguration.firstLevelModuleDependencies
            processChildDependencies(allDependencies, 0);
        }
    }

    void processChildDependencies(Set<ResolvedDependency> allDependencies, int level) {
        level++

        for (ResolvedDependency dep : allDependencies) {
            if(projectInstance.wildfly.printTree){
                printDependency(dep, level)
            }

            Set<ResolvedDependency> childrens = getChilderns(dep)

            // сначала спускаемся на нижние уровни рекурсии
            processChildDependencies(childrens, level);

            // затем деплоим - сделано, чтобы порядок деплоя был таким: от конечных узлов дерева, не имеющих зависимостей, к родительским
            File changedJar = addChildDependenciesToManifest(dep)
            if(isDeploy) {
                deployDeployment(changedJar)
            }
            if(printDeployOrder){
                printDeployment(changedJar)
            }
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
            if (it.configuration == confName) {
                childrens.add(it)
            }
        }

        return childrens
    }

    void printDependency(ResolvedDependency dep, int level) {
        File jarSrc = getJarFromDependency(dep)
        String group = dep.module.id.group
        String name = dep.module.id.name
        String version = dep.module.id.version

        println " " + "*"*level + " id=${dep.module.id} file=${jarSrc}"
    }

    void printDeployment(File dep) {
        println " " + "#" + " ${dep}"
    }

    /**
     * Копирует .jar зависимости в папку dependency-workspace
     * Изменяет .jar: добавляет зависимостей-потомков в MANIFEST.MF
     * @param dep
     * @return
     */
    File addChildDependenciesToManifest(ResolvedDependency dep) {
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
}
