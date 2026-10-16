package ai.truthlens;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AiGateway {
  private final RestClient ai;
  private final ObjectMapper json;

  public AiGateway(RestClient ai, ObjectMapper json) {
    this.ai = ai;
    this.json = json;
  }

  public JsonNode classifyArticle(Map<String, String> input) throws Exception {
    return postJson("/v1/classify/article", json.writeValueAsString(input));
  }

  public JsonNode extractClaims(Map<String, String> input) throws Exception {
    return postJson("/v1/extract-claims", json.writeValueAsString(input));
  }

  public JsonNode verify(String claim) throws Exception {
    return postJson("/v1/verify", json.writeValueAsString(Map.of("claim", claim)));
  }

  public JsonNode shap(Map<String, String> input) throws Exception {
    return postJson("/v1/explanations/shap", json.writeValueAsString(input));
  }

  public JsonNode classifyImage(MultipartFile file) {
    return postFile("/v1/classify/image", file);
  }

  public JsonNode gradcam(MultipartFile file) {
    return postFile("/v1/explanations/gradcam", file);
  }

  public JsonNode health() {
    String raw = ai.get().uri("/health").retrieve().body(String.class);
    try {
      return json.readTree(raw);
    } catch (Exception e) {
      throw new IllegalStateException("AI health response was not JSON", e);
    }
  }

  private JsonNode postJson(String path, String body) throws Exception {
    String raw = ai.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class);
    return json.readTree(raw);
  }

  private JsonNode postFile(String path, MultipartFile file) {
    try {
      MultipartBodyBuilder builder = new MultipartBodyBuilder();
      builder.part("file", file.getResource())
          .filename(file.getOriginalFilename() == null ? "upload.bin" : file.getOriginalFilename())
          .contentType(file.getContentType() == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(file.getContentType()));
      String raw = ai.post().uri(path).contentType(MediaType.MULTIPART_FORM_DATA).body(builder.build()).retrieve().body(String.class);
      return json.readTree(raw);
    } catch (Exception e) {
      throw new IllegalStateException("AI multipart request failed", e);
    }
  }
}
