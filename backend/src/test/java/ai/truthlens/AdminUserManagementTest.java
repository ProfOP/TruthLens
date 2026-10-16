package ai.truthlens;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * SRS §4/§10 admin user management over real HTTP: role boundaries for direct API calls,
 * the safe single-account detail response, self-change and invalid-role denials, audit
 * recording, and role persistence across a new login (the old JWT keeps the old role).
 */
@SpringBootTest(properties = "truthlens.jobs.sync=true")
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "SPRING_DATASOURCE_PASSWORD", matches = ".+")
class AdminUserManagementTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @MockitoBean AiGateway ai;

  private String suffix;

  @BeforeEach
  void mocks() throws Exception {
    suffix = UUID.randomUUID().toString().substring(0, 8);
    when(ai.classifyArticle(anyMap())).thenReturn(JsonNodeFactory.instance.objectNode().put("label", "FAKE").put("model", "WELFake DistilBERT"));
    when(ai.extractClaims(anyMap())).thenReturn(JsonNodeFactory.instance.objectNode().set("claims", JsonNodeFactory.instance.arrayNode()));
    when(ai.verify(anyString())).thenReturn(JsonNodeFactory.instance.objectNode().put("verdict", "INSUFFICIENT"));
    when(ai.shap(anyMap())).thenReturn(JsonNodeFactory.instance.objectNode().put("status", "generated"));
    when(ai.classifyImage(any())).thenReturn(JsonNodeFactory.instance.objectNode().put("verdict", "AUTHENTIC"));
    when(ai.gradcam(any())).thenReturn(JsonNodeFactory.instance.objectNode().put("status", "generated"));
  }

  @Test
  void adminUserAdministration() throws Exception {
    String adminEmail = "au-admin-" + suffix + "@example.com";
    String targetEmail = "au-target-" + suffix + "@example.com";
    String userEmail = "au-user-" + suffix + "@example.com";
    String checkerEmail = "au-fc-" + suffix + "@example.com";
    for (String email : new String[] {adminEmail, targetEmail, userEmail, checkerEmail}) {
      mvc.perform(post("/api/v1/users/register").contentType(MediaType.APPLICATION_JSON)
              .content(json.writeValueAsString(Map.of("name", "AU", "email", email, "password", "password123"))))
          .andExpect(status().isCreated());
    }
    promote(adminEmail, "ADMIN");
    promote(checkerEmail, "FACT_CHECKER");
    String adminToken = login(adminEmail);
    String userToken = login(userEmail);
    String checkerToken = login(checkerEmail);
    String targetToken = login(targetEmail);
    UUID targetId = userId(targetEmail);
    UUID adminId = userId(adminEmail);

    // Registration cannot grant privilege: every new account started as USER.
    mvc.perform(get("/api/v1/users/profile").header("Authorization", "Bearer " + targetToken))
        .andExpect(jsonPath("$.role").value("USER"));

    // Non-admins are denied account management even when calling the API directly.
    for (String token : new String[] {userToken, checkerToken}) {
      mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
      mvc.perform(get("/api/v1/admin/users/" + targetId).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
      mvc.perform(patch("/api/v1/admin/users/" + targetId).header("Authorization", "Bearer " + token)
              .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("role", "ADMIN"))))
          .andExpect(status().isForbidden());
    }
    // No credential material reaches an unauthenticated caller.
    mvc.perform(get("/api/v1/admin/users/" + targetId)).andExpect(status().isUnauthorized());

    // ADMIN reads a single account: profile fields only, no password material, no analyses.
    mvc.perform(get("/api/v1/admin/users/" + targetId).header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(targetId.toString()))
        .andExpect(jsonPath("$.name").value("AU"))
        .andExpect(jsonPath("$.email").value(targetEmail))
        .andExpect(jsonPath("$.role").value("USER"))
        .andExpect(jsonPath("$.active").value(true))
        .andExpect(jsonPath("$.created_at").exists())
        .andExpect(jsonPath("$.password_hash").doesNotExist())
        .andExpect(jsonPath("$.password").doesNotExist())
        .andExpect(jsonPath("$.analyses").doesNotExist());

    mvc.perform(get("/api/v1/admin/users/" + UUID.randomUUID()).header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isNotFound());

    // The signed-in administrator cannot edit their own account.
    mvc.perform(patch("/api/v1/admin/users/" + adminId).header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("role", "USER"))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("Administrators cannot change their own role or status"));

    // Only the whitelisted roles are accepted.
    mvc.perform(patch("/api/v1/admin/users/" + targetId).header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("role", "SUPERUSER"))))
        .andExpect(status().isBadRequest());
    mvc.perform(patch("/api/v1/admin/users/" + targetId).header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("role", "GUEST"))))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/admin/users/" + targetId).header("Authorization", "Bearer " + adminToken))
        .andExpect(jsonPath("$.role").value("USER"));

    int auditBefore = auditCount(targetId);

    // Promotion persists, is reflected in the detail response, and is audited.
    mvc.perform(patch("/api/v1/admin/users/" + targetId).header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("role", "FACT_CHECKER"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("FACT_CHECKER"))
        .andExpect(jsonPath("$.password_hash").doesNotExist());
    mvc.perform(get("/api/v1/admin/users/" + targetId).header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("FACT_CHECKER"));
    Assertions.assertEquals(auditBefore + 1, auditCount(targetId));

    // Role changes take effect at once for API authorization: JwtAuthenticationFilter re-reads
    // role/active from the database on every request, so the token issued before the promotion
    // already reaches the fact-checker endpoint. Re-login is only needed to refresh the role the
    // interface shows from the cached profile.
    mvc.perform(get("/api/v1/reviews/queue").header("Authorization", "Bearer " + targetToken))
        .andExpect(status().isOk());
    String refreshed = login(targetEmail);
    mvc.perform(post("/api/v1/users/login").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("email", targetEmail, "password", "password123"))))
        .andExpect(jsonPath("$.user.role").value("FACT_CHECKER"));
    mvc.perform(get("/api/v1/reviews/queue").header("Authorization", "Bearer " + refreshed))
        .andExpect(status().isOk());
    // The new role does not leak administration rights.
    mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + refreshed))
        .andExpect(status().isForbidden());

    // Demotion works again through the same endpoint while other administrators remain active.
    mvc.perform(patch("/api/v1/admin/users/" + targetId).header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("role", "USER"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("USER"));
    mvc.perform(get("/api/v1/admin/users/" + targetId).header("Authorization", "Bearer " + adminToken))
        .andExpect(jsonPath("$.role").value("USER"));

    // Access is revoked immediately when an account is deactivated, and restored afterwards.
    mvc.perform(patch("/api/v1/admin/users/" + targetId).header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("active", false))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(false));
    mvc.perform(get("/api/v1/users/profile").header("Authorization", "Bearer " + refreshed))
        .andExpect(status().isUnauthorized());
    mvc.perform(patch("/api/v1/admin/users/" + targetId).header("Authorization", "Bearer " + adminToken)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("active", true))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.active").value(true));
    mvc.perform(get("/api/v1/users/profile").header("Authorization", "Bearer " + refreshed))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("USER"));

    // Existing test accounts are left active, and no account was deleted by this run.
    mvc.perform(get("/api/v1/users/profile").header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("ADMIN"));
    mvc.perform(get("/api/v1/users/profile").header("Authorization", "Bearer " + refreshed))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.role").value("USER"));
  }

  /**
   * SRS §4 Administrator "monitor usage": the owner-filtered, paginated analysis history of one
   * account. Non-administrative roles are denied at the HTTP boundary, the response carries status
   * columns only (no input text, no asset or storage paths, no credential material), rows never
   * cross the owner boundary, and an unknown type is rejected instead of silently ignored.
   */
  @Test
  void administratorReadsOneAccountsAnalysisHistory() throws Exception {
    String adminEmail = "ah-admin-" + suffix + "@example.com";
    String ownerEmail = "ah-owner-" + suffix + "@example.com";
    String otherEmail = "ah-other-" + suffix + "@example.com";
    String checkerEmail = "ah-fc-" + suffix + "@example.com";
    for (String email : new String[] {adminEmail, ownerEmail, otherEmail, checkerEmail}) {
      mvc.perform(post("/api/v1/users/register").contentType(MediaType.APPLICATION_JSON)
              .content(json.writeValueAsString(Map.of("name", "AH", "email", email, "password", "password123"))))
          .andExpect(status().isCreated());
    }
    promote(adminEmail, "ADMIN");
    promote(checkerEmail, "FACT_CHECKER");
    String adminToken = login(adminEmail);
    String ownerToken = login(ownerEmail);
    String otherToken = login(otherEmail);
    String checkerToken = login(checkerEmail);
    UUID owner = userId(ownerEmail);
    String ownerText = submit(ownerToken, "/analysis/text", Map.of("title", "Owner", "body", "AH owner article " + suffix));
    submit(ownerToken, "/analysis/claim", Map.of("claim", "AH owner claim " + suffix));
    String otherText = submit(otherToken, "/analysis/text", Map.of("title", "Other", "body", "AH other article " + suffix));

    // Only ADMIN may read another account's history, and never without a token.
    mvc.perform(get("/api/v1/admin/users/" + owner + "/analyses").header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/admin/users/" + owner + "/analyses").header("Authorization", "Bearer " + checkerToken))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/admin/users/" + owner + "/analyses")).andExpect(status().isUnauthorized());

    var page = mvc.perform(get("/api/v1/admin/users/" + owner + "/analyses").header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.owner_id").value(owner.toString()))
        .andExpect(jsonPath("$.type").value("ALL"))
        .andExpect(jsonPath("$.total").value(2))
        .andExpect(jsonPath("$.size").value(10))
        .andExpect(jsonPath("$.rows.length()").value(2))
        .andReturn().getResponse().getContentAsString();
    com.fasterxml.jackson.databind.JsonNode rows = json.readTree(page).get("rows");
    for (com.fasterxml.jackson.databind.JsonNode row : rows) {
      Assertions.assertFalse(row.has("input_text"), "history rows must not carry submitted content");
      Assertions.assertFalse(row.has("storage_path"), "history rows must not expose storage paths");
      Assertions.assertFalse(row.has("password") || row.has("password_hash"));
      Assertions.assertTrue(row.has("id") && row.has("input_type") && row.has("status") && row.has("created_at"));
    }

    // The query is filtered to that owner: the two rows are exactly this account's analyses.
    java.util.Set<String> ids = new java.util.HashSet<>();
    rows.forEach(row -> ids.add(row.get("id").asText()));
    Assertions.assertTrue(ids.contains(ownerText));
    mvc.perform(get("/api/v1/admin/users/" + userId(otherEmail) + "/analyses").header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.rows[0].id").value(otherText));

    // Type filter, rejection of an unknown type, and pagination over the server-capped size.
    mvc.perform(get("/api/v1/admin/users/" + owner + "/analyses?type=TEXT").header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.type").value("TEXT"))
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.rows[0].input_type").value("TEXT"));
    mvc.perform(get("/api/v1/admin/users/" + owner + "/analyses?type=text").header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));
    mvc.perform(get("/api/v1/admin/users/" + owner + "/analyses?type=VOYAGE").header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/admin/users/" + owner + "/analyses?page=1&size=1").header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(2))
        .andExpect(jsonPath("$.page").value(1))
        .andExpect(jsonPath("$.rows.length()").value(1));
    mvc.perform(get("/api/v1/admin/users/" + UUID.randomUUID() + "/analyses").header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isNotFound());
  }

  private String submit(String token, String path, Map<String, String> body) throws Exception {
    MvcResult result = mvc.perform(post("/api/v1" + path).header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
        .andExpect(status().isAccepted()).andReturn();
    return json.readTree(result.getResponse().getContentAsString()).get("id").asText();
  }

  private int auditCount(UUID targetId) {
    Integer count = db.queryForObject("SELECT count(*) FROM audit_events WHERE action=? AND entity_id=?", Integer.class, "ADMIN_USER_UPDATE", targetId);
    return count == null ? 0 : count;
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
