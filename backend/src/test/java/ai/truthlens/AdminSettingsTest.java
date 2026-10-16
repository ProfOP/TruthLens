package ai.truthlens;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * SRS §4 administrator system control over real HTTP: the role boundary of the setting endpoint,
 * validation of the accepted values, the audit record, persistence in the database rather than in
 * process memory, and what the pause actually does — every new submission is refused with 503
 * while existing reports stay readable. The setting is restored to open in a finally block because
 * the value is global state shared by the real database.
 */
@SpringBootTest(properties = "truthlens.jobs.sync=true")
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "SPRING_DATASOURCE_PASSWORD", matches = ".+")
class AdminSettingsTest {
  private static final String ACTION = "ADMIN_SYSTEM_SETTING_UPDATE";

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @MockitoBean AiGateway ai;

  private String suffix;
  private String adminToken;
  private String userToken;
  private UUID ownerId;

  @BeforeEach
  void accountsAndAi() throws Exception {
    suffix = UUID.randomUUID().toString().substring(0, 8);
    when(ai.classifyArticle(anyMap())).thenReturn(JsonNodeFactory.instance.objectNode().put("label", "FAKE").put("model", "WELFake DistilBERT"));
    when(ai.extractClaims(anyMap())).thenReturn(JsonNodeFactory.instance.objectNode().set("claims", JsonNodeFactory.instance.arrayNode()));
    when(ai.verify(anyString())).thenReturn(JsonNodeFactory.instance.objectNode().put("verdict", "INSUFFICIENT"));
    when(ai.shap(anyMap())).thenReturn(JsonNodeFactory.instance.objectNode().put("status", "generated"));
    when(ai.classifyImage(any())).thenReturn(JsonNodeFactory.instance.objectNode().put("verdict", "AUTHENTIC"));
    when(ai.gradcam(any())).thenReturn(JsonNodeFactory.instance.objectNode().put("status", "generated"));

    String adminEmail = "as-admin-" + suffix + "@example.com";
    String userEmail = "as-user-" + suffix + "@example.com";
    String checkerEmail = "as-fc-" + suffix + "@example.com";
    for (String email : new String[] {adminEmail, userEmail, checkerEmail}) {
      mvc.perform(post("/api/v1/users/register").contentType(MediaType.APPLICATION_JSON)
              .content(json.writeValueAsString(Map.of("name", "AS", "email", email, "password", "password123"))))
          .andExpect(status().isCreated());
    }
    promote(adminEmail, "ADMIN");
    promote(checkerEmail, "FACT_CHECKER");
    adminToken = login(adminEmail);
    userToken = login(userEmail);
    ownerId = userId(userEmail);
  }

