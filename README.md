# ReadyAPI TestServer Maven Plugin

A maven plugin that runs a set of recipes and xml projects against your APIs - configure it to run 
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
``

By default the plugin will look in src/test/resources/recipes for json and xml files to execute.

Other configuration parameters are:
* recipeDirectory : the folder to scan for recipes/projects
* targetDirectory : the folder to which filtered recipes will be copied before executing, defaults
to target/test-recipes
* properties : a set of properties that will be used during filtering.

Specifying a skipApiTests system property will bypass this plugin alltogether.

## Filtering

As indicated, json recipes will be filtered and copied to the folder specified by 
targetDirectory before getting executed. Any available property will be replaced, which makes it 
easy to parameterize your tests.

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

from anywhere in your pom.

## Error reporting

Currently the plugin simple fails the build if any tests fail and dumps the Ready!API TestServer 
response to the console.

## Next steps?

Obviously huge list of things to improve:
- support datadriven tests
- nice reports that integrate with surefire
- etc..
