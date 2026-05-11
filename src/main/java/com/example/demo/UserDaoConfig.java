package com.example.demo;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuracao para selecionar a implementacao de UserDao em runtime.
 *
 * O Spring resolve as propriedades placeholder apenas em tempo de execucao,
 * nao em anotacoes. Este bean factory seleciona qual implementacao
 * (jpa, jdbc, mongo, dtx) sera injetada baseado na propriedade app.dao.impl.
 *
 * Cada implementacao individual tem @ConditionalOnProperty, entao apenas
 * uma sera carregada. Este bean apenas serve como "fallback wiring".
 *
 * @author DAC
 * @version 2.0
 */
@Configuration
public class UserDaoConfig {

    /**
     * Factory bean que seleciona a implementacao de UserDao.
     *
     * Ordem de prioridade: dtx → mongo → jdbc → jpa.
     * (dtx primeiro porque eh o foco deste trabalho)
     */
    @Bean
    public UserDao userDao(
            ObjectProvider<UserDtxDao> dtxDao,
            ObjectProvider<UserMongoDao> mongoDao,
            ObjectProvider<UserJdbcDao> jdbcDao,
            ObjectProvider<UserJpaDao> jpaDao) {

        UserDao chosen;
        String selected;
        if ((chosen = dtxDao.getIfAvailable()) != null)        selected = "dtx";
        else if ((chosen = mongoDao.getIfAvailable()) != null) selected = "mongo";
        else if ((chosen = jdbcDao.getIfAvailable()) != null)  selected = "jdbc";
        else if ((chosen = jpaDao.getIfAvailable()) != null)   selected = "jpa";
        else throw new IllegalStateException("Nenhuma implementacao de UserDao disponivel");

        System.out.println("[CONFIG] Implementacao de UserDao ativa: " + selected
                + " (" + chosen.getClass().getSimpleName() + ")");
        return chosen;
    }
}
