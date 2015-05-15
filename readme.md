#TODO
* numbers instead # in task prepareDependencies - as static variable
* generate manifest wirh dependencies for project jar (optional)
* in task deployDependencies make deploys via jboss-cli
* make cache for remove duplicates dependencies
* get environment variable WILDFLY_HOME

#Usage
Add
`wildfly {`
`         wildflyHome = file('C:\\path\\to\\wildflyl')`
`         printTree = true`
`     }`

Invoke `gradle prepareDependencies` copy all dependent jars into `C:\Path\To\Project\build\dependency-workspace`
and adds Dependencies string into theirs MANIFEST.MF