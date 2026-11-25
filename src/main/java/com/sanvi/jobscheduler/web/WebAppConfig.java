package com.sanvi.jobscheduler.web;

import java.io.IOException;
import java.time.Duration;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the PulseCheck web app ({@code web/} built into {@code classpath:/static/}, see the Dockerfile).
 *
 * <ul>
 *   <li>{@code /assets/**}: Vite's hashed files, cached for a year ({@code immutable}): a new build has new
 *       file names, so a cached file is never stale.</li>
 *   <li>Everything else: {@code no-cache} (the browser revalidates with the ETag/Last-Modified every time),
 *       so {@code index.html} always points at the current assets after a deploy.</li>
 *   <li>Client routes ({@link WebAppPaths#isClientRoute}) without a file get {@code index.html} with 200, and
 *       the app's router shows the page (or its own "Page not found"). Backend paths never do: a missing API
 *       endpoint stays a JSON 404.</li>
 * </ul>
 *
 * <p>Spring Boot's default static mapping is turned off ({@code spring.web.resources.add-mappings: false})
 * so this is the only {@code /**} handler.
 */
@Configuration(proxyBeanMethods = false)
@Profile("api")
public class WebAppConfig implements WebMvcConfigurer {
    static final String LOCATION = "classpath:/static/";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(WebAppPaths.HASHED_ASSETS_PATTERN)
            .addResourceLocations(LOCATION + "assets/")
            .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
            .resourceChain(true)
            .addResolver(new PathResourceResolver());

        registry.addResourceHandler("/**")
            .addResourceLocations(LOCATION)
            .setCacheControl(CacheControl.noCache())
            // No resolver cache here: it would keep one entry per distinct URL, and every made-up path
            // resolves (to index.html), so anyone could grow it without limit.
            .resourceChain(false)
            .addResolver(new SpaFallbackResolver());
    }

    /** "/" has an empty resource path, which the resource handler rejects before any resolver runs. */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    static final class SpaFallbackResolver extends PathResourceResolver {
        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource file = super.getResource(resourcePath, location);
            if (file != null || !WebAppPaths.isClientRoute(resourcePath)) {
                return file;
            }
            return super.getResource("index.html", location);
        }
    }
}
