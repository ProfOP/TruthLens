package ai.truthlens;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
  private final byte[] secret;
  private final long ttlSeconds = 900;
  private final ObjectMapper json;

  public JwtService(@Value("${JWT_SECRET:}") String configuredSecret, ObjectMapper json) {
    if (configuredSecret == null || configuredSecret.length() < 32) {
      throw new IllegalStateException("JWT_SECRET must be set to at least 32 characters");
    }
    this.secret = configuredSecret.getBytes(StandardCharsets.UTF_8);
    this.json = json;
  }

  public String issue(String userId, String role) {
    long exp = Instant.now().getEpochSecond() + ttlSeconds;
    String header = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
    String payload = b64(write(Map.of("sub", userId, "role", role, "exp", exp)));
    String signingInput = header + "." + payload;
    return signingInput + "." + b64(sign(signingInput));
  }

  public Claims verify(String token) {
    try {
      String[] parts = token.split("\\.", -1);
      if (parts.length != 3) {
        return null;
      }
      String expected = b64(sign(parts[0] + "." + parts[1]));
      if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), parts[2].getBytes(StandardCharsets.US_ASCII))) {
        return null;
      }
      JsonNode payload = json.readTree(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
      String sub = payload.path("sub").asText(null);
      String role = payload.path("role").asText(null);
      long exp = payload.path("exp").asLong(0);
      if (sub == null || sub.isBlank() || role == null || exp <= Instant.now().getEpochSecond()) {
        return null;
      }
      return new Claims(sub, role, exp);
    } catch (Exception e) {
      return null;
    }
  }

  public String expiredToken(String userId, String role) {
    long exp = Instant.now().getEpochSecond() - 10;
    String header = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
    String payload = b64(write(Map.of("sub", userId, "role", role, "exp", exp)));
    String signingInput = header + "." + payload;
    return signingInput + "." + b64(sign(signingInput));
  }

  private byte[] sign(String input) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("JWT signing unavailable", e);
    }
  }

  private String write(Map<String, Object> payload) {
    try {
      return json.writeValueAsString(payload);
    } catch (Exception e) {
      throw new IllegalStateException("JWT payload unavailable", e);
    }
  }

  private static String b64(String value) {
    return b64(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String b64(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  public record Claims(String subject, String role, long expiresAt) {}
}
