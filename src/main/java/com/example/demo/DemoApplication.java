package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Classe principal da aplicacao Spring Boot.
 *
 * Demonstra Transacoes Distribuidas (DTX) sobre H2 + MongoDB usando
 * tres padroes:
 *   - Two-Phase Commit (2PC) manual
 *   - Transactional Outbox + Relay assincrono
 *   - SAGA com compensacoes
 *
 * @EnableScheduling habilita o OutboxRelay que polla a tabela outbox_events
 * e replica os eventos PENDING para o MongoDB.
 *
 * @author DAC
 * @version 2.0
 */
@SpringBootApplication
@EnableScheduling
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}
