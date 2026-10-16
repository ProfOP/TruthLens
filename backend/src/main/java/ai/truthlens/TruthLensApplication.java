package ai.truthlens;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@SpringBootApplication
public class TruthLensApplication {
  public static void main(String[] args) {
    SpringApplication.run(TruthLensApplication.class, args);
  }

  @Bean
  RestClient aiClient(RestClient.Builder builder, org.springframework.core.env.Environment environment) {
    HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
    factory.setReadTimeout(Duration.ofSeconds(120));
    return builder.requestFactory(factory).baseUrl(environment.getProperty("ai.service.url", "http://localhost:8001")).build();
  }
}
