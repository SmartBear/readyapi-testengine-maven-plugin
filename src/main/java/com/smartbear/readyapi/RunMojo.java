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
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;

@Mojo( name = "run", defaultPhase = LifecyclePhase.INTEGRATION_TEST )
public class RunMojo
    extends AbstractMojo
{
    @Parameter(required = true, property = "ready-api-test-server.username")
    private String username;

    @Parameter(required = true, property = "ready-api-test-server.password")
    private String password;

    @Parameter(required = true, property = "ready-api-test-server.endpoint")
    private String server;

    @Parameter(defaultValue="${project.basedir}/src/test/resources/recipes", required = true)
    private File recipeDirectory;

    @Parameter(defaultValue="${project.basedir}/src/test/resources/recipes/data", required = true)
    private File dataDirectory;

    private CloseableHttpClient httpClient;
    private HttpClientContext httpContext;
    private HttpHost httpHost;

    public void execute()
        throws MojoExecutionException
    {
        try
        {
            File f = recipeDirectory;

            if ( !f.exists() )
            {
                getLog().warn("Missing recipe directory [" + f.getAbsolutePath() + "]");
                return;
            }

            File[] files = f.listFiles(new RecipeFilenameFilter());
            if( files.length == 0 ){
                getLog().warn("Missing recipes in directory [" + f.getAbsolutePath() + "]");
                return;
            }

            initHttpClient();

            for( File file : files) {
                String fileName = file.getName().toLowerCase();
                CloseableHttpResponse response;

                if( fileName.endsWith(".json")){
                    response = runJsonRecipe(file);
                }
                else if( fileName.endsWith(".xml")){
                    response = runXmlProject( file );
                }
                else {
                    getLog().warn( "Unexpected filename: " + fileName );
                    continue;
                }

                handleResponse(response);
            }
        }
        catch ( Exception e )
        {
            throw new MojoExecutionException( "Error running recipe", e );
        }
    }

    private void handleResponse(CloseableHttpResponse response) throws IOException, MojoFailureException {

        getLog().info("Response status: " + response.getStatusLine());
        InputStreamReader inputStreamReader = new InputStreamReader(response.getEntity().getContent());
        String responseBody = CharStreams.toString(inputStreamReader);

        ProjectResultReport result = Json.mapper().reader(ProjectResultReport.class).readValue( responseBody );
        if( result.getStatus() == ProjectResultReport.StatusEnum.FAILED ) {
            getLog().error("Response body:" + responseBody);
            throw new MojoFailureException( "Recipe Failed");
        }
        else {
            getLog().info( "Response body:" + responseBody );
        }

        response.close();
    }

    private CloseableHttpResponse runXmlProject(File file) throws IOException {
        getLog().info("Executing project " + file.getName());

        HttpPost httpPost = new HttpPost( server + "/v1/readyapi/executions/xml?async=false");
        httpPost.setEntity( new FileEntity(file, ContentType.APPLICATION_XML));

        return httpClient.execute(httpHost, httpPost, httpContext);
    }

    private CloseableHttpResponse runJsonRecipe(File file ) throws IOException {
        getLog().info("Running recipe " + file.getName());

        HttpPost httpPost = new HttpPost( server + "/v1/readyapi/executions?async=false");
        httpPost.setEntity( new FileEntity(file, ContentType.APPLICATION_JSON));

        return httpClient.execute(httpHost, httpPost, httpContext);
    }

    private static class RecipeFilenameFilter implements FilenameFilter {
        public boolean accept(File dir, String name) {
            String nm = name.toLowerCase();
            return nm.endsWith(".json") || nm.endsWith( ".xml");
        }
    }

    private void initHttpClient() throws MalformedURLException {

        URL url = new URL( server );
        httpHost = new HttpHost(url.getHost(), url.getPort());

        CredentialsProvider credsProvider = new BasicCredentialsProvider();
        credsProvider.setCredentials( new AuthScope(httpHost),
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
