package com.claims_engine.policy.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
    public Queue policyDeadLetterQueue() {
        return QueueBuilder.durable("q.policy.dlq").build();
    }

    @Bean
    public Binding policyDeadLetterBinding(
            Queue policyDeadLetterQueue,
            TopicExchange claimsDeadLetterExchange
    ) {
        return BindingBuilder.bind(policyDeadLetterQueue)
                .to(claimsDeadLetterExchange)
                .with("#");
    }

    @Bean
    public Queue policyClaimSubmittedQueue() {
        return QueueBuilder.durable(claimSubmittedQueueName)
                .withArgument("x-dead-letter-exchange", dlqExchangeName)
                .withArgument("x-dead-letter-routing-key", "policy.dlq")
                .build();
    }

    @Bean
    public Binding policyClaimSubmittedBinding(
            Queue policyClaimSubmittedQueue,
            TopicExchange claimsEventsExchange
    ) {
        return BindingBuilder.bind(policyClaimSubmittedQueue)
                .to(claimsEventsExchange)
                .with(claimSubmittedRoutingKey);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(
            ConnectionFactory connectionFactory,
            MessageConverter jsonMessageConverter
    ) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        template.setMandatory(true);
        template.setBeforePublishPostProcessors(message -> {
            message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return message;
        });
        return template;
    }
}