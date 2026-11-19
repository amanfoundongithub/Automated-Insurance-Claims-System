package com.claims_engine.settlement.config;

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

    @Value("${settlement.broker.exchange:claims.events}")
    private String exchangeName;

    @Value("${settlement.broker.dlq-exchange:claims.events.dlq}")
    private String dlqExchangeName;

    @Value("${settlement.broker.settlement-requested-queue:q.settlement.settlement-requested}")
    private String settlementRequestedQueueName;

    @Value("${settlement.broker.settlement-requested-routing-key:settlement.requested}")
    private String settlementRequestedRoutingKey;

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
    public Queue settlementDeadLetterQueue() {
        return QueueBuilder.durable("q.settlement.dlq").build();
    }

    @Bean
    public Binding settlementDeadLetterBinding(
            Queue settlementDeadLetterQueue,
            TopicExchange claimsDeadLetterExchange
    ) {
        return BindingBuilder.bind(settlementDeadLetterQueue)
                .to(claimsDeadLetterExchange)
                .with("#");
    }

    @Bean
    public Queue settlementRequestedQueue() {
        return QueueBuilder.durable(settlementRequestedQueueName)
                .withArgument("x-dead-letter-exchange", dlqExchangeName)
                .withArgument("x-dead-letter-routing-key", "settlement.dlq")
                .build();
    }

    @Bean
    public Binding settlementRequestedBinding(
            Queue settlementRequestedQueue,
            TopicExchange claimsEventsExchange
    ) {
        return BindingBuilder.bind(settlementRequestedQueue)
                .to(claimsEventsExchange)
                .with(settlementRequestedRoutingKey);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}