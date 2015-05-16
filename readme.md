#TODO
* in task deployDeployments make deploys via jboss-cli
* get environment variable WILDFLY_HOME

#Usage
1. Install plugin as described [here](https://plugins.gradle.org/plugin/com.github.nikit.cpp.wildflyPlugin)
2. Add wildfly extension
`wildfly {`
`   wildflyHome = file('C:\\path\\to\\wildflyl')`
`   addFirstLevelDependenciesToManifest = true // generate manifest and add firstLevel dependencies`
`   printOrder = true // print order om manually deploy`
`   printTree = false // print dependency tree for debug purposes`
`}`

Task `gradle makeDeployments` copy all dependent jars into `C:\Path\To\Project\build\dependency-workspace`
and adds Dependencies string into theirs MANIFEST.MF

Task `gradle makeDeployments` makes all of `makeDeployments` and copies jars from
`C:\Path\To\Project\build\dependency-workspace` to `wildflyHome/standalone/deployments/`.
This task will be rewrited because WildFly's scanner doesn't considers deploy order
(by file attribute `create time`) and sometimes deploy failed.