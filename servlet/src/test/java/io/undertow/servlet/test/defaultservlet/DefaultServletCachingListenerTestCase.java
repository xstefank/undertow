/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2021 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.undertow.servlet.test.defaultservlet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.servlet.DispatcherType;
import javax.servlet.ServletException;

import io.undertow.server.handlers.PathHandler;
import io.undertow.server.handlers.cache.DirectBufferCache;
import io.undertow.server.handlers.resource.CachingResourceManager;
import io.undertow.server.handlers.resource.PathResourceManager;
import io.undertow.server.session.SecureRandomSessionIdGenerator;
import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;
import io.undertow.servlet.api.ServletContainer;
import io.undertow.servlet.api.ServletInfo;
import io.undertow.servlet.test.path.ServletPathMappingTestCase;
import io.undertow.servlet.test.util.PathTestServlet;
import io.undertow.servlet.test.util.MessageFilter;
import io.undertow.servlet.test.util.TestClassIntrospector;
import io.undertow.testutils.DefaultServer;
import io.undertow.testutils.HttpClientUtils;
import io.undertow.testutils.TestHttpClient;
import io.undertow.util.FileUtils;
import io.undertow.util.StatusCodes;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xnio.BufferAllocator;

/**
 * <p>Same test case than DefaultServletCachingTestCase but enabling the
 * resource change listeners to detect changes in the file system.</p>
 *
 * @author rmartinc
 */
@RunWith(DefaultServer.class)
public class DefaultServletCachingListenerTestCase {

    private static final int MAX_FILE_SIZE = 20;
    private static final int METADATA_MAX_AGE = 1000;
    public static final String DIR_NAME = "cacheTest";

    private static Path tmpDir;
    private static DirectBufferCache dataCache = new DirectBufferCache(1000, 10, 1000 * 10 * 1000, BufferAllocator.DIRECT_BYTE_BUFFER_ALLOCATOR, METADATA_MAX_AGE);

    @Before
    public void before() {
        for(Object k : dataCache.getAllKeys()) {
            dataCache.remove(k);
        }
    }

    @BeforeClass
    public static void setup() throws ServletException, IOException {

        tmpDir = Files.createTempDirectory(DIR_NAME);

        final PathHandler root = new PathHandler();
        final ServletContainer container = ServletContainer.Factory.newInstance();

        DeploymentInfo builder = new DeploymentInfo()
                .setClassIntrospecter(TestClassIntrospector.INSTANCE)
                .setClassLoader(ServletPathMappingTestCase.class.getClassLoader())
                .setContextPath("/servletContext")
                .addWelcomePage("index.html")
                .setDeploymentName("servletContext.war")
                // PathResourceManager enables the resource change listeners in this test and max-age is infinite/-1
                .setResourceManager(new CachingResourceManager(100, MAX_FILE_SIZE, dataCache, new PathResourceManager(tmpDir, 10485760, false, false, true), -1));

        builder.addServlet(new ServletInfo("DefaultTestServlet", PathTestServlet.class)
                .addMapping("/path/default"))
                .addFilter(Servlets.filter("message", MessageFilter.class).addInitParam(MessageFilter.MESSAGE, "FILTER_TEXT "))
                .addFilterUrlMapping("message", "*.txt", DispatcherType.REQUEST);

        DeploymentManager manager = container.addDeployment(builder);
        manager.deploy();
        root.addPrefixPath(builder.getContextPath(), manager.start());

        DefaultServer.setRootHandler(root);
    }

    @AfterClass
    public static void after() throws IOException{
        FileUtils.deleteRecursive(tmpDir);
    }

    @Test
    public void testFileExistanceCheckCached() throws IOException, InterruptedException {
        TestHttpClient client = new TestHttpClient();
        String fileName = new SecureRandomSessionIdGenerator().createSessionId() + ".html";
        try {
            HttpGet get = new HttpGet(DefaultServer.getDefaultServerURL() + "/servletContext/" + fileName);
            HttpResponse result = client.execute(get);
            Assert.assertEquals(StatusCodes.NOT_FOUND, result.getStatusLine().getStatusCode());
            HttpClientUtils.readResponse(result);

            Path f = tmpDir.resolve(fileName);
            Files.write(f, "hello".getBytes());
            Thread.sleep(METADATA_MAX_AGE);

            get = new HttpGet(DefaultServer.getDefaultServerURL() + "/servletContext/" + fileName);
            result = client.execute(get);
            Assert.assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            String response = HttpClientUtils.readResponse(result);
            Assert.assertEquals("hello", response);
            Files.delete(f);
        } finally {
            client.getConnectionManager().shutdown();
        }
    }

    @Test
    public void testFileContentsCached() throws IOException, InterruptedException {
        TestHttpClient client = new TestHttpClient();
        String fileName = "hello.html";
        Path f = tmpDir.resolve(fileName);
        Files.write(f, "hello".getBytes());
        try {
            for (int i = 0; i < 10; ++i) {
                HttpGet get = new HttpGet(DefaultServer.getDefaultServerURL() + "/servletContext/" + fileName);
                HttpResponse result = client.execute(get);
                Assert.assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
                String response = HttpClientUtils.readResponse(result);
                Assert.assertEquals("hello", response);
            }
            Files.write(f, "hello world".getBytes());

            Thread.sleep(METADATA_MAX_AGE);

            HttpGet get = new HttpGet(DefaultServer.getDefaultServerURL() + "/servletContext/" + fileName);
            HttpResponse result = client.execute(get);
            Assert.assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            String response = HttpClientUtils.readResponse(result);
            Assert.assertEquals("hello world", response);

        } finally {
            client.getConnectionManager().shutdown();
        }
    }

