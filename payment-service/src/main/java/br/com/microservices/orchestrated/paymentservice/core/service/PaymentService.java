package br.com.microservices.orchestrated.paymentservice.core.service;

import br.com.microservices.orchestrated.paymentservice.config.exception.ValidationException;
import br.com.microservices.orchestrated.paymentservice.core.dto.Event;
import br.com.microservices.orchestrated.paymentservice.core.dto.History;
import br.com.microservices.orchestrated.paymentservice.core.dto.OrderProducts;
import br.com.microservices.orchestrated.paymentservice.core.enums.EPaymentStatus;
import br.com.microservices.orchestrated.paymentservice.core.enums.ESagaStatus;
import br.com.microservices.orchestrated.paymentservice.core.model.Payment;
import br.com.microservices.orchestrated.paymentservice.core.producer.KafkaProducer;
import br.com.microservices.orchestrated.paymentservice.core.repository.PaymentRepository;
import br.com.microservices.orchestrated.paymentservice.core.utils.JsonUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.sql.ast.tree.expression.Every;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static br.com.microservices.orchestrated.paymentservice.core.enums.ESagaStatus.*;

@Slf4j
@Service
@AllArgsConstructor
public class PaymentService {

    private static final String CURRENT_SOURCE = "PAYMENT_SERVICE";

    private static final BigDecimal REDUCE_SUM_VALUE = new BigDecimal("0.0");

    private static final BigDecimal MIN_AMOUNT_VALUE = new BigDecimal("0.01");

    private final JsonUtil jsonUtil;

    private final KafkaProducer kafkaProducer;

    private final PaymentRepository paymentRepository;

    public void realizePayment(Event event){
        try{
            checkCurrentValidation(event);
            createPendingPayment(event);
            var payment = findByOrdeIdAndTransactionId(event);
            validateAmount(payment.getTotalAmount());
            changePaymentToSuccess(payment);
            handleSuccess(event);
        }catch (Exception ex){
            log.error("Error trying to make payment!");
            handleFailCurrentNotExecuted(event, ex.getMessage());
        }
        kafkaProducer.sendEvent(jsonUtil.toJason(event));
    }

    private void checkCurrentValidation(Event event){
        if (paymentRepository.existsByOrderIdAndTransactionId(event.getPayload().getId(), event.getTransactionId())){
            throw new ValidationException("Theres another transactionId for this validation.");
        }
    }

    private void createPendingPayment(Event event){
        var totalAmount = calculateAmount(event);
        var totalItems = calculateTotalItems(event);
        var payment = Payment
                .builder()
                .orderId(event.getPayload().getId())
                .transactionId(event.getTransactionId())
                .totalAmount(totalAmount)
                .totalItems(totalItems)
                .build();
        save(payment);
        setEventAmountItems(event, payment);
    }

    private BigDecimal calculateAmount(Event event){
        return event.getPayload()
                .getProducts()
                .stream()
                .map(product -> product.getProduct().getUnitValue().multiply(BigDecimal.valueOf(product.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private int calculateTotalItems(Event event){
        return event.getPayload()
                .getProducts()
                .stream()
                .map(OrderProducts::getQuantity)
                .reduce(REDUCE_SUM_VALUE.intValue(), Integer::sum);
    }

    private void setEventAmountItems(Event event, Payment payment){
        event.getPayload().setTotalAmount(payment.getTotalAmount());
        event.getPayload().setTotalItems(payment.getTotalItems());
    }

    private void changePaymentToSuccess(Payment payment){
        payment.setEPaymentStatus(EPaymentStatus.SUCCESS);
        save(payment);
    }

    private void validateAmount(BigDecimal amount){
        if(amount.compareTo(MIN_AMOUNT_VALUE) < 0){
            throw new ValidationException("The minimum amount available is ".concat(MIN_AMOUNT_VALUE.toString()));
        }
    }

    private void handleSuccess(Event event){
        event.setStatus(SUCCESS);
        event.setSource(CURRENT_SOURCE);
        addHistory(event, "Payment realized successfully!");
    }

    private void addHistory(Event event, String message){
        var history = History
                .builder()
                .source(event.getSource())
                .status(event.getStatus())
                .message(message)
                .createdAt(LocalDateTime.now())
                .build();
        event.addToHistory(history);
    }

    private void handleFailCurrentNotExecuted(Event event, String message){
        event.setStatus(ROLLBACK_PENDING);
        event.setSource(CURRENT_SOURCE);
        addHistory(event, "Fail to realize payment: ".concat(message));
    }

    public void realizeRefund(Event event){
        event.setStatus(FAIL);
        event.setSource(CURRENT_SOURCE);
        try{
            changePaymetStatusToRefund(event);
            addHistory(event, "Rollback executed for payment!");
        }catch (Exception ex){
            addHistory(event, "Rollback not executed for payment: ".concat(ex.getMessage()));
        }


        kafkaProducer.sendEvent(jsonUtil.toJason(event));
    }

    private void changePaymetStatusToRefund(Event event){
        var payment = findByOrdeIdAndTransactionId(event);
        payment.setEPaymentStatus(EPaymentStatus.REFUND);
        setEventAmountItems(event, payment);
        save(payment);
    }

    private Payment findByOrdeIdAndTransactionId(Event event){
        return paymentRepository.findByOrderIdAndTransactionId(
                        event.getPayload().getId(), event.getTransactionId())
                .orElseThrow(() -> new ValidationException("Payment not found by orderId and transactionId."));
    }

    private void save(Payment payment){
        paymentRepository.save(payment);
    }
}
