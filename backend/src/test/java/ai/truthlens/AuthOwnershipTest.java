package ai.truthlens;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "SPRING_DATASOURCE_PASSWORD", matches = ".+")
class AuthOwnershipTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate db;
  @Autowired JwtService jwt;
  @MockitoBean AiGateway ai;

  private String suffix;

  @BeforeEach
  void mocks() throws Exception {
    suffix = UUID.randomUUID().toString().substring(0, 8);
    when(ai.classifyArticle(anyMap())).thenReturn(JsonNodeFactory.instance.objectNode()
        .put("label", "FAKE")
        .put("credibility_score", 4.74)
        .set("probabilities", JsonNodeFactory.instance.objectNode().put("REAL", 0.047).put("FAKE", 0.953)));
    when(ai.extractClaims(anyMap())).thenReturn(JsonNodeFactory.instance.objectNode()
        .set("claims", JsonNodeFactory.instance.arrayNode().add(JsonNodeFactory.instance.objectNode().put("text", "A claim.").put("sentence_index", 0).put("relevance", 0.8))));
    when(ai.verify(anyString())).thenReturn(JsonNodeFactory.instance.objectNode()
        .put("verdict", "INSUFFICIENT")
        .put("model", "FEVER Iteration 2")
        .set("evidence", JsonNodeFactory.instance.arrayNode()));
    when(ai.shap(anyMap())).thenReturn(JsonNodeFactory.instance.objectNode().put("status", "generated").put("method", "SHAP"));
    when(ai.classifyImage(any())).thenReturn(JsonNodeFactory.instance.objectNode().put("verdict", "AUTHENTIC"));
    when(ai.gradcam(any())).thenReturn(JsonNodeFactory.instance.objectNode().put("status", "generated"));
  }

  @Test
  void registrationLoginProfileAndOwnership() throws Exception {
    String emailA = "usera-" + suffix + "@example.com";
    String emailB = "userb-" + suffix + "@example.com";
    mvc.perform(post("/api/v1/users/register").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("name", "User A", "email", emailA, "password", "password1", "role", "ADMIN"))))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/v1/users/register").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("name", "User A", "email", emailA, "password", "password1"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.role").value("USER"));
    mvc.perform(post("/api/v1/users/register").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("name", "User B", "email", emailB, "password", "password2"))))
        .andExpect(status().isCreated());
    mvc.perform(post("/api/v1/users/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("email", emailA, "password", "wrong-pass"))))
        .andExpect(status().isUnauthorized());
    String tokenA = token(emailA, "password1");
    String tokenB = token(emailB, "password2");
    mvc.perform(get("/api/v1/users/profile").header("Authorization", "Bearer " + tokenA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value(emailA))
        .andExpect(jsonPath("$.role").value("USER"));
    mvc.perform(get("/api/v1/users/profile")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/users/profile").header("Authorization", "Bearer not-a-jwt")).andExpect(status().isUnauthorized());
    UUID userA = UUID.fromString(json.readTree(mvc.perform(get("/api/v1/users/profile").header("Authorization", "Bearer " + tokenA)).andReturn().getResponse().getContentAsString()).get("id").asText());
    mvc.perform(get("/api/v1/users/profile").header("Authorization", "Bearer " + jwt.expiredToken(userA.toString(), "USER")))
        .andExpect(status().isUnauthorized());

    MvcResult created = mvc.perform(post("/api/v1/analysis/text").header("Authorization", "Bearer " + tokenA).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("title", "T", "body", "Helsinki is the capital of Finland."))))
        .andExpect(status().isAccepted())
        .andReturn();
    String analysisId = json.readTree(created.getResponse().getContentAsString()).get("id").asText();
    mvc.perform(get("/api/v1/analysis/" + analysisId).header("Authorization", "Bearer " + tokenA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.owner_id").doesNotExist());
    mvc.perform(get("/api/v1/analysis/" + analysisId).header("Authorization", "Bearer " + tokenB))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/analysis").header("Authorization", "Bearer " + tokenB))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id=='" + analysisId + "')]").isEmpty());

    byte[] png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=");
    MvcResult image = mvc.perform(multipart("/api/v1/analysis/image").file(new MockMultipartFile("file", "dot.png", "image/png", png)).header("Authorization", "Bearer " + tokenA))
        .andExpect(status().isAccepted())
        .andReturn();
    String imageId = json.readTree(image.getResponse().getContentAsString()).get("id").asText();
    var assets = json.readTree(mvc.perform(get("/api/v1/analysis/" + imageId).header("Authorization", "Bearer " + tokenA)).andReturn().getResponse().getContentAsString()).get("assets");
    if (assets != null && assets.size() > 0) {
      mvc.perform(get("/api/v1/analysis/" + imageId + "/assets/" + assets.get(0).get("id").asText()).header("Authorization", "Bearer " + tokenB))
          .andExpect(status().isForbidden());
    }
    UUID orphan = UUID.randomUUID();
    db.update("INSERT INTO analyses(id,owner_id,input_type,input_text,status) VALUES (?,?,?,?,?)", orphan, null, "TEXT", "legacy", "COMPLETED");
    mvc.perform(get("/api/v1/analysis/" + orphan).header("Authorization", "Bearer " + tokenA)).andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/analysis").header("Authorization", "Bearer " + tokenA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.id=='" + orphan + "')]").isEmpty());
    mvc.perform(options("/api/v1/users/login").header("Origin", "http://localhost:5173").header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "content-type"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
  }

  private String token(String email, String password) throws Exception {
    MvcResult login = mvc.perform(post("/api/v1/users/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("email", email, "password", password))))
        .andExpect(status().isOk())
        .andReturn();
    return json.readTree(login.getResponse().getContentAsString()).get("token").asText();
  }
}
