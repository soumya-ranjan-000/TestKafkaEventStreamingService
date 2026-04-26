package gammazon.automation.models;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data                // Generates Getters, Setters, toString, equals, and hashCode
@Builder             // Enables the Fluent Builder pattern
@NoArgsConstructor   // Required for Jackson to deserialize JSON
@AllArgsConstructor  // Required for the Builder pattern
public class OrderRequest {
    private String customerId;
    private String productId;
    private Integer quantity;
    private BigDecimal price; // Using BigDecimal is best practice for currency/price
}

