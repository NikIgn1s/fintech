package architecture.fixtures.consumer;

import architecture.fixtures.producer.internal.PrivateRepository;

public record InvalidConsumer(PrivateRepository repository) {}
