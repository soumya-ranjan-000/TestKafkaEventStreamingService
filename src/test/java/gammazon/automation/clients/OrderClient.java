package gammazon.automation.clients;

import gammazon.automation.models.OrderRequest;
import io.restassured.response.Response;
import gammazon.automation.core.BaseAPI;
import gammazon.automation.core.ConfigManager;

public class OrderClient extends BaseAPI {

    public OrderClient() {
        super(ConfigManager.getInstance().get("GAMMAZON_BACKEND_URI"));
    }

    public static OrderClient getClient() {
        return new OrderClient();
    }

    public Response createOrder(OrderRequest request) {
        return post("/api/orders",request);
    }
}
