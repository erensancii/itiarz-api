package com.itiarz.config;

import com.itiarz.domain.CaseReason;
import com.itiarz.domain.DisputeCase;
import com.itiarz.repo.DisputeCaseRepository;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Configuration
public class DemoData {
    @Bean
    @ConditionalOnProperty(prefix = "app", name = "seed-demo", havingValue = "true")
    ApplicationRunner seedExampleCases(DisputeCaseRepository repository) {
        return args -> {
            if (repository.count() != 0) return;
            var now = Instant.now();
            repository.saveAll(List.of(
                new DisputeCase("Örnek Banka", "CB-2026-001", "ORD-1452",
                    CaseReason.NotReceived, new BigDecimal("2850.00"), "TRY", now.plus(2, ChronoUnit.DAYS)),
                new DisputeCase("Örnek PSP", "CB-2026-002", "ORD-1453",
                    CaseReason.Fraud, new BigDecimal("1750.00"), "TRY", now.plus(1, ChronoUnit.DAYS)),
                new DisputeCase("Örnek Banka", "CB-2026-003", "ORD-1454",
                    CaseReason.Duplicate, new BigDecimal("950.00"), "TRY", now.plus(5, ChronoUnit.DAYS))
            ));
        };
    }
}
