package br.com.microservices.orchestrated.orderservice.core.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
@Document(collation = "order")
public class Order {

    @Id
    private String id;

    private List<OrderProducts> products;

    private LocalDateTime createdAt;

    private String transactionId;

    private BigDecimal totalAmount;

    private int totalItems;
}
