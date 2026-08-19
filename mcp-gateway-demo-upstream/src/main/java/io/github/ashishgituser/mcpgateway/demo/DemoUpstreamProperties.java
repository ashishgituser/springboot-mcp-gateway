package io.github.ashishgituser.mcpgateway.demo;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "demo.upstream")
public record DemoUpstreamProperties(
    @DefaultValue("demo-upstream") String name, List<ToolSpec> tools) {

  public DemoUpstreamProperties {
    if (tools == null || tools.isEmpty()) {
      tools = List.of(new ToolSpec("ping", "Replies with pong", "pong"));
    }
  }

  public record ToolSpec(
      String name,
      @DefaultValue("A demo tool") String description,
      @DefaultValue("ok") String result) {}
}
