package gammazon.automation.clients;

import gammazon.automation.core.BaseAPI;
import gammazon.automation.core.ConfigManager;

public class KafkaConsumer extends BaseAPI {

    public KafkaConsumer() {
        super(ConfigManager.getInstance().get("KAFKA_CONSUMER_URI"));
    }

    public KafkaConsumer(String uri) {
        super(uri);
    }

    public static KafkaConsumer getConsumer() {
        return new KafkaConsumer();
    }

    public static KafkaConsumer getConsumer(String uri) {
        return new KafkaConsumer(uri);
    }
}
