package io.github.rnachoc.mercadillo.notifier;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Starts the notifier, which tells customers their order was placed.
 *
 * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
 */
@SpringBootApplication
public class NotifierApplication {

    /**
     * Runs the service.
     *
     * @param args command-line arguments, passed on to Spring Boot
     * @author Ignacio Rodriguez Caro <rnachoccontact@gmail.com>
     */
    public static void main(String[] args) {
        SpringApplication.run(NotifierApplication.class, args);
    }
}
