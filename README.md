# ReadyAPI TestServer Maven Plugin

A maven plugin that runs a set of Ready! API TestServer Json recipes and Ready! API projects - 
configure it to run in whatever build phase you might find relevant, for example;

```
<plugin>
    <groupId>com.smartbear.readyapi</groupId>
    <artifactId>testserver-maven-plugin</artifactId>
    <version>1.0-SNAPSHOT</version>
    <configuration>
        <username>defaultUser</username>
        <password>defaultPassword</password>
        <server>...Ready!API TestServer endpoint...</server>
    </configuration>
    <executions>
        <execution>
            <id>run</id>
            <phase>integration-test</phase>
            <goals>
                <goal>run</goal>
            </goals>
        </execution>
    </executions>
</plugin>
```

The only goal exposed by the plugin is "run" - you can invoke it as above or directly from the command-line, for example

```
mvn testserver:run 
```

The plugin will look for files with either json or xml extensions.

## Configuration

Configuration parameters are:

* username (required) : the TestServer username to use for authentication
* password (required) : the TestServer password to use for authentication
* server (required) : endpoint of the TestServer (no trailing slash!)
* recipeDirectory : the folder to scan recursively for recipes/projects, defaults to ${project.basedir}/src/test/resources/recipes
* targetDirectory : the folder to which filtered recipes will be copied before executing, defaults
to ${project.basedir}/target/test-recipes
* properties : an optional set of additional properties that will be used during filtering (see below)
* disableFiltering : disables filtering of recipes - if set to true the recipes will not be copied and filtere
to the target directory, instead they will run directly from the source directory.
* reportTarget : the folder to which a junit-report.xml file will be generated (as can be processed by 
the surefire plugin), defaults to ${basedir}/target/surefire-reports

Specifying a skipApiTests system property will bypass this plugin altogether.

The plugin will also look for standard properties file named recipe.properties in the recipeDirectory folder and
load any properties in this file before applying the properties specified in the configuration.

## Filtering

Json recipes will be filtered and copied to the folder specified by targetDirectory before getting executed. 
Any available property will be replaced, which makes it easy to parameterize your tests.

For example the following simple recipe:

```
{
    "testSteps": [
        {
            "type": "REST Request",
            "method": "GET",
            "URI": "${apitest.host}/apis",
            "assertions": [
                {
                    "type": "Valid HTTP Status Codes",
                    "validStatusCodes": [200]
                }
            ]
        }
    ]
}
```

would use a property defined as 

```
...
<apitest.host>...</apitest.host>
...
```              

when assembling the URI. You can simply look in the targetDirectory folder after your tests were run to see what was 
actually executed.

## Error reporting

Currently the plugin simple fails the build if any tests fail and dumps the Ready!API TestServer 
response to the console. A surefire xml file is generated for inclusion in generated reports.

## Building the plugin

Simply pull this repo and run 

```
mvn clean install
```

to install the latest version of the plugin locally. It will eventually be made available on maven central also.


## Next steps?

Obviously huge list of things to improve:
- support datadriven tests
- nice reports that integrate with surefire
- etc..
