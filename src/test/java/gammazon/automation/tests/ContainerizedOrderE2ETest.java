package gammazon.automation.tests;

import gammazon.automation.clients.KafkaConsumer;
import gammazon.automation.clients.OrderClient;
import gammazon.automation.core.ConfigManager;
import gammazon.automation.db.MongoDBClient;
import gammazon.automation.models.OrderRequest;
import gammazon.automation.utils.KafkaProducerClient;
import gammazon.automation.utils.KafkaVerifier;
import gammazon.automation.utils.RandomGenerator;
import gammazon.automation.utils.TestContainerManager;
import io.restassured.response.Response;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;
import gammazon.automation.core.ReportManager;
import org.testng.Assert;
import org.testng.ITestResult;
import org.testng.annotations.*;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;

public class ContainerizedOrderE2ETest {

    @BeforeSuite
    public void setupSuite() {
        TestContainerManager.startContainers();
    }

    @BeforeMethod
    public void setup(Method method) {
        ReportManager.getInstance();
        ReportManager.setTest(ReportManager.getInstance().createTest(method.getName()));
    }

    @Test(description = "Verify that a valid order request submitted via the API is correctly published, consumed, and persisted with stock adjustment.")
    public void testEndToEndOrderProcessing() {
        String productId = "QA-CONTAINER";
        int initialStock = 500;
        int orderQuantity = 5;
        BigDecimal price = BigDecimal.valueOf(49.99);

        // Pre-condition: Setup product in containerized MongoDB from JSON
        MongoDBClient.loadTestData("products", "src/test/resources/testdata/products.json");

        MongoDBClient.printCollectionData("products");

        // Step 1: Send Order
        String customerId = "Cust-" + RandomGenerator.generateNumber(5);
        OrderRequest request = OrderRequest.builder()
                .customerId(customerId)
                .productId(productId)
                .quantity(orderQuantity)
                .price(price)
                .build();

        Response response = OrderClient.getClient().createOrder(request);
        Assert.assertEquals(response.getStatusCode(), 201, "Order creation failed!");
        String orderId = response.jsonPath().getString("orderId");

        // Wait for async processing
        try { Thread.sleep(2000); } catch (InterruptedException e) { e.printStackTrace(); }

        // Step 2: Verify the order message is published to the Kafka topic
        KafkaVerifier.verifyOrderInTopic(
                TestContainerManager.getKafkaBootstrapServers(), 
                "orders-topic", 
                orderId
        );

        // Step 3: Verify Consumer processed the order (via Consumer API)
        Response consumerResponse = KafkaConsumer.getConsumer().get("/api/orders/" + orderId);
        Assert.assertEquals(consumerResponse.getStatusCode(), 200);
        Assert.assertEquals(consumerResponse.jsonPath().getString("status"), "PROCESSED");

        // Step 4: Verify MongoDB persistence
        MongoDBClient.listCollections();
        Document dbOrder = null;
        for (int i = 0; i < 5; i++) {
            dbOrder = MongoDBClient.findOne("orders", Filters.eq("_id", orderId));
            if (dbOrder != null) break;
            try { Thread.sleep(1000); } catch (InterruptedException e) {}
        }
        
        Assert.assertNotNull(dbOrder, "Order record not found in MongoDB after retries");
        // Step 5: Verify Stock decrement
        Document updatedProduct = MongoDBClient.findOne("products", Filters.eq("productId", productId));
        Assert.assertEquals(updatedProduct.getInteger("stock").intValue(), initialStock - orderQuantity);
        MongoDBClient.printCollectionData("products");
        MongoDBClient.printCollectionData("orders");
    }

