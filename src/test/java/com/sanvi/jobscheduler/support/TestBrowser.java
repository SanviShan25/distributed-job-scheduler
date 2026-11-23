package com.sanvi.jobscheduler.support;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;

/**
 * A minimal browser for MockMvc: keeps cookies between requests and echoes the XSRF-TOKEN cookie in the
 * X-XSRF-TOKEN header, like a single-page app. Use with the auto-configured MockMvc so the Spring Session
 * filter sees real session cookies.
 */
public final class TestBrowser {
    public static final String SESSION_COOKIE = "PULSECHECK_SESSION";

    private final MockMvc mvc;
    public final Map<String, String> cookies = new LinkedHashMap<>();
    public MockHttpServletResponse last;

    public TestBrowser(MockMvc mvc) {
        this.mvc = mvc;
    }

    public ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        cookies.forEach((name, value) -> request.cookie(new Cookie(name, value)));
        if (cookies.containsKey("XSRF-TOKEN")) {
            request.header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
        }
        ResultActions result = mvc.perform(request);
        last = result.andReturn().getResponse();
        for (String header : last.getHeaders("Set-Cookie")) {
            String[] nameValue = header.split(";", 2)[0].split("=", 2);
            if (header.contains("Max-Age=0") || nameValue[1].isEmpty()) {
                cookies.remove(nameValue[0]);
            } else {
                cookies.put(nameValue[0], nameValue[1]);
            }
        }
        return result;
    }

    public TestBrowser fetchCsrf() throws Exception {
        perform(get("/api/auth/csrf"));
        return this;
    }

    public ResultActions postJson(String url, String json) throws Exception {
        return perform(post(url).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    public ResultActions login(String email, String password) throws Exception {
        return postJson("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    public String sessionCookie() {
        return cookies.get(SESSION_COOKIE);
    }
}
