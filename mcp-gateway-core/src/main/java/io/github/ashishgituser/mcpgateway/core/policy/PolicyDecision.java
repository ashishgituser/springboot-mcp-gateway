package io.github.ashishgituser.mcpgateway.core.policy;

/** The verdict for one tool call, with the reason it was reached so it can be logged or audited. */
public record PolicyDecision(PolicyEffect effect, String reason) {

  public static PolicyDecision allow(String reason) {
    return new PolicyDecision(PolicyEffect.ALLOW, reason);
  }

  public static PolicyDecision deny(String reason) {
    return new PolicyDecision(PolicyEffect.DENY, reason);
  }

  public boolean allowed() {
    return effect == PolicyEffect.ALLOW;
  }
}
