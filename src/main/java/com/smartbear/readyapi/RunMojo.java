package com.smartbear.readyapi;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
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

import com.google.common.collect.Lists;
import com.google.common.io.CharStreams;
import io.swagger.client.model.ProjectResultReport;
import io.swagger.util.Json;
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
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;

@Mojo(name = "run")
public class RunMojo
        extends AbstractMojo {
    @Component
    private MavenResourcesFiltering mavenResourcesFiltering;

    @Component
    private MavenProject mavenProject;

    @Component
    private MavenSession mavenSession;

    @Parameter
    private Map properties;

    @Parameter
    private boolean disableFiltering;

    @Parameter(required = true, property = "ready-api-test-server.username")
    private String username;

    @Parameter(required = true, property = "ready-api-test-server.password")
    private String password;

    @Parameter(required = true, property = "ready-api-test-server.endpoint")
    private String server;

    @Parameter(defaultValue = "${project.basedir}/src/test/resources/recipes", required = true)
    private File recipeDirectory;

    @Parameter(defaultValue = "${project.basedir}/target/test-recipes", required = true)
    private File targetDirectory;

    private CloseableHttpClient httpClient;
    private HttpClientContext httpContext;
    private HttpHost httpHost;

    public void execute()
            throws MojoExecutionException {
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

            for (String file : files) {
                String fileName = file.toLowerCase();
                CloseableHttpResponse response;

                File f = new File(recipeDirectory,file);

                if (fileName.endsWith(".json")) {
                    response = runJsonRecipe(f);
                } else if (fileName.endsWith(".xml")) {
                    response = runXmlProject(f);
                } else {
                    getLog().warn("Unexpected filename: " + fileName);
                    continue;
                }

                handleResponse(response);
            }
        } catch (Exception e) {
            throw new MojoExecutionException("Error running recipe", e);
        }
    }

    private void readRecipeProperties() throws IOException {
        File recipeProperties = new File(recipeDirectory, "recipe.properties");
        if( recipeProperties.exists()){
            Properties props = new Properties();
            props.load( new FileInputStream( recipeProperties ));
            getLog().info( "Read " + props.size() + " properties from recipe.properties");

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

    private void handleResponse(CloseableHttpResponse response) throws IOException, MojoFailureException {

        getLog().info("Response status: " + response.getStatusLine());
        InputStreamReader inputStreamReader = new InputStreamReader(response.getEntity().getContent());
        String responseBody = CharStreams.toString(inputStreamReader);

        ProjectResultReport result = Json.mapper().reader(ProjectResultReport.class).readValue(responseBody);
        if (result.getStatus() == ProjectResultReport.StatusEnum.FAILED) {
            getLog().error("Response body:" + responseBody);
            throw new MojoFailureException("Recipe Failed");
        } else {
            getLog().debug("Response body:" + responseBody);
        }

        response.close();
    }

    private CloseableHttpResponse runXmlProject(File file) throws IOException, MavenFilteringException {
        getLog().debug("Executing project " + file.getName());

        HttpPost httpPost = new HttpPost(server + "/v1/readyapi/executions/xml?async=false");
        httpPost.setEntity(new FileEntity(file, ContentType.APPLICATION_XML));

        return httpClient.execute(httpHost, httpPost, httpContext);
    }

    private CloseableHttpResponse runJsonRecipe(File file) throws IOException, MavenFilteringException {

        if( !disableFiltering) {
            file = filterRecipe(file);
        }

        getLog().debug("Running recipe " + file.getName());

        HttpPost httpPost = new HttpPost(server + "/v1/readyapi/executions?async=false");
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

        mavenResourcesFiltering.filterResources(resourcesExecution);

        return new File(targetDirectory, filename);
    }

    private static class RecipeFilenameFilter implements FilenameFilter {
        public boolean accept(File dir, String name) {
            String nm = name.toLowerCase();
            return nm.endsWith(".json") || nm.endsWith(".xml");
        }
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
