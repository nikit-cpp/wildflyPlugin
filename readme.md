#TODO
* in task deployDeployments make deploys via jboss-cli
* get environment variable WILDFLY_HOME

#Usage
Add
`wildfly {`
`         wildflyHome = file('C:\\path\\to\\wildflyl')`
`         printTree = true`
`     }`

Invoke `gradle prepareDependencies` copy all dependent jars into `C:\Path\To\Project\build\dependency-workspace`
and adds Dependencies string into theirs MANIFEST.MF