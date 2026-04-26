# QA Test Strategy & Test Cases: Kafka Event Streaming System

This document outlines the test strategy and detailed test cases for the Kafka-based E-commerce order processing system.

## 1. Overview
The system consists of a **Java Spring Boot Producer** that publishes order events and a **Python Async Consumer** that processes these events, updates stock in MongoDB, and handles errors via a Dead Letter Queue (DLQ).

---

## 2. Test Cases

### TC-001: Successful End-to-End Order Processing
**Description:** Verify that a valid order request submitted via the API is correctly published, consumed, and persisted with stock adjustment.
**Pre-conditions:**
- Kafka and MongoDB services are running.
- Product with ID `PROD-123` exists with stock `100`.
**Steps:**
1. Send a `POST` request to `/api/orders` with a valid payload (customerId, productId, quantity, price).
2. Verify the API response.
3. Check the `orders-topic` for the published message.
4. Verify the `orders` collection in MongoDB for a new record.
5. Verify the `products` collection stock for `PROD-123`.
**Acceptance Criteria:**
- Producer returns `201 Created` with a unique `orderId`.
- Message in Kafka contains the generated `orderId` and matches the request payload.
- Consumer persists the order with status `PROCESSED`.
- Product stock is decremented by the requested quantity.
**Expected Behavior:** The entire flow completes within < 2 seconds, maintaining data integrity across Kafka and MongoDB.

---

### TC-002: Consumer Idempotency (Duplicate Message Handling)
**Description:** Ensure that if a message is delivered multiple times (due to Kafka retries or consumer crashes), it is only processed once.
**Acceptance Criteria:**
- Produce two identical messages (same `orderId`) to the `orders-topic`.
- The first message is processed and saved to MongoDB.
- The second message is detected as a duplicate (via `DuplicateKeyError` on `_id`).
- The consumer commits the offset for the second message without updating the database or stock again.
**Expected Behavior:** Only one record exists in MongoDB; product stock is only decremented once.

---

### TC-003: Error Handling - Malformed JSON Payload
**Description:** Verify the system's "Fail-Fast" and "Isolate" behavior when encountering unparseable messages.
**Steps:**
1. Manually produce a non-JSON string (e.g., "invalid-data") to the `orders-topic`.
2. Monitor consumer logs and the `orders-dlq-topic`.
**Acceptance Criteria:**
- Consumer does not crash or hang.
- Consumer logs a `JSONDecodeError`.
- The malformed message is published to `orders-dlq-topic` with an `error` header.
- The original topic offset is committed to move past the bad message.
**Expected Behavior:** Invalid data is moved to DLQ for manual inspection, and the main pipeline continues processing valid messages.

---

### TC-004: Resilience - Database Connectivity Interruption
**Description:** Verify that the consumer retries database operations during transient DB outages.
**Steps:**
1. Produce a valid order message.
2. Stop the MongoDB service before the consumer processes the message.
3. Observe consumer retry logs.
4. Restart MongoDB.
**Acceptance Criteria:**
- Consumer uses exponential backoff for retries.
- Consumer does not commit the offset during DB failure.
- Once DB is back, the message is processed successfully and offset is committed.
**Expected Behavior:** No data loss occurs during database downtime; processing resumes automatically.

---

### TC-005: Producer Validation - Invalid Requests
**Description:** Verify that the Producer API prevents invalid data from entering the Kafka stream.
**Steps:**
1. Send `POST /api/orders` with `quantity: -5`.
2. Send `POST /api/orders` with missing `productId`.
**Acceptance Criteria:**
- API returns `400 Bad Request`.
- No message is published to Kafka.
**Expected Behavior:** The Producer acts as a gatekeeper, ensuring only schema-valid events are streamed.

---

### TC-006: Consumer Group - Load Balancing
**Description:** Verify that multiple consumer instances correctly distribute the load.
**Pre-conditions:** `orders-topic` has 3 partitions.
**Steps:**
1. Start 3 instances of the `KafkaConsumer` service with the same `group_id`.
2. Produce 30 unique order messages.
**Acceptance Criteria:**
- Each consumer instance handles approximately 10 messages.
- No message is processed by more than one consumer.
**Expected Behavior:** Throughput scales linearly with the number of partitions and consumers.

---

## 3. Automation Implementation Plan

| Layer | Tooling Recommendation | Rationale |
| :--- | :--- | :--- |
| **API Testing** | RestAssured (Java) / Pytest-requests (Python) | For validating Producer response codes and payload schemas. |
| **Infrastructure** | Testcontainers | To spin up ephemeral Kafka and MongoDB instances for integration tests. |
| **Kafka Validation** | AIOKafka (Python) / Kafka TestUtils (Java) | To programmatically peek into topics and verify message content. |
| **Contract Testing** | Pact | To ensure Producer and Consumer agree on the `OrderEvent` schema. |
| **Performance** | JMeter or k6 | To measure end-to-end latency and consumer lag under high load. |

---

## 4. Proposed Metrics for Dashboard
- **Mean Time to Process (MTTP):** Time from API request to DB persistence.
- **Consumer Lag:** Number of unprocessed messages in `orders-topic`.
- **DLQ Rate:** Percentage of messages failing and moving to DLQ.
- **Success Rate:** HTTP 201 vs 4xx/5xx responses.
