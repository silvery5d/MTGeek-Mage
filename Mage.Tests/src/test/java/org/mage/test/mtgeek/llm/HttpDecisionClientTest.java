package org.mage.test.mtgeek.llm;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class HttpDecisionClientTest {
    private HttpServer server;
    private int port;
    private AtomicInteger callCount;
    private volatile int currentResponseStatus = 200;
    private volatile String currentResponseBody = "{\"choices\":[0],\"rationale\":\"r\"}";

    @Before
    public void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        callCount = new AtomicInteger(0);
        currentResponseStatus = 200;
        currentResponseBody = "{\"choices\":[0],\"rationale\":\"r\"}";
        server.createContext("/api/mtg-decision", new HttpHandler() {
            @Override public void handle(HttpExchange ex) throws IOException {
                callCount.incrementAndGet();
                byte[] body = currentResponseBody.getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(currentResponseStatus, body.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(body); }
            }
        });
        server.start();
    }

    @After
    public void tearDown() { if (server != null) server.stop(0); }

    private HttpDecisionClient makeClient() {
        return new HttpDecisionClient(
            "http://127.0.0.1:" + port + "/api/mtg-decision",
            HttpClient.newHttpClient(),
            new int[]{10, 20, 40}   // tiny backoff to keep tests fast
        );
    }

    @Test
    public void decide_success_singleCall() {
        DecisionResponse r = makeClient().decide(new HashMap<>());
        assertArrayEquals(new int[]{0}, r.choices);
        assertEquals("r", r.rationale);
        assertEquals(1, callCount.get());
    }

    @Test
    public void decide_500_retriesThenFails() {
        currentResponseStatus = 500;
        currentResponseBody = "boom";
        try {
            makeClient().decide(new HashMap<>());
            fail("expected DecisionFailedException");
        } catch (DecisionFailedException e) {
            assertTrue("message: " + e.getMessage(), e.getMessage().contains("4 attempts"));
        }
        assertEquals(4, callCount.get());
    }

    @Test
    public void decide_503_failsImmediatelyNoRetry() {
        currentResponseStatus = 503;
        currentResponseBody = "{\"error\":\"MINIMAX_API_KEY not set\"}";
        try {
            makeClient().decide(new HashMap<>());
            fail("expected DecisionFailedException");
        } catch (DecisionFailedException e) {
            assertTrue("message: " + e.getMessage(), e.getMessage().contains("503"));
        }
        assertEquals(1, callCount.get());
    }

    @Test
    public void decide_endpointDown_failsWithConnectionRefused() {
        // Stop the server to force connection refused
        server.stop(0);
        HttpDecisionClient client = new HttpDecisionClient(
            "http://127.0.0.1:" + port + "/api/mtg-decision",
            HttpClient.newHttpClient(),
            new int[]{10, 20, 40}
        );
        try {
            client.decide(new HashMap<>());
            fail("expected DecisionFailedException");
        } catch (DecisionFailedException e) {
            assertTrue("message: " + e.getMessage(),
                e.getMessage().contains("4 attempts") || e.getMessage().contains("dev server"));
        }
    }
}
