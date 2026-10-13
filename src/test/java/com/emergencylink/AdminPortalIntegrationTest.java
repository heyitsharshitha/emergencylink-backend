package com.emergencylink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Hospital records, resource corrections, settings, exception ownership, reports, saved filters and audit detail. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminPortalIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String login(String email) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"Demo@1234\"}")).andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    private JsonNode call(String method, String token, String url, String body, int expected) throws Exception {
        var b = switch (method) {
            case "PUT" -> put(url);
            case "DELETE" -> delete(url);
            default -> post(url);
        };
        if (body != null) b = b.contentType(MediaType.APPLICATION_JSON).content(body);
        MvcResult r = mvc.perform(b.header("Authorization", "Bearer " + token)).andExpect(status().is(expected)).andReturn();
        String c = r.getResponse().getContentAsString();
        return c.isBlank() ? null : json.readTree(c);
    }

    private JsonNode get(String token, String url) throws Exception {
        MvcResult r = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    private long createHospital(String admin, String name) throws Exception {
        String body = "{\"hospital\":{\"name\":\"" + name + "\",\"type\":\"MULTI_SPECIALTY\",\"city\":\"Hyderabad\",\"state\":\"Telangana\","
                + "\"specialties\":[\"Cardiology\"],\"facilities\":[]},\"reason\":\"Added for the test\"}";
        return call("POST", admin, "/api/admin/hospitals", body, 201).get("id").asLong();
    }

    @Test
    void adminCreatesAndEditsHospitalsWithAReasonAndBeforeAfterAudit() throws Exception {
        String admin = login("admin@emergencylink.demo");
        String name = "Test Hospital " + UUID.randomUUID().toString().substring(0, 8);
        long id = createHospital(admin, name);
        call("POST", admin, "/api/admin/hospitals", "{\"hospital\":{\"name\":\"" + name + "\",\"type\":\"MULTI_SPECIALTY\",\"city\":\"X\",\"state\":\"Y\"}}", 409);

        String edit = "{\"hospital\":{\"name\":\"" + name + "\",\"type\":\"MULTI_SPECIALTY\",\"city\":\"Warangal\",\"state\":\"Telangana\",\"specialties\":[\"Cardiology\"],\"facilities\":[]}";
        call("PUT", admin, "/api/admin/hospitals/" + id, edit + "}", 400); // reason missing
        call("PUT", admin, "/api/admin/hospitals/" + id, edit + ",\"reason\":\"Moved premises\"}", 200);
        call("PUT", admin, "/api/admin/hospitals/" + id, edit + ",\"reason\":\"Again\"}", 400); // nothing changed

        JsonNode audit = get(admin, "/api/admin/audit?action=HOSPITAL_ADMIN_EDITED&entityType=Hospital&outcome=SUCCESS");
        JsonNode first = audit.get("content").get(0);
        assertTrue(first.get("before").asText().contains("city=Hyderabad"));
        assertTrue(first.get("after").asText().contains("city=Warangal"));
        assertEquals("Moved premises", first.get("reason").asText());

        String referrer = login("medchal.referrer@emergencylink.demo");
        call("POST", referrer, "/api/admin/hospitals", "{\"hospital\":{\"name\":\"Nope\",\"type\":\"MULTI_SPECIALTY\",\"city\":\"X\",\"state\":\"Y\"}}", 403);
    }

    @Test
    void resourceCorrectionsAreValidatedLoggedAndWarnAboutMatching() throws Exception {
        String admin = login("admin@emergencylink.demo");
        long id = createHospital(admin, "Resource Hospital " + UUID.randomUUID().toString().substring(0, 8));
        String url = "/api/admin/hospitals/" + id + "/resources";
        call("PUT", admin, url, "{\"updates\":[{\"type\":\"ICU_BED\",\"total\":4,\"available\":9}],\"reason\":\"x\"}", 400);
        call("PUT", admin, url, "{\"updates\":[{\"type\":\"ICU_BED\",\"total\":4,\"available\":2}]}", 400);
        call("PUT", admin, url, "{\"updates\":[{\"type\":\"ICU_BED\",\"total\":4,\"available\":2}],\"reason\":\"Phoned the ward\"}", 200);
        call("PUT", admin, url, "{\"updates\":[{\"type\":\"ICU_BED\",\"total\":4,\"available\":2}],\"reason\":\"Same\"}", 400);
        JsonNode result = call("PUT", admin, url, "{\"updates\":[{\"type\":\"ICU_BED\",\"total\":4,\"available\":0}],\"reason\":\"Ward full\"}", 200);
        assertTrue(result.get("warnings").size() > 0);

        JsonNode history = get(admin, "/api/admin/hospitals/" + id + "/resource-history");
        assertEquals(2, history.size());
        assertTrue(history.get(0).get("changedBy").asText().contains("administrator correction"));
        assertEquals("Ward full", history.get(0).get("reason").asText());
        call("PUT", login("medchal.referrer@emergencylink.demo"), url, "{\"updates\":[],\"reason\":\"x\"}", 403);
    }

    @Test
    void thresholdsAreValidatedAuditedAndAdminOnly() throws Exception {
        String admin = login("admin@emergencylink.demo");
        call("PUT", admin, "/api/admin/settings", "{\"waitMinutes\":2,\"staleMinutes\":120,\"reason\":\"x\"}", 400);
        call("PUT", admin, "/api/admin/settings", "{\"waitMinutes\":45,\"staleMinutes\":120}", 400);
        JsonNode updated = call("PUT", admin, "/api/admin/settings", "{\"waitMinutes\":45,\"staleMinutes\":180,\"reason\":\"Quieter overnight\"}", 200);
        assertEquals(45, updated.get("waitMinutes").asInt());
        assertEquals(180, get(admin, "/api/admin/settings").get("staleMinutes").asInt());
        call("PUT", admin, "/api/admin/settings", "{\"waitMinutes\":45,\"staleMinutes\":180,\"reason\":\"No change\"}", 400);
        assertEquals(45, get(admin, "/api/admin/exceptions").get("waitThresholdMinutes").asInt());
        call("PUT", admin, "/api/admin/settings", "{\"waitMinutes\":30,\"staleMinutes\":120,\"reason\":\"Restore defaults\"}", 200);
        call("PUT", login("deccan.receiver@emergencylink.demo"), "/api/admin/settings", "{\"waitMinutes\":30,\"staleMinutes\":120,\"reason\":\"x\"}", 403);
    }

    @Test
    void exceptionsHaveOwnersAndRequireANoteToBeReviewed() throws Exception {
        String admin = login("admin@emergencylink.demo");
        long id = createHospital(admin, "Unstaffed Hospital " + UUID.randomUUID().toString().substring(0, 8));
        String key = "noaccount-" + id; // a new hospital has no user account yet
        JsonNode before = get(admin, "/api/admin/exceptions");
        boolean listed = false;
        for (JsonNode i : before.get("items")) if (key.equals(i.get("id").asText())) listed = "OPEN".equals(i.get("status").asText());
        assertTrue(listed);

        call("PUT", admin, "/api/admin/exceptions/state", "{\"id\":\"" + key + "\",\"status\":\"REVIEWED\"}", 400);
        call("PUT", admin, "/api/admin/exceptions/state", "{\"id\":\"missing-1\",\"status\":\"IN_PROGRESS\"}", 404);
        JsonNode claimed = call("PUT", admin, "/api/admin/exceptions/state", "{\"id\":\"" + key + "\",\"status\":\"IN_PROGRESS\"}", 200);
        boolean owned = false;
        for (JsonNode i : claimed.get("items")) if (key.equals(i.get("id").asText())) owned = "IN_PROGRESS".equals(i.get("status").asText()) && !i.get("ownerName").isNull();
        assertTrue(owned);
        call("PUT", admin, "/api/admin/exceptions/state", "{\"id\":\"" + key + "\",\"status\":\"REVIEWED\",\"note\":\"Account will be created next week\"}", 200);
        call("PUT", login("medchal.referrer@emergencylink.demo"), "/api/admin/exceptions/state", "{\"id\":\"" + key + "\",\"status\":\"OPEN\"}", 403);
    }

    @Test
    void reportsRespectRangesAdminAccessAndExportNoPatientData() throws Exception {
        String admin = login("admin@emergencylink.demo");
        JsonNode summary = get(admin, "/api/admin/reports/summary");
        assertTrue(summary.get("volumeByDay").size() > 0);
        assertTrue(summary.has("responseTime"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/reports/summary?from=2026-10-10&to=2026-10-01")
                .header("Authorization", "Bearer " + admin)).andExpect(status().isBadRequest());
        MvcResult csv = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/reports/export/hospitals")
                .header("Authorization", "Bearer " + admin)).andExpect(status().isOk()).andReturn();
        String body = csv.getResponse().getContentAsString();
        assertTrue(body.contains("Demonstration data"));
        assertFalse(body.contains("DEMO-PT"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/reports/export/nonsense")
                .header("Authorization", "Bearer " + admin)).andExpect(status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/admin/reports/summary")
                .header("Authorization", "Bearer " + login("deccan.receiver@emergencylink.demo"))).andExpect(status().isForbidden());
    }

    @Test
    void savedFiltersArePrivateToTheirOwner() throws Exception {
        String a = login("medchal.referrer@emergencylink.demo");
        String b = login("deccan.receiver@emergencylink.demo");
        JsonNode saved = call("POST", a, "/api/saved-filters", "{\"name\":\"Critical only\",\"scope\":\"referrals\",\"filter\":\"{\\\"urgency\\\":\\\"CRITICAL\\\"}\"}", 200);
        long id = saved.get("id").asLong();
        assertTrue(get(a, "/api/saved-filters?scope=referrals").size() >= 1);
        assertEquals(0, get(b, "/api/saved-filters?scope=referrals").size());
        call("DELETE", b, "/api/saved-filters/" + id, null, 404);
        call("POST", a, "/api/saved-filters", "{\"name\":\"Bad\",\"scope\":\"referrals\",\"filter\":\"not json\"}", 400);
        call("POST", a, "/api/saved-filters", "{\"name\":\"Bad\",\"scope\":\"users\",\"filter\":\"{}\"}", 400);
        call("DELETE", a, "/api/saved-filters/" + id, null, 204);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/saved-filters?scope=referrals")).andExpect(status().isUnauthorized());
    }

    @Test
    void refusedAdminActionsAreAuditedAsRejected() throws Exception {
        String referrer = login("medchal.referrer@emergencylink.demo");
        String receiver = login("deccan.receiver@emergencylink.demo");
        String admin = login("admin@emergencylink.demo");
        long deccan = get(receiver, "/api/hospitals/mine").get("id").asLong();
        String body = "{\"patientName\":\"DEMO-PT-REJ\",\"age\":40,\"emergencyCondition\":\"Rejected audit test\",\"urgency\":\"URGENT\","
                + "\"requiredSpecialty\":\"Cardiology\",\"facilities\":[],\"icuRequired\":false,\"ventilatorRequired\":false,\"oxygenRequired\":false,\"bloodRequired\":false}";
        long id = call("POST", referrer, "/api/referrals", body, 201).get("id").asLong();
        call("POST", referrer, "/api/referrals/" + id + "/send", "{\"hospitalId\":" + deccan + "}", 200);
        call("POST", admin, "/api/admin/referrals/" + id + "/cancel", "{\"reason\":\"Duplicate\"}", 200);
        call("POST", admin, "/api/admin/referrals/" + id + "/cancel", "{\"reason\":\"Again\"}", 409);
        JsonNode rejected = get(admin, "/api/admin/audit?outcome=REJECTED&action=REFERRAL_ADMIN_CANCELLED");
        assertTrue(rejected.get("content").size() > 0);
        assertEquals("REJECTED", rejected.get("content").get(0).get("outcome").asText());
    }
}
