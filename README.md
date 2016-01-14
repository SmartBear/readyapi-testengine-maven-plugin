# ReadyAPI TestServer Maven Plugin

A maven plugin that runs a set of json recipes and xml projects against your APIs - configure it to run 
in whatever build phase you might find relevant, for example;

```
<plugin>
    <groupId>com.smartbear.readyapi</groupId>
    <artifactId>testserver-maven-plugin</artifactId>
    <version>1.0-SNAPSHOT</version>
    <configuration>
        <username>defaultUser</username>
        <password>defaultPassword</password>
        <server>http://ready-api-test-server.swaggerhub31339dac46cf41e3.svc.tutum.io:8080</server>
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
* recipeDirectory : the folder to scan for recipes/projects, defaults to ${project.basedir}/src/test/resources/recipes
* targetDirectory : the folder to which filtered recipes will be copied before executing, defaults
to ${project.basedir}/target/test-recipes
* properties : an optional set of additional properties that will be used during filtering (see below)

Specifying a skipApiTests system property will bypass this plugin altogether.

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

## Error reporting

Currently the plugin simple fails the build if any tests fail and dumps the Ready!API TestServer 
response to the console.

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
