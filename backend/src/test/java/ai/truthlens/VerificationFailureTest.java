package ai.truthlens;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
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
 * SRS failure handling: an AI verification call that fails is a service failure, never an
 * evidence-based INSUFFICIENT result. Covers the three required shapes - a direct claim that fails,
 * an article where one claim fails while another succeeds, and an article where every claim fails -
 * plus what the API, the report, the dashboard and a reopened saved report expose, and how rows
 * already stored by the old fallback are read back.
 *
 * <p>The failure is simulated at the {@link AiGateway} boundary, which is exactly where the real
 * HTTP 503 from {@code /v1/verify} surfaces (see AiGateway.postJson and the 503 codes in
 * ai-service/app.py).
 */
@SpringBootTest(properties = "truthlens.jobs.sync=true")
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "SPRING_DATASOURCE_PASSWORD", matches = ".+")
class VerificationFailureTest {
  private static final String SERVICE_FAILURE = "I/O on http://ai-service:8001/v1/verify: 503 FEVER_UNAVAILABLE";

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @MockitoBean AiGateway ai;

  private String suffix;
  private String userToken;
  private UUID ownerId;

  @BeforeEach
  void account() throws Exception {
    suffix = UUID.randomUUID().toString().substring(0, 8);
    when(ai.classifyArticle(anyMap())).thenReturn(JsonNodeFactory.instance.objectNode()
        .put("label", "FAKE")
        .put("model", "WELFake DistilBERT"));
    when(ai.shap(anyMap())).thenReturn(JsonNodeFactory.instance.objectNode().put("status", "generated"));

    String email = "vf-user-" + suffix + "@example.com";
    mvc.perform(post("/api/v1/users/register").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name", "VF", "email", email, "password", "password123"))))
        .andExpect(status().isCreated());
    userToken = login(email);
    ownerId = UUID.fromString(db.queryForObject("SELECT id::text FROM users WHERE email=?", String.class, email));
  }

  /** (a) A direct claim whose verification call fails: no verdict, component UNAVAILABLE, still not INSUFFICIENT. */
  @Test
  void directClaimFailureStoresNoVerdict() throws Exception {
    when(ai.verify(anyString())).thenThrow(new IllegalStateException(SERVICE_FAILURE));
    String id = submit("/api/v1/analysis/claim", Map.of("claim", "Paris is the capital of France."));

    String analysisStatus = db.queryForObject("SELECT status FROM analyses WHERE id=?", String.class, UUID.fromString(id));
    List<Map<String, Object>> rows = db.queryForList(
        "SELECT verdict,component_status,nli_output FROM claims WHERE analysis_id=?", UUID.fromString(id));
    Assertions.assertEquals(1, rows.size());
    Assertions.assertEquals("COMPLETED", analysisStatus);
    Assertions.assertNull(rows.get(0).get("verdict"), "a failed verification must store no verdict");
    Assertions.assertEquals("UNAVAILABLE", rows.get(0).get("component_status"));
    Assertions.assertEquals(0, evidenceCount(UUID.fromString(id)));

    JsonNode claim = claimOf(fetchAnalysis(id), 0);
    Assertions.assertTrue(claim.get("verdict").isNull(), "the API must not present a verdict for a failed claim");
    Assertions.assertEquals("UNAVAILABLE", claim.path("component_status").asText());
    Assertions.assertEquals("Verification could not be completed.", claim.path("nli_output").path("error").asText());
    Assertions.assertEquals(SERVICE_FAILURE, claim.path("nli_output").path("service_error").asText());

    JsonNode verification = fetchAnalysis(id).path("result_json").path("verification");
    Assertions.assertEquals("UNAVAILABLE", verification.path("status").asText());
    Assertions.assertEquals(1, verification.path("failed_claims").asInt());
  }

  /** (b) One claim fails while the others succeed: their verdicts, evidence and classification survive. */
  @Test
  void partialArticleFailureKeepsSuccessfulResults() throws Exception {
    failingOnlyOn("FAIL");
    String id = submit("/api/v1/analysis/text", Map.of("title", "Mixed article",
        "body", "Article " + suffix + " about two solid claims and one that cannot be checked."));

    JsonNode body = fetchAnalysis(id);
    Assertions.assertEquals("COMPLETED", body.path("status").asText());
    Assertions.assertEquals("FAKE", body.path("result_json").path("label").asText(),
        "the successful WELFake classification must be preserved");
    JsonNode verification = body.path("result_json").path("verification");
    Assertions.assertEquals("PARTIAL", verification.path("status").asText());
    Assertions.assertEquals(1, verification.path("failed_claims").asInt());
    Assertions.assertEquals(3, verification.path("total_claims").asInt());

    Assertions.assertEquals("SUPPORTED", claimByText(body, "Paris is in France").path("verdict").asText());
    Assertions.assertEquals("UNAVAILABLE", claimByText(body, "unverifiable claim").path("component_status").asText());
    Assertions.assertTrue(claimByText(body, "unverifiable claim").path("verdict").isNull());
    Assertions.assertEquals("REFUTED", claimByText(body, "Moon is made of cheese").path("verdict").asText());
    Assertions.assertEquals(1, claimByText(body, "Paris is in France").path("evidence").size(),
        "evidence of the successful claim is kept");
    Assertions.assertEquals(0, claimByText(body, "unverifiable claim").path("evidence").size());
    Assertions.assertEquals("generated", body.path("explanations").get(0).path("status").asText());

    // Reopening the stored report shows the same thing: a failed component, not an evidence finding.
    JsonNode report = fetchReport(id);
    Assertions.assertEquals("PARTIAL", report.path("model_output").path("verification").path("status").asText());
    Assertions.assertEquals("UNAVAILABLE", claimByText(report, "unverifiable claim").path("component_status").asText());
    Assertions.assertTrue(claimByText(report, "unverifiable claim").path("verdict").isNull());
  }

  /** (c) Every claim of an article fails: verification is UNAVAILABLE, never a blanket COMPLETED. */
  @Test
  void allClaimsFailingMarksVerificationUnavailable() throws Exception {
    when(ai.extractClaims(anyMap())).thenReturn(claims("Alpha claim.", "Beta claim.", "Gamma claim."));
    when(ai.verify(anyString())).thenThrow(new IllegalStateException(SERVICE_FAILURE));
    String id = submit("/api/v1/analysis/text", Map.of("title", "All failing", "body", "Body " + suffix));

    JsonNode body = fetchAnalysis(id);
    Assertions.assertEquals("COMPLETED", body.path("status").asText());
    Assertions.assertEquals("FAKE", body.path("result_json").path("label").asText());
    JsonNode verification = body.path("result_json").path("verification");
    Assertions.assertEquals("UNAVAILABLE", verification.path("status").asText());
    Assertions.assertEquals(3, verification.path("failed_claims").asInt());
    for (int i = 0; i < 3; i++) {
      Assertions.assertTrue(claimOf(body, i).path("verdict").isNull());
    }
    // The dashboard must not fold the failed claims into the INSUFFICIENT bucket.
    JsonNode verdicts = fetch("/api/v1/dashboard").path("verification_verdicts");
    Assertions.assertEquals(0, countNamed(verdicts, "INSUFFICIENT"));
    Assertions.assertEquals(3, countNamed(verdicts, "UNAVAILABLE"));
  }

  /** Rows the old fallback already stored as INSUFFICIENT/UNAVAILABLE are read honestly, not rewritten. */
  @Test
  void legacyUnavailableRowsKeepTheirStoredVerdictOnRead() throws Exception {
    UUID analysisId = UUID.randomUUID();
    UUID claimId = UUID.randomUUID();
    db.update("INSERT INTO analyses(id,owner_id,input_type,input_text,status) VALUES (?,?,?,?,?)",
        analysisId, ownerId, "CLAIM", "Legacy claim " + suffix, "COMPLETED");
    db.update("INSERT INTO claims(id,analysis_id,claim_text,verdict,component_status,nli_output) VALUES (?,?,?,?,?,?::jsonb)",
        claimId, analysisId, "Legacy claim " + suffix, "INSUFFICIENT", "UNAVAILABLE",
        json.createObjectNode().put("error", "Verification unavailable: " + SERVICE_FAILURE).toString());

    JsonNode claim = claimOf(fetchAnalysis(analysisId.toString()), 0);
    Assertions.assertEquals("INSUFFICIENT", claim.path("verdict").asText(), "the stored verdict is not rewritten");
    Assertions.assertEquals("UNAVAILABLE", claim.path("component_status").asText(), "the failure stays visible");
    Assertions.assertEquals("INSUFFICIENT",
        db.queryForObject("SELECT verdict FROM claims WHERE id=?", String.class, claimId));
    Assertions.assertEquals("UNAVAILABLE", fetchReport(analysisId.toString()).path("claims").get(0).path("component_status").asText());

    JsonNode verdicts = fetch("/api/v1/dashboard").path("verification_verdicts");
    Assertions.assertEquals(1, countNamed(verdicts, "UNAVAILABLE"));
    Assertions.assertEquals(0, countNamed(verdicts, "INSUFFICIENT"));
  }

  /** Only claims starting with FAIL throw; the rest answer like the deployed service. */
  private void failingOnlyOn(String marker) throws Exception {
    when(ai.extractClaims(anyMap())).thenReturn(claims("SUPPORTED Paris is in France.",
        marker + " unverifiable claim.", "REFUTED the Moon is made of cheese."));
    when(ai.verify(anyString())).thenAnswer(invocation -> {
      String claim = invocation.getArgument(0);
      if (claim.startsWith(marker)) {
        throw new IllegalStateException(SERVICE_FAILURE);
      }
      boolean refuted = claim.startsWith("REFUTED");
      ObjectNode node = JsonNodeFactory.instance.objectNode();
      node.put("verdict", refuted ? "REFUTED" : "SUPPORTED");
      node.put("model", "FEVER Iteration 2");
      node.put("reason", (refuted ? "REFUTED" : "SUPPORTED") + ": strongest signal 0.91 from Test_Page#1.");
      ArrayNode evidence = node.putArray("evidence");
      ObjectNode passage = JsonNodeFactory.instance.objectNode();
      passage.put("page_id", "Test_Page");
      passage.put("sentence_id", 1);
      passage.put("text", "A sentence from the indexed repository.");
      passage.put("similarity", 0.91);
      passage.put("selected", true);
      passage.set("nli_scores", JsonNodeFactory.instance.arrayNode().add(0.02).add(refuted ? 0.91 : 0.04).add(0.05));
      passage.set("nli_labels", JsonNodeFactory.instance.arrayNode().add("ENTAILMENT").add("CONTRADICTION").add("NEUTRAL"));
      evidence.add(passage);
      return node;
    });
  }

  private ObjectNode claims(String... texts) {
    ArrayNode array = JsonNodeFactory.instance.arrayNode();
    for (String text : texts) {
      array.add(JsonNodeFactory.instance.objectNode().put("text", text).put("method", "deterministic heuristic baseline"));
    }
    return JsonNodeFactory.instance.objectNode().set("claims", array);
  }

  private int evidenceCount(UUID analysisId) {
    Integer count = db.queryForObject(
        "SELECT count(*) FROM evidence WHERE claim_id IN (SELECT id FROM claims WHERE analysis_id=?)",
        Integer.class, analysisId);
    return count == null ? 0 : count;
  }

  private int countNamed(JsonNode array, String name) {
    int total = 0;
    for (JsonNode row : array) {
      if (name.equals(row.path("verdict").asText())) {
        total += row.path("total").asInt();
      }
    }
    return total;
  }

  private JsonNode claimOf(JsonNode body, int index) {
    return body.path("claims").get(index);
  }

  /** Claims come back in insertion order; matching on text keeps the assertions order-independent. */
  private JsonNode claimByText(JsonNode body, String needle) {
    for (JsonNode claim : body.path("claims")) {
      if (claim.path("claim_text").asText().contains(needle)) {
        return claim;
      }
    }
    throw new AssertionError("no claim containing '" + needle + "' in " + body.path("claims"));
  }

  private String submit(String path, Map<String, String> body) throws Exception {
    MvcResult result = mvc.perform(post(path).header("Authorization", "Bearer " + userToken)
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
        .andExpect(status().isAccepted()).andReturn();
    return json.readTree(result.getResponse().getContentAsString()).get("id").asText();
  }

  private JsonNode fetchAnalysis(String analysisId) throws Exception {
    return fetch("/api/v1/analysis/" + analysisId);
  }

  private JsonNode fetchReport(String analysisId) throws Exception {
    return fetch("/api/v1/reports/" + analysisId);
  }

  private JsonNode fetch(String path) throws Exception {
    MvcResult result = mvc.perform(get(path).header("Authorization", "Bearer " + userToken))
        .andExpect(status().isOk()).andReturn();
    return json.readTree(result.getResponse().getContentAsString());
  }

  private String login(String email) throws Exception {
    MvcResult result = mvc.perform(post("/api/v1/users/login").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("email", email, "password", "password123"))))
        .andExpect(status().isOk()).andReturn();
    return json.readTree(result.getResponse().getContentAsString()).get("token").asText();
  }
}
