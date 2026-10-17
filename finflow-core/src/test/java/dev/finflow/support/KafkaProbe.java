package dev.finflow.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

public final class KafkaProbe implements AutoCloseable {

    private final KafkaConsumer<String, String> consumer;

    public KafkaProbe(String bootstrapServers, String topic) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "probe-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        this.consumer = new KafkaConsumer<>(properties);
        this.consumer.subscribe(List.of(topic));
    }

    public List<ConsumerRecord<String, String>> poll(int expected, Duration timeout) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (records.size() < expected && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(300)).forEach(records::add);
        }
        return records;
    }

    public List<ConsumerRecord<String, String>> drain(Duration quietPeriod) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        long quietUntil = System.nanoTime() + quietPeriod.toNanos();
        while (System.nanoTime() < quietUntil) {
            consumer.poll(Duration.ofMillis(300)).forEach(record -> {
                records.add(record);
            });
        }
        return records;
    }

    @Override
    public void close() {
        consumer.close();
    }
}
