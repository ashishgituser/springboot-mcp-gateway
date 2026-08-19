package io.github.ashishgituser.mcpgateway.core.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArgumentRedactorTest {

  @Test
  void masksKeysMatchingTheDefaultPatternsWhateverTheirCasing() {
    Map<String, Object> arguments = new LinkedHashMap<>();
    arguments.put("query", "select 1");
    arguments.put("Password", "hunter2");
    arguments.put("GITHUB_TOKEN", "ghp_x");
    arguments.put("Authorization", "Bearer abc");

    Map<String, Object> redacted = ArgumentRedactor.withDefaults().redact(arguments);

    assertThat(redacted)
        .containsEntry("query", "select 1")
        .containsEntry("Password", "***")
        .containsEntry("GITHUB_TOKEN", "***")
        .containsEntry("Authorization", "***");
  }

  @Test
  void keepsMaskedKeysSoTheLogStillShowsTheArgumentWasPresent() {
    Map<String, Object> redacted =
        ArgumentRedactor.withDefaults().redact(Map.of("apiKey", "sk-live-1"));

    assertThat(redacted).containsOnlyKeys("apiKey");
  }

  @Test
  void masksNestedArguments() {
    Map<String, Object> arguments =
        Map.of("connection", Map.of("host", "db.internal", "password", "hunter2"));

    Map<String, Object> redacted = ArgumentRedactor.withDefaults().redact(arguments);

    assertThat(redacted)
        .extracting("connection")
        .isEqualTo(Map.of("host", "db.internal", "password", "***"));
  }

  @Test
  void honoursAnExplicitPatternListInsteadOfTheDefaults() {
    ArgumentRedactor redactor = new ArgumentRedactor(List.of("ssn"));

    Map<String, Object> redacted = redactor.redact(Map.of("ssn", "123", "password", "hunter2"));

    assertThat(redacted).containsEntry("ssn", "***").containsEntry("password", "hunter2");
  }

  @Test
  void leavesEmptyArgumentsAlone() {
    assertThat(ArgumentRedactor.withDefaults().redact(Map.of())).isEmpty();
    assertThat(ArgumentRedactor.withDefaults().redact(null)).isNull();
  }
}
