package ai.truthlens;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class JwtServiceTest {
  private final JwtService jwt = new JwtService("test-jwt-secret-please-use-only-in-tests-32", new ObjectMapper());

  @Test
  void issuesAndVerifiesSignedToken() {
    String token = jwt.issue("11111111-1111-1111-1111-111111111111", "USER");
    JwtService.Claims claims = jwt.verify(token);
    assertNotNull(claims);
  }

  @Test
  void rejectsTamperedToken() {
    String token = jwt.issue("11111111-1111-1111-1111-111111111111", "USER") + "x";
    assertNull(jwt.verify(token));
  }

  @Test
  void rejectsExpiredToken() {
    String token = jwt.expiredToken("11111111-1111-1111-1111-111111111111", "USER");
    assertNull(jwt.verify(token));
  }
}
