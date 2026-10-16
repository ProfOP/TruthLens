package ai.truthlens;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Map;
import java.util.UUID;
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
 * SRS role + reporting coverage: USER/FACT_CHECKER/ADMIN boundaries (including
 * direct API calls), cross-user report isolation, report contents, and the rule
 * that a human review never changes the stored model prediction.
 */
@SpringBootTest(properties = "truthlens.jobs.sync=true")
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "SPRING_DATASOURCE_PASSWORD", matches = ".+")
class RoleReportTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @MockitoBean AiGateway ai;

  private String suffix;

  @BeforeEach
  void mocks() throws Exception {
    suffix = UUID.randomUUID().toString().substring(0, 8);
    when(ai.classifyArticle(anyMap())).thenReturn(JsonNodeFactory.instance.objectNode()
        .put("label", "FAKE")
        .put("model", "WELFake DistilBERT")
        .put("credibility_score", 4.74)
        .set("probabilities", JsonNodeFactory.instance.objectNode().put("REAL", 0.047).put("FAKE", 0.953)));
    when(ai.extractClaims(anyMap())).thenReturn(JsonNodeFactory.instance.objectNode()
        .set("claims", JsonNodeFactory.instance.arrayNode()
            .add(JsonNodeFactory.instance.objectNode().put("text", "Helsinki is the capital of Finland.").put("sentence_index", 0).put("relevance", 0.8))));
    var evidenceNode = JsonNodeFactory.instance.objectNode()
        .put("page_id", "Helsinki").put("sentence_id", 0)
        .put("text", "Helsinki is the capital of Finland.")
        .put("similarity", 0.82).put("overlap", 0.5);
    evidenceNode.set("nli_scores", JsonNodeFactory.instance.arrayNode().add(0.91).add(0.05).add(0.04));
    evidenceNode.set("nli_labels", JsonNodeFactory.instance.arrayNode().add("ENTAILMENT").add("CONTRADICTION").add("NEUTRAL"));
    evidenceNode.put("selected", true);
    var verifyNode = JsonNodeFactory.instance.objectNode()
        .put("verdict", "SUPPORTED")
        .put("model", "FEVER Iteration 2");
    verifyNode.set("evidence", JsonNodeFactory.instance.arrayNode().add(evidenceNode));
    when(ai.verify(anyString())).thenReturn(verifyNode);
    when(ai.shap(anyMap())).thenReturn(JsonNodeFactory.instance.objectNode().put("status", "generated").put("method", "SHAP"));
    when(ai.classifyImage(any())).thenReturn(JsonNodeFactory.instance.objectNode().put("verdict", "AUTHENTIC"));
    when(ai.gradcam(any())).thenReturn(JsonNodeFactory.instance.objectNode().put("status", "generated"));
  }

  @Test
  void roleBoundariesAndReports() throws Exception {
    String userEmail = "rr-user-" + suffix + "@example.com";
    String fcEmail = "rr-fc-" + suffix + "@example.com";
    String adminEmail = "rr-admin-" + suffix + "@example.com";
    String otherEmail = "rr-other-" + suffix + "@example.com";
    for (String email : new String[] {userEmail, fcEmail, adminEmail, otherEmail}) {
      mvc.perform(post("/api/v1/users/register").contentType(MediaType.APPLICATION_JSON)
              .content(json.writeValueAsString(Map.of("name", "RR", "email", email, "password", "password123"))))
          .andExpect(status().isCreated());
    }
    promote(fcEmail, "FACT_CHECKER");
    promote(adminEmail, "ADMIN");
    String userToken = login(userEmail);
    String otherToken = login(otherEmail);
    String fcToken = login(fcEmail);
    String adminToken = login(adminEmail);

    // USER submits a text analysis; it completes synchronously under sync jobs.
    MvcResult created = mvc.perform(post("/api/v1/analysis/text").header("Authorization", "Bearer " + userToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("title", "T", "body", "Helsinki is the capital of Finland."))))
        .andExpect(status().isAccepted())
        .andReturn();
    String analysisId = json.readTree(created.getResponse().getContentAsString()).get("id").asText();

    // USER is denied review + admin endpoints even when calling the API directly.
    mvc.perform(get("/api/v1/reviews/queue").header("Authorization", "Bearer " + userToken)).andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + userToken)).andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/admin/models").header("Authorization", "Bearer " + userToken)).andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/admin/health").header("Authorization", "Bearer " + userToken)).andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/reviews/" + analysisId).header("Authorization", "Bearer " + userToken)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("status", "CONFIRMED", "notes", "x"))))
        .andExpect(status().isForbidden());

    // Cross-user isolation: another USER cannot read the analysis, its report, or its asset.
    mvc.perform(get("/api/v1/analysis/" + analysisId).header("Authorization", "Bearer " + otherToken)).andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/reports/" + analysisId).header("Authorization", "Bearer " + otherToken)).andExpect(status().isForbidden());

    // Owner report contains status/date/model metadata, disclaimer, and separate human review.
    mvc.perform(get("/api/v1/reports/" + analysisId).header("Authorization", "Bearer " + userToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(analysisId))
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.input_type").value("TEXT"))
        .andExpect(jsonPath("$.created_at").exists())
        .andExpect(jsonPath("$.model_versions.analysis_model").value("WELFake DistilBERT"))
        .andExpect(jsonPath("$.model_versions.verification_model").value("FEVER Iteration 2"))
        .andExpect(jsonPath("$.model_output.label").value("FAKE"))
        .andExpect(jsonPath("$.claims[0].verdict").value("SUPPORTED"))
        .andExpect(jsonPath("$.claims[0].evidence[0].selected").value(true))
        .andExpect(jsonPath("$.human_review.reviews").isArray())
        .andExpect(jsonPath("$.disclaimer").isString());

    // Owner sends it to review; FACT_CHECKER can read + review it but not use ADMIN endpoints.
    mvc.perform(post("/api/v1/analysis/" + analysisId + "/review-request").header("Authorization", "Bearer " + userToken))
        .andExpect(status().isOk());
    mvc.perform(get("/api/v1/reviews/queue").header("Authorization", "Bearer " + fcToken)).andExpect(status().isOk());
    mvc.perform(get("/api/v1/reports/" + analysisId).header("Authorization", "Bearer " + fcToken)).andExpect(status().isOk());
    mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + fcToken)).andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/admin/health").header("Authorization", "Bearer " + fcToken)).andExpect(status().isForbidden());
    mvc.perform(post("/api/v1/reviews/" + analysisId).header("Authorization", "Bearer " + fcToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("status", "DISPUTED", "notes", "Needs a second source."))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.note").value("Human review does not overwrite model predictions."));

    // The review changed review_status but NOT the stored model prediction.
    mvc.perform(get("/api/v1/reports/" + analysisId).header("Authorization", "Bearer " + userToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.model_output.label").value("FAKE"))
        .andExpect(jsonPath("$.human_review.review_status").value("DISPUTED"))
        .andExpect(jsonPath("$.human_review.reviews[0].status").value("DISPUTED"))
        .andExpect(jsonPath("$.human_review.reviews[0].notes").value("Needs a second source."));

    // ADMIN can read the report and administrative information.
    mvc.perform(get("/api/v1/reports/" + analysisId).header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk());
    mvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk());
    mvc.perform(get("/api/v1/admin/models").header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk());
    mvc.perform(get("/api/v1/admin/health").header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk());
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
