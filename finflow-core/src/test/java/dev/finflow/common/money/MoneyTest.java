package dev.finflow.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void addsSameCurrency() {
        Money sum = Money.of("10.50", "RUB").add(Money.of("0.25", "RUB"));

        assertThat(sum).isEqualTo(Money.of("10.75", "RUB"));
    }

    @Test
    void subtractsAndNegates() {
        Money result = Money.of("5", "RUB").subtract(Money.of("7.5", "RUB"));

        assertThat(result.isNegative()).isTrue();
        assertThat(result.negate()).isEqualTo(Money.of("2.5", "RUB"));
    }

    @Test
    void rejectsDifferentCurrencies() {
        assertThatThrownBy(() -> Money.of("1", "RUB").add(Money.of("1", "USD")))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void rejectsTooManyDecimalPlaces() {
        assertThatThrownBy(() -> Money.of("1.00001", "RUB"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizesScaleSoEqualsWorks() {
        assertThat(Money.of("1", "RUB")).isEqualTo(Money.of(new BigDecimal("1.0000"), "RUB"));
    }

    @Test
    void zeroIsZero() {
        assertThat(Money.of("0.00", "RUB").isZero()).isTrue();
        assertThat(Money.of("0.01", "RUB").isPositive()).isTrue();
    }

    @Test
    void comparesAmounts() {
        assertThat(Money.of("2", "RUB")).isGreaterThan(Money.of("1.99", "RUB"));
    }
}
