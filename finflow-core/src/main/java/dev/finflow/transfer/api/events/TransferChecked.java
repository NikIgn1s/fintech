package dev.finflow.transfer.api.events;

import java.time.Duration;

public record TransferChecked(String decision,String source,Duration latency) {}
