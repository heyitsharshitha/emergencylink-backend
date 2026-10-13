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
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Administrator corrections, flags, notes, exceptions and access control against the seeded in-memory database. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminOperationsIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String login(String email) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"Demo@1234\"}")).andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    private JsonNode send(String method, String token, String url, String body, int expected) throws Exception {
        var builder = switch (method) {
            case "PUT" -> put(url);
            case "PATCH" -> patch(url);
            default -> post(url);
        };
        MvcResult r = mvc.perform(builder.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
        String c = r.getResponse().getContentAsString();
        return c.isBlank() ? null : json.readTree(c);
    }

    private JsonNode getJson(String token, String url) throws Exception {
        MvcResult r = mvc.perform(get(url).header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    private long createSentReferral(String referrer, long targetId) throws Exception {
        String body = "{\"patientName\":\"DEMO-PT-ADMIN\",\"age\":50,\"emergencyCondition\":\"Admin test\",\"urgency\":\"URGENT\","
                + "\"requiredSpecialty\":\"Cardiology\",\"facilities\":[],\"icuRequired\":false,\"ventilatorRequired\":false,"
                + "\"oxygenRequired\":false,\"bloodRequired\":false}";
        long id = send("POST", referrer, "/api/referrals", body, 201).get("id").asLong();
        send("POST", referrer, "/api/referrals/" + id + "/send", "{\"hospitalId\":" + targetId + "}", 200);
        return id;
    }

    @Test
    void adminCorrectionsNeedAReasonAreAuditedAndNotifyTheHospitals() throws Exception {
        String referrer = login("medchal.referrer@emergencylink.demo");
        String receiver = login("deccan.receiver@emergencylink.demo");
        String admin = login("admin@emergencylink.demo");
        long deccanId = getJson(receiver, "/api/hospitals/mine").get("id").asLong();
        long id = createSentReferral(referrer, deccanId);
        String url = "/api/admin/referrals/" + id;

        // A reason is mandatory and at least one field must change.
        send("PUT", admin, url, "{\"urgency\":\"CRITICAL\"}", 400);
        send("PUT", admin, url, "{\"urgency\":\"URGENT\",\"reason\":\"No change\"}", 400);

        JsonNode edited = send("PUT", admin, url, "{\"urgency\":\"CRITICAL\",\"reason\":\"Entered at the wrong urgency\"}", 200);
        assertEquals("CRITICAL", edited.get("urgency").asText());

        JsonNode timeline = getJson(referrer, "/api/referrals/" + id + "/timeline");
        assertEquals("ADMIN_EDITED", timeline.get(timeline.size() - 1).get("eventType").asText());
        JsonNode audit = getJson(admin, "/api/admin/audit?action=REFERRAL_ADMIN_EDITED");
        assertTrue(audit.get("content").size() > 0);
        assertTrue(getJson(receiver, "/api/notifications/unread-count").get("unread").asInt() > 0);
    }

    @Test
    void hospitalUsersCannotUseAdminOperations() throws Exception {
        String referrer = login("medchal.referrer@emergencylink.demo");
        String receiver = login("deccan.receiver@emergencylink.demo");
        long deccanId = getJson(receiver, "/api/hospitals/mine").get("id").asLong();
        long id = createSentReferral(referrer, deccanId);
        send("PUT", referrer, "/api/admin/referrals/" + id, "{\"urgency\":\"STANDARD\",\"reason\":\"x\"}", 403);
        send("POST", receiver, "/api/admin/referrals/" + id + "/cancel", "{\"reason\":\"x\"}", 403);
        mvc.perform(get("/api/admin/exceptions").header("Authorization", "Bearer " + referrer)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/overview")).andExpect(status().isUnauthorized());
    }

    @Test
    void adminCancelFollowsTheStateMachineAndFlagsStayInternal() throws Exception {
        String referrer = login("medchal.referrer@emergencylink.demo");
        String receiver = login("deccan.receiver@emergencylink.demo");
        String admin = login("admin@emergencylink.demo");
        long deccanId = getJson(receiver, "/api/hospitals/mine").get("id").asLong();
        long id = createSentReferral(referrer, deccanId);
        String url = "/api/admin/referrals/" + id;

        send("POST", admin, url + "/flag", "{\"reason\":\"Check hospital assignment\"}", 200);
        send("POST", admin, url + "/flag", "{\"reason\":\"again\"}", 409);
        assertTrue(getJson(admin, "/api/referrals/" + id).get("adminFlagged").asBoolean());
        // Hospitals never see the flag.
        assertFalse(getJson(referrer, "/api/referrals/" + id).get("adminFlagged").asBoolean());
        send("POST", admin, url + "/notes", "{\"body\":\"Called the receiving hospital\"}", 200);
        assertTrue(getJson(admin, url + "/notes").size() >= 2);
        send("POST", admin, url + "/resolve-flag", "{\"reason\":\"Confirmed correct\"}", 200);

        send("POST", admin, url + "/cancel", "{\"reason\":\"Duplicate record\"}", 200);
        send("POST", admin, url + "/cancel", "{\"reason\":\"Again\"}", 409); // already cancelled
        send("PUT", admin, url, "{\"urgency\":\"STANDARD\",\"reason\":\"Too late\"}", 409);
    }

    @Test
    void overviewAndExceptionsAreDerivedFromRecords() throws Exception {
        String admin = login("admin@emergencylink.demo");
        JsonNode overview = getJson(admin, "/api/admin/overview");
        assertTrue(overview.get("hospitalsTotal").asLong() > 0);
        assertTrue(overview.get("byStatus").has("SENT"));
        JsonNode ex = getJson(admin, "/api/admin/exceptions");
        assertEquals(ex.get("total").asInt(), ex.get("items").size());
    }

    @Test
    void deactivatingAUserRequiresAReason() throws Exception {
        String admin = login("admin@emergencylink.demo");
        long userId = getJson(admin, "/api/admin/users?q=medchal.referrer").get("content").get(0).get("id").asLong();
        send("PATCH", admin, "/api/admin/users/" + userId + "/active", "{\"active\":false}", 400);
        send("PATCH", admin, "/api/admin/users/" + userId + "/active", "{\"active\":false,\"reason\":\"Left the organisation\"}", 200);
        send("PATCH", admin, "/api/admin/users/" + userId + "/active", "{\"active\":true}", 200);
    }
}
