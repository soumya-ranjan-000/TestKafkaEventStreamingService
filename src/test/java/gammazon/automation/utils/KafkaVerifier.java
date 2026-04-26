package gammazon.automation.utils;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.bson.Document;
import org.bson.json.JsonWriterSettings;
import gammazon.automation.core.ReportManager;
import org.testng.Assert;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;

/**
 * Reusable utility for verifying Kafka events in tests.
 */
public class KafkaVerifier {

    private static void logKafka(String message) {
        String consolePrefix = "[🎡 Kafka] ";
        System.out.println(consolePrefix + message.replaceAll("<[^>]*>", ""));
        if (ReportManager.getTest() != null) {
            String reportIcon = "<i class='fa fa-exchange' style='color:#FF9900'></i> ";
            ReportManager.getTest().info(reportIcon + "<b>Kafka:</b> " + message);
        }
    }

    /**
     * Polls a Kafka topic and verifies that a message containing the specified Order ID exists.
     * 
     * @param bootstrapServers Kafka bootstrap servers URI
     * @param topic Topic to poll
     * @param orderId The Order ID to search for in the message value
     * @return The raw message value if found
     */
    public static String verifyOrderInTopic(String bootstrapServers, String topic, String orderId) {
        logKafka("🔍 Polling <b>" + topic + "</b> for Order ID: <span style='color:#FF9900'>" + orderId + "</span>");
        
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "verifier-" + System.currentTimeMillis());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(topic));
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(10));
            
            for (var record : records) {
                if (record.value().contains(orderId)) {
                    logKafka("✅ Found matching event in topic: <b>" + topic + "</b>");
                    
                    try {
                        Document doc = Document.parse(record.value());
                        String prettyJson = doc.toJson(JsonWriterSettings.builder().indent(true).build());
                        String box = "<div style='background-color: #fffef0; padding: 15px; border-left: 5px solid #FF9900; border-radius: 8px; margin: 10px 0;'>" +
                                     "<h5 style='margin-top: 0; color: #e65100; font-weight: bold;'>🎡 Kafka Event Captured</h5>" +
                                     "<pre style='font-family: \"Courier New\", Courier, monospace; color: #202124; font-size: 13px; line-height: 1.5;'>" + prettyJson + "</pre>" +
                                     "<small style='color: #666;'>Partition: " + record.partition() + " | Offset: " + record.offset() + "</small></div>";
                        if (ReportManager.getTest() != null) ReportManager.getTest().info(box);
                    } catch (Exception e) {
                        logKafka("Raw Message Content: " + record.value());
                    }
                    return record.value();
                }
            }
            Assert.fail("Order ID " + orderId + " was not found in Kafka topic '" + topic + "' after 10s poll.");
        }
        return null;
    }

    /**
     * Polls a Kafka topic and verifies that a message containing the specified substring exists.
     * Also logs message headers if present.
     * 
     * @param bootstrapServers Kafka bootstrap servers URI
     * @param topic Topic to poll
     * @param expectedContent Substring to search for in the message value
     * @return The raw message value if found
     */
    public static String verifyMessageInTopic(String bootstrapServers, String topic, String expectedContent) {
        logKafka("🔍 Polling <b>" + topic + "</b> for content: <span style='color:#FF9900'>" + expectedContent + "</span>");
        
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "verifier-gen-" + System.currentTimeMillis());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(Collections.singletonList(topic));
            
            // Poll multiple times if needed to handle consumer lag
            for (int attempt = 0; attempt < 3; attempt++) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(5));
                for (var record : records) {
                    if (record.value().contains(expectedContent)) {
                        logKafka("✅ Found matching event in topic: <b>" + topic + "</b>");
                        
                        StringBuilder headersInfo = new StringBuilder();
                        record.headers().forEach(header -> 
                            headersInfo.append("<b>").append(header.key()).append("</b>: ").append(new String(header.value())).append("<br/>")
                        );

                        String box = "<div style='background-color: #f0f7ff; padding: 15px; border-left: 5px solid #007bff; border-radius: 8px; margin: 10px 0;'>" +
                                     "<h5 style='margin-top: 0; color: #0056b3; font-weight: bold;'>🎡 Kafka Event (Generic)</h5>" +
                                     "<pre style='font-family: \"Courier New\", Courier, monospace; color: #202124; font-size: 13px; line-height: 1.5;'>" + record.value() + "</pre>" +
                                     (headersInfo.length() > 0 ? "<div style='margin-top:10px;'><b>Headers:</b><br/>" + headersInfo.toString() + "</div>" : "") +
                                     "<small style='color: #666;'>Partition: " + record.partition() + " | Offset: " + record.offset() + "</small></div>";
                        
                        if (ReportManager.getTest() != null) ReportManager.getTest().info(box);
                        return record.value();
                    }
                }
            }
            Assert.fail("Content '" + expectedContent + "' was not found in Kafka topic '" + topic + "' after polling.");
        }
        return null;
    }

    public static long getLatestOffset(String bootstrapServers, String topic) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        try (org.apache.kafka.clients.consumer.KafkaConsumer<String, String> consumer = new org.apache.kafka.clients.consumer.KafkaConsumer<>(props)) {
            var partitions = consumer.partitionsFor(topic);
            if (partitions == null) return 0;
            
            var topicPartitions = partitions.stream()
                    .map(p -> new org.apache.kafka.common.TopicPartition(topic, p.partition()))
                    .toList();
            
            consumer.assign(topicPartitions);
            consumer.seekToEnd(topicPartitions);
            
            long totalOffset = 0;
            for (var tp : topicPartitions) {
                totalOffset += consumer.position(tp);
            }
            return totalOffset;
        } catch (Exception e) {
            return -1;
        }
    }
}