    @Test
    public void testFileContentsCachedWithFilter() throws IOException, InterruptedException {
        TestHttpClient client = new TestHttpClient();
        String fileName = "hello.txt";
        Path f = tmpDir.resolve(fileName);
        Files.write(f, "hello".getBytes());
        try {
            for (int i = 0; i < 10; ++i) {
                HttpGet get = new HttpGet(DefaultServer.getDefaultServerURL() + "/servletContext/" + fileName);
                HttpResponse result = client.execute(get);
                Assert.assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
                String response = HttpClientUtils.readResponse(result);
                Assert.assertEquals("FILTER_TEXT hello", response);
            }
            Files.write(f, "hello world".getBytes());

            Thread.sleep(METADATA_MAX_AGE);

            HttpGet get = new HttpGet(DefaultServer.getDefaultServerURL() + "/servletContext/" + fileName);
            HttpResponse result = client.execute(get);
            Assert.assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            String response = HttpClientUtils.readResponse(result);
            Assert.assertEquals("FILTER_TEXT hello world", response);

        } finally {
            client.getConnectionManager().shutdown();
        }
    }

    @Test
    public void testRangeRequest() throws IOException {
        TestHttpClient client = new TestHttpClient();
        try {
            String fileName = "range.html";
            Path f = tmpDir.resolve(fileName);
            Files.write(f, "hello".getBytes());
            HttpGet get = new HttpGet(DefaultServer.getDefaultServerURL() + "/servletContext/range.html");
            get.addHeader("range", "bytes=2-3");
            HttpResponse result = client.execute(get);
            Assert.assertEquals(StatusCodes.PARTIAL_CONTENT, result.getStatusLine().getStatusCode());
            String response = HttpClientUtils.readResponse(result);
            Assert.assertEquals("ll", response);

        } finally {
            client.getConnectionManager().shutdown();
        }
    }

    @Test
    public void testRangeRequestFileNotInCache() throws IOException {
        TestHttpClient client = new TestHttpClient();
        try {
            String fileName = "range_not_in_cache.html";
            Path f = tmpDir.resolve(fileName);
            Files.write(f, "hello world and once again hello world".getBytes());
            HttpGet get = new HttpGet(DefaultServer.getDefaultServerURL() + "/servletContext/range_not_in_cache.html");
            get.addHeader("range", "bytes=2-3");
            HttpResponse result = client.execute(get);
            Assert.assertEquals(StatusCodes.PARTIAL_CONTENT, result.getStatusLine().getStatusCode());
            String response = HttpClientUtils.readResponse(result);
            Assert.assertEquals("ll", response);
        } finally {
            client.getConnectionManager().shutdown();
        }
    }

    @Test
    public void testWelcomePages() throws IOException, InterruptedException {
        TestHttpClient client = new TestHttpClient();
        try {
            String fileName = "index.html";
            String content = "<html></html>";

            HttpGet get = new HttpGet(DefaultServer.getDefaultServerURL() + "/servletContext/");
            HttpResponse result = client.execute(get);
            Assert.assertEquals(StatusCodes.FORBIDDEN, result.getStatusLine().getStatusCode());
            HttpClientUtils.readResponse(result);
            get = new HttpGet(DefaultServer.getDefaultServerURL() + "/servletContext/" + fileName);
            result = client.execute(get);
            Assert.assertEquals(StatusCodes.NOT_FOUND, result.getStatusLine().getStatusCode());
            HttpClientUtils.readResponse(result);

            Path f = tmpDir.resolve(fileName);
            Files.write(f, content.getBytes());

            Thread.sleep(METADATA_MAX_AGE);

            get = new HttpGet(DefaultServer.getDefaultServerURL() + "/servletContext/");
            result = client.execute(get);
            Assert.assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            Assert.assertEquals(content, HttpClientUtils.readResponse(result));
            get = new HttpGet(DefaultServer.getDefaultServerURL() + "/servletContext/" + fileName);
            result = client.execute(get);
            Assert.assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            Assert.assertEquals(content, HttpClientUtils.readResponse(result));

            Files.delete(f);

            Thread.sleep(METADATA_MAX_AGE);

            get = new HttpGet(DefaultServer.getDefaultServerURL() + "/servletContext/");
            result = client.execute(get);
            Assert.assertEquals(StatusCodes.FORBIDDEN, result.getStatusLine().getStatusCode());
            HttpClientUtils.readResponse(result);
            get = new HttpGet(DefaultServer.getDefaultServerURL() + "/servletContext/" + fileName);
            result = client.execute(get);
            Assert.assertEquals(StatusCodes.NOT_FOUND, result.getStatusLine().getStatusCode());
            HttpClientUtils.readResponse(result);
        } finally {
            client.getConnectionManager().shutdown();
        }
    }
}
