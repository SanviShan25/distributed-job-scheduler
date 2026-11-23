package com.sanvi.jobscheduler.pulsecheck.auth;

import java.util.List;
import java.util.Set;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.security.web.authentication.SpringSessionRememberMeServices;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

import com.sanvi.jobscheduler.pulsecheck.PulseCheckProperties;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The login session cookie: HttpOnly, SameSite=Lax, path /, and Secure according to
 * {@code pulsecheck.session.secure-cookie} (AUTO = everywhere except localhost). Without "remember me"
 * it is a browser-session cookie; with it, it lives as long as the remember-me timeout.
 */
@Configuration(proxyBeanMethods = false)
public class SessionCookieConfig {
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    @Bean
    CookieSerializer cookieSerializer(PulseCheckProperties props) {
        PulseCheckProperties.Session cfg = props.session();
        DefaultCookieSerializer secure = serializer(cfg, true);
        DefaultCookieSerializer insecure = serializer(cfg, false);
        int rememberMeSeconds = (int) cfg.rememberMeTimeout().toSeconds();

        return new CookieSerializer() {
            @Override
            public void writeCookieValue(CookieValue cookieValue) {
                HttpServletRequest request = cookieValue.getRequest();
                boolean deleting = cookieValue.getCookieValue() == null || cookieValue.getCookieValue().isEmpty();
                if (!deleting && cookieValue.getCookieMaxAge() < 0
                        && request.getAttribute(SpringSessionRememberMeServices.REMEMBER_ME_LOGIN_ATTR) != null) {
                    cookieValue.setCookieMaxAge(rememberMeSeconds);
                }
                (useSecure(cfg.secureCookie(), request) ? secure : insecure).writeCookieValue(cookieValue);
            }

            @Override
            public List<String> readCookieValues(HttpServletRequest request) {
                return secure.readCookieValues(request);
            }
        };
    }

    static boolean useSecure(PulseCheckProperties.SecureCookie mode, HttpServletRequest request) {
        return switch (mode) {
            case ALWAYS -> true;
            case NEVER -> false;
            case AUTO -> !LOCAL_HOSTS.contains(String.valueOf(request.getServerName()).toLowerCase());
        };
    }

    private static DefaultCookieSerializer serializer(PulseCheckProperties.Session cfg, boolean secure) {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName(cfg.cookieName());
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setSameSite("Lax");
        serializer.setUseSecureCookie(secure);
        serializer.setUseBase64Encoding(true);
        return serializer;
    }
}
