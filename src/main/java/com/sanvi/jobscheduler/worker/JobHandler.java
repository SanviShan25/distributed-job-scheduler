package com.sanvi.jobscheduler.worker;

public interface JobHandler {
    String type();

    void handle(String payload) throws Exception;
}