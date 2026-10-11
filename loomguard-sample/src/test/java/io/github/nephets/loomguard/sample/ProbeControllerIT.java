/*
 * Copyright 2026 The loomguard Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.nephets.loomguard.sample;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 端到端验证：探针确实能区分「错误用法（pinning）」与「正确用法（不 pinning）」。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProbeControllerIT {

    @Autowired private TestRestTemplate rest;

    @SuppressWarnings("unchecked")
    private Map<String, Object> getForMap(String url) {
        ResponseEntity<Map> response = rest.getForEntity(url, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return (Map<String, Object>) response.getBody();
    }

    @SuppressWarnings("unchecked")
    private List<Object> findingsOf(Map<String, Object> body) {
        return (List<Object>) body.get("findings");
    }

    @SuppressWarnings("unchecked")
    private boolean hasFinding(List<Object> findings, String severity, String ruleId) {
        for (Object f : findings) {
            Map<String, Object> finding = (Map<String, Object>) f;
            if (severity.equals(finding.get("severity")) && ruleId.equals(finding.get("ruleId"))) {
                return true;
            }
        }
        return false;
    }

    @Test
    void badEndpointIsPinned() {
        Map<String, Object> body = getForMap("/demo/bad");
        Map<String, Object> observation = (Map<String, Object>) body.get("observation");
        Map<String, Object> pinning = (Map<String, Object>) observation.get("pinning");
        List<Object> events = (List<Object>) pinning.get("events");
        assertThat(events).isNotEmpty();
        assertThat(hasFinding(findingsOf(body), "CRITICAL", "LOOM-PINNED")).isTrue();
    }

    @Test
    void goodEndpointIsNotPinned() {
        Map<String, Object> body = getForMap("/demo/good");
        Map<String, Object> observation = (Map<String, Object>) body.get("observation");
        Map<String, Object> pinning = (Map<String, Object>) observation.get("pinning");
        List<Object> events = (List<Object>) pinning.get("events");
        assertThat(events).isEmpty();
        assertThat(hasFinding(findingsOf(body), "CRITICAL", "LOOM-PINNED")).isFalse();
    }

    @Test
    void cpuEndpointWarns() {
        Map<String, Object> body = getForMap("/demo/cpu");
        assertThat(hasFinding(findingsOf(body), "WARN", "LOOM-CPU-BOUND")).isTrue();
    }

    @Test
    void plainEndpointIsOk() {
        Map<String, Object> body = getForMap("/demo/plain");
        Map<String, Object> observation = (Map<String, Object>) body.get("observation");
        Map<String, Object> pinning = (Map<String, Object>) observation.get("pinning");
        assertThat(((List<Object>) pinning.get("events"))).isEmpty();
        assertThat(hasFinding(findingsOf(body), "CRITICAL", "LOOM-PINNED")).isFalse();
    }

    @Test
    void actuatorEndpointExposesSnapshot() {
        ResponseEntity<Map> response = rest.getForEntity("/actuator/loomguard", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("enabled")).isEqualTo(true);
    }
}
