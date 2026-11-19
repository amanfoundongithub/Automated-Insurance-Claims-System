package com.claims_engine.orchestrator.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    @Value("${claims.broker.exchange}")
    private String exchangeName;

    @Value("${claims.broker.dlq-exchange}")
    private String dlqExchangeName;

    @Value("${claims.broker.claim-submitted-queue}")
    private String claimSubmittedQueueName;

    @Value("${claims.broker.claim-submitted-routing-key}")
    private String claimSubmittedRoutingKey;

    @Value("${claims.broker.policy-validated-queue:q.orchestrator.policy-validated}")
    private String policyValidatedQueueName;

    @Value("${claims.broker.policy-validated-routing-key:policy.validated}")
    private String policyValidatedRoutingKey;

    @Value("${claims.broker.policy-validation-failed-queue:q.orchestrator.policy-validation-failed}")
    private String policyValidationFailedQueueName;

    @Value("${claims.broker.policy-validation-failed-routing-key:policy.validation-failed}")
    private String policyValidationFailedRoutingKey;

    @Bean
    public TopicExchange claimsEventsExchange() {
        return ExchangeBuilder.topicExchange(exchangeName)
                .durable(true)
                .build();
    }

    @Bean
    public TopicExchange claimsDeadLetterExchange() {
        return ExchangeBuilder.topicExchange(dlqExchangeName)
                .durable(true)
                .build();
    }

    @Bean
    public Queue orchestratorDeadLetterQueue() {
        return QueueBuilder.durable("q.orchestrator.dlq").build();
    }

    @Bean
    public Binding orchestratorDeadLetterBinding(
            Queue orchestratorDeadLetterQueue,
            TopicExchange claimsDeadLetterExchange
    ) {
        return BindingBuilder.bind(orchestratorDeadLetterQueue)
                .to(claimsDeadLetterExchange)
                .with("#");
    }

    @Bean
    public Queue claimSubmittedQueue() {
        return QueueBuilder.durable(claimSubmittedQueueName)
                .withArgument("x-dead-letter-exchange", dlqExchangeName)
                .withArgument("x-dead-letter-routing-key", "orchestrator.dlq")
                .build();
    }

    @Bean
    public Binding claimSubmittedBinding(
            Queue claimSubmittedQueue,
            TopicExchange claimsEventsExchange
    ) {
        return BindingBuilder.bind(claimSubmittedQueue)
                .to(claimsEventsExchange)
                .with(claimSubmittedRoutingKey);
    }

    @Bean
    public Queue policyValidatedQueue() {
        return QueueBuilder.durable(policyValidatedQueueName)
                .withArgument("x-dead-letter-exchange", dlqExchangeName)
                .withArgument("x-dead-letter-routing-key", "orchestrator.dlq")
                .build();
    }

    @Bean
    public Binding policyValidatedBinding(
            Queue policyValidatedQueue,
            TopicExchange claimsEventsExchange
    ) {
        return BindingBuilder.bind(policyValidatedQueue)
                .to(claimsEventsExchange)
                .with(policyValidatedRoutingKey);
    }

    @Bean
    public Queue policyValidationFailedQueue() {
        return QueueBuilder.durable(policyValidationFailedQueueName)
                .withArgument("x-dead-letter-exchange", dlqExchangeName)
                .withArgument("x-dead-letter-routing-key", "orchestrator.dlq")
                .build();
    }

    @Bean
    public Binding policyValidationFailedBinding(
            Queue policyValidationFailedQueue,
            TopicExchange claimsEventsExchange
    ) {
        return BindingBuilder.bind(policyValidationFailedQueue)
                .to(claimsEventsExchange)
                .with(policyValidationFailedRoutingKey);
    }
    

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

}