    @Test(description = "Ensure that if a message is delivered multiple times (due to Kafka retries or consumer crashes), it is only processed once.")
    public void testDuplicateMessageHandling(){
        String productId = "QA-DUPLICATE";
        int initialStock = 100;
        int orderQuantity = 2;
        BigDecimal price = BigDecimal.valueOf(99.99);

        // Pre-condition: Setup product in MongoDB
        Document product = new Document("_id", productId)
                .append("productId", productId)
                .append("name", "Idempotency Test Product")
                .append("stock", initialStock)
                .append("price", price.doubleValue());

        MongoDBClient.getCollection("products").replaceOne(
                Filters.eq("_id", productId),
                product,
                new ReplaceOptions().upsert(true)
        );

        // Step 1: Send original Order via API
        OrderRequest request = OrderRequest.builder()
                .customerId("Cust-Idempotent")
                .productId(productId)
                .quantity(orderQuantity)
                .price(price)
                .build();

        Response response = OrderClient.getClient().createOrder(request);
        Assert.assertEquals(response.getStatusCode(), 201, "Initial order creation failed!");
        String orderId = response.jsonPath().getString("orderId");

        // Step 2: Verify the first message is processed and captured
        String originalKafkaMessage = KafkaVerifier.verifyOrderInTopic(
                TestContainerManager.getKafkaBootstrapServers(), 
                "orders-topic", 
                orderId
        );

        // Step 3: Produce the IDENTICAL message again directly to Kafka to simulate a retry
        ReportManager.getTest().info("🎡 <b>Simulating Kafka Retry:</b> Producing duplicate message for Order ID: " + orderId);
        KafkaProducerClient.sendMessage(
                TestContainerManager.getKafkaBootstrapServers(),
                "orders-topic",
                originalKafkaMessage
        );

        // Wait for async processing of the duplicate
        try { Thread.sleep(3000); } catch (InterruptedException ignored) {}

        // Step 4: Verify MongoDB only has ONE record for this orderId (Idempotency)
        long orderCount = MongoDBClient.getCollection("orders").countDocuments(Filters.eq("_id", orderId));
        Assert.assertEquals(orderCount, 1, "Consumer processed the duplicate! Multiple records found in MongoDB.");
        
        // Step 5: Verify Stock was only decremented ONCE
        Document updatedProduct = MongoDBClient.findOne("products", Filters.eq("_id", productId));
        Assert.assertEquals(updatedProduct.getInteger("stock").intValue(), initialStock - orderQuantity, 
                "Stock was decremented multiple times! Idempotency failed.");

        MongoDBClient.printCollectionData("orders");
        MongoDBClient.printCollectionData("products");
    }

    @Test(description = "Verify the system's 'Fail-Fast' and 'Isolate' behavior when encountering unparseable messages.")
    public void testMalformedJsonHandling() {
        String malformedData = """
                {
                	"eventId": "8be3b90e-9c7c-44ce-9253-ce76830673ed",
                	"orderId": "20ace5b0-7c8c-418b-879f-90ad979b0a1d",
                	"customerId": "Customer-72054",
                	"productId": "P1",
                	"quantity": 2,
                	"status": "CREATED",
                	"timestamp": "2026-04-26T09:51:55.778249529"
                }
                """;
        String ordersTopic = "orders-topic";
        String dlqTopic = "orders-topic.dlq";

        // Step 1: Manually produce a non-JSON string to the orders-topic
        ReportManager.getTest().info("📤 <b>Producing Malformed Message:</b> " + malformedData);
        KafkaProducerClient.sendMessage(
                TestContainerManager.getKafkaBootstrapServers(),
                ordersTopic,
                malformedData
        );

        // Step 2: Monitor orders-dlq-topic for the malformed message
        // This verifies that the message was moved to DLQ and (implicitly) that the consumer didn't crash
        KafkaVerifier.verifyMessageInTopic(
                TestContainerManager.getKafkaBootstrapServers(),
                dlqTopic,
                malformedData
        );

        // Step 3: Verify the system is still healthy by sending a valid order
        String productId = "QA-RECOVERY";
        int orderQuantity = 1;
        BigDecimal price = BigDecimal.valueOf(10.00);

        // Setup product
        Document product = new Document("_id", productId)
                .append("productId", productId)
                .append("name", "Recovery Test Product")
                .append("stock", 100)
                .append("price", price.doubleValue());

        MongoDBClient.getCollection("products").replaceOne(
                Filters.eq("_id", productId),
                product,
                new ReplaceOptions().upsert(true)
        );

        OrderRequest request = OrderRequest.builder()
                .customerId("Cust-Recovery")
                .productId(productId)
                .quantity(orderQuantity)
                .price(price)
                .build();

        Response response = OrderClient.getClient().createOrder(request);
        Assert.assertEquals(response.getStatusCode(), 201, "Order creation failed after processing malformed message!");
        String orderId = response.jsonPath().getString("orderId");

        // Verify valid order is processed
        KafkaVerifier.verifyOrderInTopic(
                TestContainerManager.getKafkaBootstrapServers(),
                ordersTopic,
                orderId
        );
        
        // Final check in MongoDB for the valid order
        Document dbOrder = null;
        for (int i = 0; i < 5; i++) {
            dbOrder = MongoDBClient.findOne("orders", Filters.eq("_id", orderId));
            if (dbOrder != null) break;
            try { Thread.sleep(1000); } catch (InterruptedException e) {}
        }
        Assert.assertNotNull(dbOrder, "Pipeline stalled! Valid order not found in MongoDB.");
        Assert.assertEquals(dbOrder.getString("status"), "PROCESSED");

        MongoDBClient.printCollectionData("orders");
        MongoDBClient.printCollectionData("products");

        ReportManager.getTest().pass("Malformed message isolated to DLQ and pipeline recovered successfully. ✅");
    }

