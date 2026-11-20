package com.sanvi.jobscheduler.worker;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

/** Type -> handler lookup, built once at startup. Duplicate types fail fast. */
@Component
public class JobHandlerRegistry {
    private final Map<String, JobHandler> handlers;

    public JobHandlerRegistry(List<JobHandler> handlerList) {
        Map<String, JobHandler> byType = new TreeMap<>();
        for (JobHandler handler : handlerList) {
            JobHandler previous = byType.putIfAbsent(handler.type(), handler);
            if (previous != null) {
                throw new IllegalStateException("Duplicate JobHandler for type '" + handler.type() + "': "
                    + previous.getClass().getName() + " and " + handler.getClass().getName());
            }
        }
        this.handlers = Map.copyOf(byType);
    }

    public Optional<JobHandler> find(String type) {
        return Optional.ofNullable(type == null ? null : handlers.get(type));
    }

    public boolean supports(String type) {
        return type != null && handlers.containsKey(type);
    }

    public Set<String> types() {
        return new java.util.TreeSet<>(handlers.keySet());
    }
}
