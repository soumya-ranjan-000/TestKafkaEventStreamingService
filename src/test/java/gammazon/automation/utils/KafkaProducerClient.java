package gammazon.automation.utils;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;

/**
 * Reusable utility for producing Kafka messages in tests.
 */
public class KafkaProducerClient {

    /**
     * Sends a simple string message to a Kafka topic.
     * 
     * @param bootstrapServers Kafka bootstrap servers URI
     * @param topic Topic to send to
     * @param value Message payload
     */
    public static void sendMessage(String bootstrapServers, String topic, String value) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(topic, value));
            producer.flush();
        }
    }
}
