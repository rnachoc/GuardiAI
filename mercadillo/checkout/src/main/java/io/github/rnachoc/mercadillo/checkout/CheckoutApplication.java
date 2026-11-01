package io.github.rnachoc.mercadillo.checkout;

import java.time.Clock;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.web.client.RestClient;

/**
 * Starts the checkout service, which turns a cart into an order.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
@SpringBootApplication
public class CheckoutApplication {

    /**
     * Runs the service.
     *
     * @param args command-line arguments, passed on to Spring Boot
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public static void main(String[] args) {
        SpringApplication.run(CheckoutApplication.class, args);
    }

    /**
     * Builds the client for the cart service.
     *
     * @param builder Spring's builder, configured with the service's timeouts
     * @param url the cart service's base URL
     * @return the client
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Bean
    RestClient cartRestClient(RestClient.Builder builder, @Value("${mercadillo.cart-url}") String url) {
        return builder.clone().baseUrl(url).build();
    }

    /**
     * Builds the client for the catalog service.
     *
     * @param builder Spring's builder, configured with the service's timeouts
     * @param url the catalog's base URL
     * @return the client
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Bean
    RestClient catalogRestClient(RestClient.Builder builder, @Value("${mercadillo.catalog-url}") String url) {
        return builder.clone().baseUrl(url).build();
    }

    /**
     * Builds the client for the inventory service.
     *
     * @param builder Spring's builder, configured with the service's timeouts
     * @param url the inventory's base URL
     * @return the client
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Bean
    RestClient inventoryRestClient(RestClient.Builder builder, @Value("${mercadillo.inventory-url}") String url) {
        return builder.clone().baseUrl(url).build();
    }

    /**
     * Builds the client for the payments service.
     *
     * @param builder Spring's builder, configured with the service's timeouts
     * @param url the payment provider's base URL
     * @return the client
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Bean
    RestClient paymentsRestClient(RestClient.Builder builder, @Value("${mercadillo.payments-url}") String url) {
        return builder.clone().baseUrl(url).build();
    }

    /**
     * Declares the topic placed orders are announced on, so it exists before the first order.
     *
     * @return the topic, with three partitions
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Bean
    NewTopic ordersPlacedTopic() {
        return TopicBuilder.name(OrderEvents.TOPIC).partitions(3).replicas(1).build();
    }

    /**
     * Provides the clock that stamps orders.
     *
     * @return the system clock, in UTC
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