  @Test
  void administratorPausesAndResumesNewAnalyses() throws Exception {
    String baseline = submit(userToken, "/analysis/text", Map.of("title", "Open", "body", "AS baseline " + suffix));
    int auditBefore = auditCount();
    int analysesBefore = analysisCount();

    // Only an administrator may read or write the setting; the boundary is enforced by the
    // security filter, independently of the role check inside the controller.
    mvc.perform(get("/api/v1/admin/settings")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/admin/settings").header("Authorization", "Bearer " + userToken))
        .andExpect(status().isForbidden());
    mvc.perform(patch("/api/v1/admin/settings").header("Authorization", "Bearer " + userToken)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("submissionsEnabled", false))))
        .andExpect(status().isForbidden());
    String checkerToken = login("as-fc-" + suffix + "@example.com");
    mvc.perform(get("/api/v1/admin/settings").header("Authorization", "Bearer " + checkerToken))
        .andExpect(status().isForbidden());
    mvc.perform(patch("/api/v1/admin/settings").header("Authorization", "Bearer " + checkerToken)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("submissionsEnabled", false))))
        .andExpect(status().isForbidden());
    Assertions.assertEquals("true", persistedValue());

    try {
      // An invalid value is refused and changes nothing.
      mvc.perform(patch("/api/v1/admin/settings").header("Authorization", "Bearer " + adminToken)
              .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("submissionsEnabled", "maybe"))))
          .andExpect(status().isBadRequest());
      mvc.perform(patch("/api/v1/admin/settings").header("Authorization", "Bearer " + adminToken)
              .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of())))
          .andExpect(status().isBadRequest());
      Assertions.assertEquals("true", persistedValue());
      Assertions.assertEquals(auditBefore, auditCount());

      mvc.perform(patch("/api/v1/admin/settings").header("Authorization", "Bearer " + adminToken)
              .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("submissionsEnabled", false))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.submissionsEnabled").value(false))
          .andExpect(jsonPath("$.updatedBy").value(adminId().toString()))
          .andExpect(jsonPath("$.updatedAt").exists())
          .andExpect(jsonPath("$.settingKey").doesNotExist())
          .andExpect(jsonPath("$.password").doesNotExist());
      Assertions.assertEquals("false", persistedValue());
      mvc.perform(get("/api/v1/admin/settings").header("Authorization", "Bearer " + adminToken))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.submissionsEnabled").value(false));
      Assertions.assertEquals(auditBefore + 1, auditCount());

      // The published status is readable by any signed-in user so the interface can explain the pause.
      mvc.perform(get("/api/v1/system/status")).andExpect(status().isUnauthorized());
      mvc.perform(get("/api/v1/system/status").header("Authorization", "Bearer " + userToken))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.submissionsEnabled").value(false));

      // Every submission path is refused, no row is created, and the refusal names the cause.
      mvc.perform(post("/api/v1/analysis/text").header("Authorization", "Bearer " + userToken)
              .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("body", "AS paused " + suffix))))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.error").value(SystemSettingsService.PAUSE_MESSAGE));
      mvc.perform(post("/api/v1/analysis/claim").header("Authorization", "Bearer " + userToken)
              .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("claim", "AS paused " + suffix))))
          .andExpect(status().isServiceUnavailable());
      byte[] png = java.util.Base64.getDecoder()
          .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=");
      mvc.perform(multipart("/api/v1/analysis/image")
              .file(new MockMultipartFile("file", "dot.png", "image/png", png))
              .header("Authorization", "Bearer " + userToken))
          .andExpect(status().isServiceUnavailable());
      Assertions.assertEquals(analysesBefore, analysisCount());

      // Pausing new work does not lock anyone out of work already done.
      mvc.perform(get("/api/v1/analysis/" + baseline).header("Authorization", "Bearer " + userToken))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.id").value(baseline));
      mvc.perform(get("/api/v1/analysis").header("Authorization", "Bearer " + userToken))
          .andExpect(status().isOk());
      mvc.perform(get("/api/v1/dashboard").header("Authorization", "Bearer " + userToken))
          .andExpect(status().isOk());

      // The decision survives a fresh sign-in: it is stored, not held in the session.
      String relogin = login("as-user-" + suffix + "@example.com");
      mvc.perform(get("/api/v1/system/status").header("Authorization", "Bearer " + relogin))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.submissionsEnabled").value(false));

      mvc.perform(patch("/api/v1/admin/settings").header("Authorization", "Bearer " + adminToken)
              .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("submissionsEnabled", true))))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.submissionsEnabled").value(true));
      Assertions.assertEquals("true", persistedValue());
      Assertions.assertEquals(auditBefore + 2, auditCount());
      submit(userToken, "/analysis/text", Map.of("title", "Resumed", "body", "AS resumed " + suffix));
      mvc.perform(get("/api/v1/system/status").header("Authorization", "Bearer " + userToken))
          .andExpect(jsonPath("$.submissionsEnabled").value(true));
    } finally {
      mvc.perform(patch("/api/v1/admin/settings").header("Authorization", "Bearer " + adminToken)
          .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("submissionsEnabled", true))));
      Assertions.assertEquals("true", persistedValue());
    }
  }

  private String submit(String token, String path, Map<String, String> body) throws Exception {
    MvcResult result = mvc.perform(post("/api/v1" + path).header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
        .andExpect(status().isAccepted()).andReturn();
    return json.readTree(result.getResponse().getContentAsString()).get("id").asText();
  }

  private String persistedValue() {
    return db.queryForObject("SELECT setting_value FROM system_settings WHERE setting_key=?",
        String.class, SystemSettingsService.SUBMISSIONS_ENABLED);
  }

  private int auditCount() {
    Integer count = db.queryForObject("SELECT count(*) FROM audit_events WHERE action=?", Integer.class, ACTION);
    return count == null ? 0 : count;
  }

  private int analysisCount() {
    Integer count = db.queryForObject("SELECT count(*) FROM analyses WHERE owner_id=?", Integer.class, ownerId);
    return count == null ? 0 : count;
  }

  private UUID adminId() {
    return userId("as-admin-" + suffix + "@example.com");
  }

  private UUID userId(String email) {
    return UUID.fromString(db.queryForObject("SELECT id::text FROM users WHERE email=?", String.class, email.toLowerCase()));
  }

  private void promote(String email, String role) {
    db.update("UPDATE users SET role=? WHERE email=?", role, email.toLowerCase());
  }

  private String login(String email) throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/users/login").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("email", email, "password", "password123"))))
        .andExpect(status().isOk())
        .andReturn();
    return json.readTree(result.getResponse().getContentAsString()).get("token").asText();
  }
}