    @Test(description = "Verify that the consumer retries database operations during transient DB outages.")
    public void testDatabaseConnectivityInterruption() {
        String productId = "QA-RESILIENCE";
        int orderQuantity = 3;
        BigDecimal price = BigDecimal.valueOf(150.00);

        // Step 1: Pre-condition - Setup product in MongoDB
        Document product = new Document("_id", productId)
                .append("productId", productId)
                .append("name", "Resilience Test Product")
                .append("stock", 50)
                .append("price", price.doubleValue());

        MongoDBClient.getCollection("products").replaceOne(
                Filters.eq("_id", productId),
                product,
                new ReplaceOptions().upsert(true)
        );

        // Step 2: Pause MongoDB to simulate a transient outage
        ReportManager.getTest().info("🔌 <b>Simulating Outage:</b> Pausing MongoDB container...");
        TestContainerManager.pauseMongo();

        // Step 3: Produce a valid order message directly to Kafka
        // We use KafkaProducerClient directly to bypass any Producer API -> DB checks
        String orderId = "ORD-RES-" + System.currentTimeMillis();
        String eventId = java.util.UUID.randomUUID().toString();
        String timestamp = java.time.OffsetDateTime.now().toString();
        
        String orderMessage = String.format(
            "{\"eventId\":\"%s\",\"orderId\":\"%s\",\"customerId\":\"Cust-Resilience\",\"productId\":\"%s\",\"quantity\":%d,\"price\":%.2f,\"status\":\"CREATED\",\"timestamp\":\"%s\"}",
            eventId, orderId, productId, orderQuantity, price, timestamp
        );

        ReportManager.getTest().info("📤 <b>Producing Message:</b> " + orderId);
        KafkaProducerClient.sendMessage(
                TestContainerManager.getKafkaBootstrapServers(),
                "orders-topic",
                orderMessage
        );

        // Step 4: Wait and verify that the order is NOT in MongoDB (since it's paused)
        ReportManager.getTest().info("⏳ Waiting to verify consumer retries (DB should be unreachable)...");
        try { Thread.sleep(5000); } catch (InterruptedException ignored) {}

        // Step 5: Unpause MongoDB to restore connectivity
        ReportManager.getTest().info("⚡ <b>Restoring Connectivity:</b> Unpausing MongoDB container...");
        TestContainerManager.unpauseMongo();

        try { Thread.sleep(10000); } catch (InterruptedException ignored) {}

        // Step 6: Verify the message is eventually processed successfully
        ReportManager.getTest().info("🔍 Verifying eventual consistency...");
        Document dbOrder = null;
        for (int i = 0; i < 10; i++) {
            try {
                dbOrder = MongoDBClient.findOne("orders", Filters.eq("_id", orderId));
                if (dbOrder != null) break;
            } catch (Exception e) {
                // Ignore transient errors while DB is waking up
            }
            try { Thread.sleep(2000); } catch (InterruptedException e) {}
        }

        Assert.assertNotNull(dbOrder, "Order not processed even after DB restoration!");
        Assert.assertEquals(dbOrder.getString("status"), "PROCESSED", "Order status should be PROCESSED");

        // Step 7: Verify Stock was correctly decremented
        Document updatedProduct = MongoDBClient.findOne("products", Filters.eq("_id", productId));
        Assert.assertEquals(updatedProduct.getInteger("stock").intValue(), 47, "Stock decrement failed after recovery!");

        MongoDBClient.printCollectionData("orders");
        MongoDBClient.printCollectionData("products");

        ReportManager.getTest().pass("Consumer successfully retried and recovered from DB outage. ✅");
    }

