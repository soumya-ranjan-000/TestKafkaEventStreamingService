package gammazon.automation.tests;

import gammazon.automation.clients.KafkaConsumer;
import gammazon.automation.clients.OrderClient;
import gammazon.automation.models.OrderRequest;
import gammazon.automation.utils.RandomGenerator;
import io.restassured.response.Response;
import gammazon.automation.core.BaseAPI;
import gammazon.automation.core.ReportManager;
import org.testng.Assert;
import org.testng.ITestResult;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.AfterSuite;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;

public class OrderCreationHealthTest {

    @BeforeMethod
    void setup(Method method) {
        ReportManager.getInstance();
        ReportManager.setTest(ReportManager.getInstance().createTest(method.getName()));
    }

    @Test
    public void shouldCreateOrderSuccessfully() {
        String customerId = "Customer-"+ RandomGenerator.generateNumber(5);

        OrderRequest request = OrderRequest.builder()
                .customerId(customerId)
                .productId("P1")
                .quantity(2)
                .price(BigDecimal.valueOf(2499.99))
                .build();

        Response orderResponse = OrderClient.getClient().createOrder(request);
        Assert.assertEquals(orderResponse.getStatusCode(), 200);

        //verify response schema
        BaseAPI.validateSchema("schemas/order-creation-response-schema.json",orderResponse);

        //verify other response fields
        Assert.assertEquals(orderResponse.jsonPath().getString("message"), "Order event published successfully");
        Assert.assertEquals(orderResponse.jsonPath().getString("status"), "SUCCESS");

        String orderId = orderResponse.jsonPath().getString("orderId");

        //check if consumer processed the order successfully by querying the order by order id

        Response consumerResponse = KafkaConsumer.getConsumer().get("/api/orders/" + orderId);
        Assert.assertEquals(consumerResponse.getStatusCode(), 200);

        //validate response schema
        BaseAPI.validateSchema("schemas/consumer-get-order-schema.json",consumerResponse);
        Assert.assertEquals(consumerResponse.jsonPath().getString("status"), "PROCESSED");
    }



    @AfterMethod
    void teardown(ITestResult result) {
        if (result.getStatus() == ITestResult.FAILURE) {
            ReportManager.getTest().fail(result.getThrowable().getMessage());
        } else if (result.getStatus() == ITestResult.SUCCESS) {
            ReportManager.getTest().pass(result.getName() + " passed ✅");
        }
        ReportManager.removeTest(); // ← clean up ThreadLocal
    }

    @AfterSuite
    void afterSuite() {
        ReportManager.flush();  // generate final report
    }

}
