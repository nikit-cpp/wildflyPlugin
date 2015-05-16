#Info
This plugin adds `providedCompile` configuration if not exist,
prepares WildFly deploymets recurcively walks on dependency
tree - copy dependency jar to `build\dependency-workspace` and
adds Dependencies string to theirs maifests.
Also plugin can copy dependency jar to WildFly deploymets directory.

#TODO
* in task deployDeployments make deploys via jboss-cli
* get environment variable WILDFLY_HOME

#Usage
1. Install plugin as described [here](https://plugins.gradle.org/plugin/com.github.nikit.cpp.wildflyPlugin).
2. Add WildFly extension:
```
wildfly {
	wildflyHome = file('C:\\path\\to\\wildflyl')
	addFirstLevelDependenciesToManifest = true // generate manifest and add firstLevel dependencies
	printOrder = true // print order om manually deploy
	printTree = false // print dependency tree for debug purposes
}
```

3. If nesserary change `compile` configuration to `providedCompile` for those dependencies that are prolided by WildFly,
for example:
```
dependencies {
	compile 'org.apache.qpid:qpid-client:0.32'
	providedCompile 'org.jboss.spec.javax.ejb:jboss-ejb-api_3.2_spec:1.0.0.Final'
	providedCompile 'org.jboss.spec.javax.jms:jboss-jms-api_2.0_spec:1.0.0.Final'
}
```

#Gradle tasks
* `gradle makeDeployments` copy all dependent jars into `C:\Path\To\Project\build\dependency-workspace`
and adds Dependencies string into theirs MANIFEST.MF

* `gradle deployDeployments` makes all of `makeDeployments` and copies jars from
`C:\Path\To\Project\build\dependency-workspace` to `wildflyHome/standalone/deployments/`.
This task will be rewrited with jboss-cli because WildFly's scanner doesn't considers deploy order (by file attribute `create time`) and sometimes deploy failed.  
  * Workadrond 1:
You have to restart server that deployments correctly loaded.
  * Workadrond 2:
Don't use `deployDeployments` task, enable `printOrder = true` and deploy dependencies manually.