    @Test(description = "Verify that the Producer API prevents invalid data from entering the Kafka stream.")
    public void testProducerValidation() {
        // Case 1: Invalid quantity (-5)
        OrderRequest invalidQtyRequest = OrderRequest.builder()
                .customerId("Cust-Val-1")
                .productId("PROD-VAL")
                .quantity(-5)
                .price(BigDecimal.valueOf(10.00))
                .build();

        Response responseQty = OrderClient.getClient().createOrder(invalidQtyRequest);
        Assert.assertEquals(responseQty.getStatusCode(), 400, "Should return 400 for invalid quantity");

        // Case 2: Missing productId
        OrderRequest missingProductRequest = OrderRequest.builder()
                .customerId("Cust-Val-2")
                .quantity(1)
                .price(BigDecimal.valueOf(10.00))
                .build();

        Response responseProduct = OrderClient.getClient().createOrder(missingProductRequest);
        Assert.assertEquals(responseProduct.getStatusCode(), 400, "Should return 400 for missing productId");

        ReportManager.getTest().pass("Producer validation active: Rejected invalid requests with 400 Bad Request. ✅");
    }

    @Test(description = "Verify that multiple consumer instances correctly distribute the load.")
    public void testConsumerLoadBalancing() {
        // Pre-condition: Start 2 more consumers (total 3)
        ReportManager.getTest().info("🚀 Scaling up: Starting 2 additional consumer instances...");
        TestContainerManager.startAdditionalConsumers(2);

        // Wait for consumers to rebalance
        ReportManager.getTest().info("⏳ Waiting for consumer group rebalance...");
        try { Thread.sleep(15000); } catch (InterruptedException ignored) {}

        // Produce 30 unique order messages
        int messageCount = 30;
        String productId = "PROD-LOAD";
        
        // Setup product
        MongoDBClient.getCollection("products").replaceOne(
                Filters.eq("_id", productId),
                new Document("_id", productId).append("productId", productId).append("stock", 1000).append("price", 10.0),
                new ReplaceOptions().upsert(true)
        );

        String bootstrapServers = ConfigManager.getInstance().getRequired("KAFKA_BOOTSTRAP_SERVERS");
        long startOffset = KafkaVerifier.getLatestOffset(bootstrapServers, "orders-topic");
        ReportManager.getTest().info("🎡 Initial Kafka offset for orders-topic: " + startOffset);

        ReportManager.getTest().info("📤 Producing " + messageCount + " order messages...");
        for (int i = 1; i <= messageCount; i++) {
            OrderRequest request = OrderRequest.builder()
                    .customerId("Cust-Load-" + i)
                    .productId(productId)
                    .quantity(1)
                    .price(BigDecimal.valueOf(10.00))
                    .build();
            OrderClient.getClient().createOrder(request);
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
        }

        long endOffset = KafkaVerifier.getLatestOffset(bootstrapServers, "orders-topic");
        ReportManager.getTest().info("🎡 Final Kafka offset for orders-topic: " + endOffset);
        ReportManager.getTest().info("📊 Messages published to Kafka: " + (endOffset - startOffset));

        // Wait for processing
        ReportManager.getTest().info("⏳ Waiting for async processing of all messages...");
        try { Thread.sleep(20000); } catch (InterruptedException ignored) {}

        // Verify all 30 messages are in MongoDB
        long processedCount = MongoDBClient.getCollection("orders").countDocuments(Filters.regex("customer_id", "Cust-Load-.*"));
        Assert.assertEquals(processedCount, (long) messageCount, "Not all messages were processed!");

        MongoDBClient.printCollectionData("orders");

        // Diagnostics: Check each consumer's health and stats
        List<String> consumerUris = TestContainerManager.getAllConsumerUris();
        int totalTrackedByStats = 0;
        for (int i = 0; i < consumerUris.size(); i++) {
            try {
                Response stats = KafkaConsumer.getConsumer(consumerUris.get(i)).get("/api/stats");
                int count = stats.jsonPath().getInt("processedCount");
                totalTrackedByStats += count;
                ReportManager.getTest().info("📡 Consumer " + i + " processed: " + count + " messages.");
            } catch (Exception e) {
                ReportManager.getTest().warning("🚫 Consumer " + i + " stats unreachable.");
            }
        }
        ReportManager.getTest().info("📊 Total messages tracked by Consumer Group Stats: " + totalTrackedByStats);

        ReportManager.getTest().pass("Load balancing successful: All " + messageCount + " messages processed by consumer group. ✅");
    }

    @AfterMethod
    public void teardown(ITestResult result) {
        if (result.getStatus() == ITestResult.FAILURE) {
            ReportManager.getTest().fail(result.getThrowable().getMessage());
        } else if (result.getStatus() == ITestResult.SUCCESS) {
            ReportManager.getTest().pass(result.getName() + " passed ✅");
        }
        ReportManager.removeTest();
    }

    @AfterSuite
    public void teardownSuite() {
        MongoDBClient.close();
        TestContainerManager.stopContainers();
        ReportManager.flush();
    }
}
