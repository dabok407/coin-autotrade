package com.example.upbit.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class BuildInfoController {

    @Value("${app.version:unknown}")
    private String version;

    @Value("${app.build-time:unknown}")
    private String buildTime;

    @GetMapping("/api/build-info")
    public Map<String, String> buildInfo() {
        Map<String, String> info = new LinkedHashMap<String, String>();
        info.put("version", version);
        info.put("buildTime", buildTime);
        return info;
    }
}
