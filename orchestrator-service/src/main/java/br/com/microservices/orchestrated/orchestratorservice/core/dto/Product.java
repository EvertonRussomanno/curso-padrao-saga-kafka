package br.com.microservices.orchestrated.orchestratorservice.core.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@NoArgsConstructor
@AllArgsConstructor
@Data
public class Product {

    private String code;

    private BigDecimal unitValue;
}
