package com.smartbear.readyapi.maven;

/*
 * Copyright 2004-2015 SmartBear Software
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.google.common.io.CharStreams;
import com.smartbear.readyapi.portal.model.*;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.FileEntity;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.filtering.MavenFilteringException;
import org.apache.maven.shared.filtering.MavenResourcesExecution;
import org.apache.maven.shared.filtering.MavenResourcesFiltering;
import org.apache.maven.shared.model.fileset.FileSet;
import org.apache.maven.shared.model.fileset.util.FileSetManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

@Mojo(name = "run")
public class RunMojo
        extends AbstractMojo {
    @Component
    private MavenResourcesFiltering resourcesFiltering;

    @Component
    private MavenProject mavenProject;

    @Component
    private MavenSession mavenSession;

    @Parameter
    private Map properties;

    @Parameter
    private String environment;

    @Parameter( defaultValue = "true" )
    private boolean async;

    @Parameter
    private String callback;

    @Parameter
    private boolean disableFiltering;

    @Parameter(defaultValue = "true")
    private boolean failOnFailures;

    @Parameter(required = true, property = "readyapi-testengine.username")
    private String username;

    @Parameter(required = true, property = "readyapi-testengine.password")
    private String password;

    @Parameter(required = true, property = "readyapi-testengine.endpoint")
    private String server;

    @Parameter
    private String hostAndPort;

    @Parameter(defaultValue = "${project.basedir}/src/test/resources/recipes", required = true)
    private File recipeDirectory;

    @Parameter(defaultValue = "${project.basedir}/target/test-recipes", required = true)
    private File targetDirectory;

    @Parameter(defaultValue = "${basedir}/target/surefire-reports")
    private File reportTarget;

    private CloseableHttpClient httpClient;
    private HttpClientContext httpContext;
    private HttpHost httpHost;

    public void execute()
            throws MojoExecutionException, MojoFailureException {
        try {
            if( mavenSession.getSystemProperties().getProperty("skipApiTests") != null ){
                return;
            }

            List<String> files = getIncludedFiles();

            if (files.isEmpty() ) {
                getLog().warn("Missing matching files in project");
                return;
            }

            readRecipeProperties();
            initHttpClient();

            JUnitReport report = async ? null : new JUnitReport();

            int recipeCount = 0;
            int projectCount = 0;
            int failCount = 0;

            for (String file : files) {
                String fileName = file.toLowerCase();
                CloseableHttpResponse response;

                File f = new File(recipeDirectory,file);

                if (fileName.endsWith(".json")) {
                    recipeCount++;
                    response = runJsonRecipe(f);
                } else if (fileName.endsWith(".xml")) {
                    projectCount++;
                    response = runXmlProject(f);
                } else {
                    getLog().warn("Unexpected filename: " + fileName);
                    continue;
                }

                try{
                    handleResponse(response, report, file);
                }
                catch (MojoFailureException exception)
                {
                    failCount++;
                }
            }

            getLog().info("ReadyAPI TestEngine Maven Plugin");
            getLog().info("--------------------------------------");
            getLog().info("Recipes run: " + recipeCount );
            getLog().info("Projects run: " + projectCount );
            getLog().info("Failures: " + failCount );

            if( report != null ) {
                report.setTestSuiteName(mavenProject.getName());
                report.setNoofFailuresInTestSuite(failCount);

                if (!reportTarget.exists()) {
                    reportTarget.mkdirs();
                }

                report.save(new File(reportTarget, "recipe-report.xml"));

                if (failCount > 0 && failOnFailures) {
                    throw new MojoFailureException(failCount + " failures during test execution");
                }
            }

        } catch( MojoFailureException e ){
            throw e;
        }
        catch (Exception e) {
            throw new MojoExecutionException("Error running recipe", e);
        }
    }

    private void readRecipeProperties() throws IOException {
        File recipeProperties = new File(recipeDirectory, "recipe.properties");
        if( recipeProperties.exists()){
            Properties props = new Properties();
            props.load( new FileInputStream( recipeProperties ));
            getLog().debug( "Read " + props.size() + " properties from recipe.properties");

            // override with properties in config section
            if( properties != null ){
                props.putAll( properties );
            }

            properties = props;
        }
    }

    private List<String> getIncludedFiles() {

        FileSetManager fileSetManager = new FileSetManager();

        FileSet fileSet = new FileSet();
        fileSet.setDirectory(recipeDirectory.getAbsolutePath());
        fileSet.addInclude("**/*.json");
        fileSet.addInclude("**/*.xml");

        return Arrays.asList(fileSetManager.getIncludedFiles( fileSet ));
    }

    private void handleResponse(CloseableHttpResponse response, JUnitReport report, String name) throws IOException, MojoFailureException {

        getLog().debug("Response status: " + response.getStatusLine());
        InputStreamReader inputStreamReader = new InputStreamReader(response.getEntity().getContent());
        String responseBody = CharStreams.toString(inputStreamReader);
        response.close();

        getLog().debug("Response body:" + responseBody);

        if( report != null ) {
            ObjectMapper mapper = new ObjectMapper();
            TestJobReport result = mapper.readerFor(TestJobReport.class).readValue(responseBody);
            if (result.getStatus() == TestJobReport.StatusEnum.FAILED) {

                String message = logErrorsToConsole(result);
                report.addTestCaseWithFailure(name, result.getTotalTime(),
                    message, "<missing stacktrace>", new HashMap<String, String>(properties));

                throw new MojoFailureException("Recipe Failed");
            } else {
                report.addTestCase(name, result.getTotalTime(), new HashMap<String, String>(properties));
            }
        }
    }

    private String logErrorsToConsole(TestJobReport result) {

        List<String> messages = new ArrayList<String>();

        for( TestSuiteResultReport testSuiteResultReport : result.getTestSuiteResultReports()){
            for(TestCaseResultReport testCaseResultReport : testSuiteResultReport.getTestCaseResultReports()){
                for(TestStepResultReport stepResultReport : testCaseResultReport.getTestStepResultReports()){
                    if( stepResultReport.getAssertionStatus() == TestStepResultReport.AssertionStatusEnum.FAIL){
                        getLog().error("Failed " + testSuiteResultReport.getTestSuiteName() + " / " +
                                testCaseResultReport.getTestCaseName() + " / " + stepResultReport.getTestStepName());
                        for( String message : stepResultReport.getMessages()){
                            messages.add( message );
                            getLog().error( "- " + message);
                        }
                    }
                }
            }
        }

        return Arrays.toString( messages.toArray());
    }

    private CloseableHttpResponse runXmlProject(File file) throws IOException, MavenFilteringException {
        getLog().info("Executing project " + file.getName());

        HttpPost httpPost = new HttpPost(buildExecuteUri("/testjobs"));
        httpPost.setEntity(new FileEntity(file, ContentType.APPLICATION_XML));

        return httpClient.execute(httpHost, httpPost, httpContext);
    }

    private String buildExecuteUri(String path) {
        String uri = server + "/api/v1" + path + "?async=" + async;
        if( environment != null ){
            uri += "&environment=" + URLEncoder.encode( environment );
        }

        if (hostAndPort != null) {
            uri += "&hostAndPort=" + URLEncoder.encode(hostAndPort);
        }

        if( callback != null ){
            uri += "&callback=" + URLEncoder.encode( callback );
        }
        return uri;
    }

    private CloseableHttpResponse runJsonRecipe(File file) throws IOException, MavenFilteringException {

        if( !disableFiltering) {
            file = filterRecipe(file);
        }

        getLog().info("Running recipe " + file.getName());

        HttpPost httpPost = new HttpPost(buildExecuteUri("/testjobs/recipe"));
        httpPost.setEntity(new FileEntity(file, ContentType.APPLICATION_JSON));

        return httpClient.execute(httpHost, httpPost, httpContext);
    }

    private File filterRecipe(File file) throws MavenFilteringException {
        if (!targetDirectory.exists()) {
            targetDirectory.mkdirs();
        }

        Resource fileResource = new Resource();
        fileResource.setDirectory(recipeDirectory.getAbsolutePath());

        String filename = file.getAbsolutePath().substring(recipeDirectory.getAbsolutePath().length());

        fileResource.addInclude(filename);
        fileResource.setFiltering(true);

        MavenResourcesExecution resourcesExecution = new MavenResourcesExecution();
        resourcesExecution.setOutputDirectory(targetDirectory);
        resourcesExecution.setResources(Lists.newArrayList(fileResource));
        resourcesExecution.setOverwrite(true);
        resourcesExecution.setSupportMultiLineFiltering(true);
        resourcesExecution.setEncoding(Charset.defaultCharset().toString());

        if (properties != null && !properties.isEmpty()) {
            Properties props = new Properties();
            props.putAll(properties);
            getLog().debug("Adding additional properties: " + properties.toString());
            resourcesExecution.setAdditionalProperties(props);
        }

        resourcesExecution.setMavenProject(mavenProject);
        resourcesExecution.setMavenSession(mavenSession);
        resourcesExecution.setUseDefaultFilterWrappers(true);

        resourcesFiltering.filterResources(resourcesExecution);

        return new File(targetDirectory, filename);
    }

    /**
     * Sets up HttpClient with preemptive basic authentication
     */

    private void initHttpClient() throws MalformedURLException {

        URL url = new URL(server);
        httpHost = new HttpHost(url.getHost(), url.getPort());

        CredentialsProvider credsProvider = new BasicCredentialsProvider();
        credsProvider.setCredentials(new AuthScope(httpHost),
                new UsernamePasswordCredentials(username, password));
        httpClient = HttpClients.custom()
                .setDefaultCredentialsProvider(credsProvider)
                .build();

        AuthCache authCache = new BasicAuthCache();
        BasicScheme basicAuth = new BasicScheme();
        authCache.put(httpHost, basicAuth);

        httpContext = HttpClientContext.create();
        httpContext.setAuthCache(authCache);
    }
}
