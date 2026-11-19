package com.claims_engine.ingestion.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
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
    
    @Value ("${claims.broker.exchange}")
    private String exchangeName;

    @Value("${claims.broker.dlq-exchange}")
    private String dlqExchangeName;

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
    public Queue ingestionDeadLetterQueue() {
        return QueueBuilder.durable("q.ingestion.dlq").build();
    }

    @Bean
    public Binding ingestionDeadLetterBinding(
            Queue ingestionDeadLetterQueue,
            TopicExchange claimsDeadLetterExchange
    ) {
        return BindingBuilder.bind(ingestionDeadLetterQueue)
                .to(claimsDeadLetterExchange)
                .with("#");
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
        return template;
    }


}
