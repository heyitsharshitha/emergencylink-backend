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

/** End-to-end referral lifecycle against an in-memory database seeded with the demo data. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReferralWorkflowIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private String login(String email) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"Demo@1234\"}")).andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString()).get("token").asText();
    }

    private JsonNode postJson(String token, String url, String body, int expected) throws Exception {
        MvcResult r = mvc.perform(post(url).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected)).andReturn();
        String c = r.getResponse().getContentAsString();
        return c.isBlank() ? null : json.readTree(c);
    }

    private JsonNode getJson(String token, String url) throws Exception {
        MvcResult r = mvc.perform(get(url).header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    @Test
    void fullLifecycleWithRoleEnforcement() throws Exception {
        String referrer = login("medchal.referrer@emergencylink.demo");
        String receiver = login("deccan.receiver@emergencylink.demo");
        String otherReceiver = login("hitech.receiver@emergencylink.demo");

        String body = "{\"patientName\":\"DEMO-PT-TEST\",\"age\":40,\"emergencyCondition\":\"Test condition\",\"urgency\":\"URGENT\"," +
                "\"requiredSpecialty\":\"Cardiology\",\"facilities\":[\"CT Scan\"],\"icuRequired\":true,\"ventilatorRequired\":false," +
                "\"oxygenRequired\":true,\"bloodRequired\":false}";
        JsonNode created = postJson(referrer, "/api/referrals", body, 201);
        long id = created.get("id").asLong();
        assertEquals("CREATED", created.get("status").asText());

        // A receiving hospital cannot create referrals.
        postJson(receiver, "/api/referrals", body, 403);

        JsonNode matches = postJson(referrer, "/api/referrals/" + id + "/match", "{}", 200);
        assertTrue(matches.size() > 0);
        assertTrue(matches.get(0).get("score").asInt() >= matches.get(matches.size() - 1).get("score").asInt());

        long deccanId = getJson(receiver, "/api/hospitals/mine").get("id").asLong();
        // Invalid transition: cannot accept before sending.
        postJson(receiver, "/api/referrals/" + id + "/accept", "{}", 404); // not yet visible to the receiver

        postJson(referrer, "/api/referrals/" + id + "/send", "{\"hospitalId\":" + deccanId + "}", 200);

        // A different receiving hospital must not see the referral.
        mvc.perform(get("/api/referrals/" + id).header("Authorization", "Bearer " + otherReceiver)).andExpect(status().isNotFound());

        // Accepting before viewing is an invalid transition.
        postJson(receiver, "/api/referrals/" + id + "/accept", "{}", 409);
        postJson(receiver, "/api/referrals/" + id + "/view", "{}", 200);
        postJson(receiver, "/api/referrals/" + id + "/decline", "{\"note\":\"\"}", 400); // reason required
        postJson(receiver, "/api/referrals/" + id + "/request-info", "{\"note\":\"Please send latest vitals\"}", 200);
        postJson(referrer, "/api/referrals/" + id + "/provide-info", "{\"note\":\"Demo vitals attached\"}", 200);
        JsonNode accepted = postJson(receiver, "/api/referrals/" + id + "/accept", "{\"note\":\"Bed reserved\"}", 200);
        assertEquals("ACCEPTED", accepted.get("status").asText());
        postJson(referrer, "/api/referrals/" + id + "/start-transfer", "{}", 200);
        postJson(receiver, "/api/referrals/" + id + "/complete", "{}", 200);

        JsonNode timeline = getJson(referrer, "/api/referrals/" + id + "/timeline");
        assertTrue(timeline.size() >= 9, "timeline should record every step, was " + timeline.size());
        assertEquals("CREATED", timeline.get(0).get("eventType").asText());
        assertEquals("COMPLETED", timeline.get(timeline.size() - 1).get("eventType").asText());

        assertTrue(getJson(receiver, "/api/notifications/unread-count").get("unread").asInt() > 0);
        assertEquals(2, getJson(referrer, "/api/referrals/" + id + "/messages").size());
    }

    @Test
    void adminEndpointsAreRestrictedToAdmins() throws Exception {
        String referrer = login("medchal.referrer@emergencylink.demo");
        String admin = login("admin@emergencylink.demo");
        mvc.perform(get("/api/admin/audit").header("Authorization", "Bearer " + referrer)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/audit")).andExpect(status().isUnauthorized());
        assertTrue(getJson(admin, "/api/admin/audit").get("content").size() > 0);
        assertTrue(getJson(admin, "/api/dashboard").get("kpis").get("total").asInt() >= 9);
    }

    @Test
    void invalidLoginReturnsCleanError() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"admin@emergencylink.demo\",\"password\":\"wrong\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"bad\"}")).andExpect(status().isBadRequest());
    }
}
