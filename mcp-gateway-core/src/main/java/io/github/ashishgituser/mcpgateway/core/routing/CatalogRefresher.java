package io.github.ashishgituser.mcpgateway.core.routing;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Re-reads the upstream catalogs on a fixed interval.
 *
 * <p>Without this the catalog is whatever the upstreams reported the moment the gateway started: an
 * upstream that was down at boot never appears, one that was restarted with new tools is never
 * noticed, and one that was decommissioned is advertised forever. When a refresh changes the
 * published set of tools, {@code onCatalogChanged} runs so connected clients can be told to
 * re-list.
 */
public class CatalogRefresher implements AutoCloseable {

  private static final Logger logger = LoggerFactory.getLogger(CatalogRefresher.class);

  private final BooleanSupplier refresh;
  private final Duration interval;
  private final Runnable onCatalogChanged;

  private ScheduledExecutorService scheduler;

  public CatalogRefresher(BooleanSupplier refresh, Duration interval, Runnable onCatalogChanged) {
    this.refresh = refresh;
    this.interval = interval;
    this.onCatalogChanged = onCatalogChanged;
  }

  public void start() {
    if (interval.isZero() || interval.isNegative()) {
      logger.debug("Upstream catalog refresh is disabled");
      return;
    }
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "mcp-gateway-catalog-refresh");
              thread.setDaemon(true);
              return thread;
            });
    scheduler.scheduleWithFixedDelay(
        this::refreshQuietly, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    logger.info("Refreshing upstream tool catalogs every {}", interval);
  }

  private void refreshQuietly() {
    try {
      if (refresh.getAsBoolean()) {
        logger.info("Upstream tool catalog changed; notifying connected clients");
        onCatalogChanged.run();
      }
    } catch (RuntimeException e) {
      // Never let a failure kill the schedule - scheduleWithFixedDelay stops on a thrown task.
      logger.warn("Upstream catalog refresh failed", e);
    }
  }

  @Override
  public void close() {
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }
}
