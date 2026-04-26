package gammazon.automation.utils;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import gammazon.automation.core.ConfigManager;

public class TestContainerManager {
    private static final Network network = Network.newNetwork();
    
    private static KafkaContainer kafka;
    private static MongoDBContainer mongo;
    private static GenericContainer<?> producer;
    private static List<GenericContainer<?>> consumers = new ArrayList<>();

    public static void startContainers() {
        // Start MongoDB
        mongo = new MongoDBContainer(DockerImageName.parse("mongo:6.0.5"))
                .withNetwork(network)
                .withNetworkAliases("mongodb");
        mongo.start();

        // Start Kafka
        kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.4.0"))
                .withNetwork(network)
                .withNetworkAliases("kafka");
        kafka.start();

        // Create orders-topic with 3 partitions for load balancing tests
        createTopic("orders-topic", 3);

        // Start Consumer (Python Async Consumer)
        String consumerImage = ConfigManager.getInstance().get("CONSUMER_IMAGE");
        String dbName = ConfigManager.getInstance().get("MONGODB_DATABASE");
        GenericContainer<?> consumer = new GenericContainer<>(DockerImageName.parse(consumerImage))
                .withNetwork(network)
                .withExposedPorts(8000)
                .withEnv("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092")
                .withEnv("MONGODB_URI", "mongodb://mongodb:27017/" + dbName)
                .withEnv("MONGODB_DATABASE", dbName)
                .withEnv("DATABASE_NAME", dbName)
                .waitingFor(Wait.forListeningPort());
        consumer.start();
        consumers.add(consumer);

        // Start Producer (Java Spring Boot Producer)
        String producerImage = ConfigManager.getInstance().get("PRODUCER_IMAGE");
        producer = new GenericContainer<>(DockerImageName.parse(producerImage))
                .withNetwork(network)
                .withExposedPorts(8080)
                .withEnv("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092")
                .withEnv("SPRING_KAFKA_BOOTSTRAP_SERVERS", "kafka:9092")
                .withEnv("MONGODB_URI", "mongodb://mongodb:27017/" + dbName)
                .withEnv("SPRING_DATA_MONGODB_URI", "mongodb://mongodb:27017/" + dbName)
                .withEnv("SPRING_DATA_MONGODB_DATABASE", dbName)
                .withEnv("MONGODB_DATABASE", dbName)
                .waitingFor(Wait.forListeningPort())
                .withStartupTimeout(java.time.Duration.ofSeconds(120));
        producer.start();
        
        // Update ConfigManager with dynamic host/ports for the tests
        String producerUri = "http://" + producer.getHost() + ":" + producer.getMappedPort(8080);
        ConfigManager.getInstance().set("KAFKA_PRODUCER_URI", producerUri);
        ConfigManager.getInstance().set("GAMMAZON_BACKEND_URI", producerUri);
        ConfigManager.getInstance().set("KAFKA_CONSUMER_URI", "http://" + consumers.get(0).getHost() + ":" + consumers.get(0).getMappedPort(8000));
        ConfigManager.getInstance().set("MONGODB_URI", mongo.getReplicaSetUrl());
        ConfigManager.getInstance().set("KAFKA_BOOTSTRAP_SERVERS", kafka.getBootstrapServers());
        
        System.out.println("[TestContainerManager] All containers started and URIs updated.");
    }

    public static void stopContainers() {
        if (producer != null) producer.stop();
        for (GenericContainer<?> c : consumers) {
            if (c != null) c.stop();
        }
        if (kafka != null) kafka.stop();
        if (mongo != null) mongo.stop();
        System.out.println("[TestContainerManager] All containers stopped.");
    }

    public static String getProducerUri() {
        return ConfigManager.getInstance().get("KAFKA_PRODUCER_URI");
    }

    public static String getConsumerUri() {
        return ConfigManager.getInstance().get("KAFKA_CONSUMER_URI");
    }

    public static List<String> getAllConsumerUris() {
        List<String> uris = new ArrayList<>();
        for (GenericContainer<?> c : consumers) {
            uris.add("http://" + c.getHost() + ":" + c.getMappedPort(8000));
        }
        return uris;
    }

    public static String getKafkaBootstrapServers() {
        return kafka.getBootstrapServers();
    }

    /**
     * Pauses the MongoDB container to simulate a database outage.
     */
    public static void pauseMongo() {
        if (mongo != null && mongo.isRunning()) {
            mongo.getDockerClient().pauseContainerCmd(mongo.getContainerId()).exec();
            System.out.println("[TestContainerManager] MongoDB container PAUSED.");
        }
    }

    /**
     * Unpauses the MongoDB container to restore database connectivity.
     */
    public static void unpauseMongo() {
        if (mongo != null && mongo.isRunning()) {
            mongo.getDockerClient().unpauseContainerCmd(mongo.getContainerId()).exec();
            System.out.println("[TestContainerManager] MongoDB container UNPAUSED.");
        }
    }

    private static void createTopic(String topicName, int partitions) {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        try (AdminClient adminClient = AdminClient.create(props)) {
            // Delete if exists to ensure fresh partition count
            try {
                adminClient.deleteTopics(Collections.singletonList(topicName)).all().get();
                System.out.println("[TestContainerManager] Deleted existing topic: " + topicName);
            } catch (Exception ignored) {}

            NewTopic newTopic = new NewTopic(topicName, partitions, (short) 1);
            adminClient.createTopics(Collections.singletonList(newTopic)).all().get();
            System.out.println("[TestContainerManager] Topic created: " + topicName + " with " + partitions + " partitions.");
        } catch (Exception e) {
            System.err.println("[TestContainerManager] Failed to manage topic: " + e.getMessage());
        }
    }


    public static void startAdditionalConsumers(int count) {
        String consumerImage = ConfigManager.getInstance().get("CONSUMER_IMAGE");
        String dbName = ConfigManager.getInstance().get("MONGODB_DATABASE");
        for (int i = 0; i < count; i++) {
            GenericContainer<?> extraConsumer = new GenericContainer<>(DockerImageName.parse(consumerImage))
                    .withNetwork(network)
                    .withExposedPorts(8000)
                    .withEnv("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092")
                    .withEnv("MONGODB_URI", "mongodb://mongodb:27017/" + dbName)
                    .withEnv("MONGODB_DATABASE", dbName)
                    .withEnv("DATABASE_NAME", dbName)
                    .waitingFor(Wait.forListeningPort());
            extraConsumer.start();
            consumers.add(extraConsumer);
            System.out.println("[TestContainerManager] Extra consumer started.");
        }
    }
}
